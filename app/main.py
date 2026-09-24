"""music-add：手机端添加歌曲到飞牛曲库（FastAPI 主程序）。

功能：
  - 免密登录（只填飞牛音乐账号，校验该账号在飞牛音乐里存在）
  - 搜索（调 music-dl），列表点歌名即在页面内播放
  - 下载：服务端拉流写盘到挂载的曲库目录（默认 /music = /vol2/1000/video/music）
  - 下载完成后可勾选歌单，一键加进该账号的飞牛音乐歌单
  - 音源可插拔（providers/），落雪音乐后期按同一接口接入
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, asdict
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse

from . import providers
from .fnos import FnosMusic, FnosMusicError
from .providers import Track

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("DATA_DIR", "/app/data"))
STATIC_DIR = BASE_DIR / "static"
MUSIC_DIR = Path(os.environ.get("MUSIC_DIR", "/music"))
MUSICDL_BASE = os.environ.get("MUSICDL_BASE", "http://192.168.10.10:19010/music")
FNOS_SOCKET = os.environ.get("FNOS_SOCKET", "/run/trim_music.socket")
FNOS_DB = os.environ.get("FNOS_DB", "/fnos-db/music.db")
CONCURRENCY = int(os.environ.get("DOWNLOAD_CONCURRENCY", "3"))

DATA_DIR.mkdir(parents=True, exist_ok=True)
SECRET_FILE = DATA_DIR / "secret.key"
TASKS_FILE = DATA_DIR / "tasks.json"
SETTINGS_FILE = DATA_DIR / "settings.json"


def _load_settings() -> dict:
    try:
        return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _save_settings(data: dict) -> None:
    tmp = SETTINGS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(SETTINGS_FILE)


def default_playlist_for(user: str) -> dict:
    """该用户设定的"下载后自动加入"的歌单。"""
    return (_load_settings().get(user) or {}).get("defaultPlaylist") or {}

fnos = FnosMusic(socket_path=FNOS_SOCKET, db_path=FNOS_DB)

# ------------------------------------------------------------------ 本地歌单（不需要飞牛账号）

LOCAL_USER = "__local__"          # 本地模式的内部用户名
LOCAL_PL_FILE = DATA_DIR / "local_playlists.json"


def is_local(user: str) -> bool:
    return user == LOCAL_USER


def _load_local_playlists() -> list[dict]:
    try:
        data = json.loads(LOCAL_PL_FILE.read_text(encoding="utf-8"))
        return data.get("playlists") or []
    except Exception:
        return []


def _save_local_playlists(items: list[dict]) -> None:
    tmp = LOCAL_PL_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps({"playlists": items}, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(LOCAL_PL_FILE)


def local_playlist(guid: str) -> dict | None:
    for p in _load_local_playlists():
        if p.get("guid") == guid:
            return p
    return None


def local_playlists_brief() -> list[dict]:
    return [
        {
            "guid": p.get("guid"),
            "name": p.get("name") or "未命名歌单",
            "trackCount": len(p.get("tracks") or []),
            "source": "local",
        }
        for p in _load_local_playlists()
    ]


def all_playlists(user: str) -> list[dict]:
    """歌单总表 = 本地歌单 +（有飞牛账号时）该账号的飞牛歌单。"""
    items = local_playlists_brief()
    if not is_local(user):
        try:
            for p in fnos.list_playlists(fnos.token_for(user) or ""):
                p["source"] = "fnos"
                items.append(p)
        except FnosMusicError:
            pass
    return items
providers.register(providers.MusicDlProvider(MUSICDL_BASE))

# 落雪音乐（可选）：部署好后设 LXMUSIC_BASE 即自动启用
LXMUSIC_BASE = os.environ.get("LXMUSIC_BASE", "").strip()
if LXMUSIC_BASE:
    providers.register(providers.LxMusicProvider(LXMUSIC_BASE))

app = FastAPI(title="music-add", docs_url=None, redoc_url=None)
_executor = ThreadPoolExecutor(max_workers=CONCURRENCY, thread_name_prefix="dl")


# ------------------------------------------------------------------ 会话

def _secret() -> bytes:
    if not SECRET_FILE.exists():
        SECRET_FILE.write_bytes(base64.urlsafe_b64encode(os.urandom(32)))
    return SECRET_FILE.read_bytes()


def make_session(username: str, days: int = 60) -> str:
    exp = int(time.time()) + days * 86400
    payload = f"{username}|{exp}"
    sig = hmac.new(_secret(), payload.encode(), hashlib.sha256).hexdigest()[:32]
    return base64.urlsafe_b64encode(f"{payload}|{sig}".encode()).decode()


def parse_session(token: str | None) -> str | None:
    if not token:
        return None
    try:
        raw = base64.urlsafe_b64decode(token.encode()).decode()
        username, exp, sig = raw.rsplit("|", 2)
    except Exception:
        return None
    expect = hmac.new(_secret(), f"{username}|{exp}".encode(), hashlib.sha256).hexdigest()[:32]
    if not hmac.compare_digest(sig, expect) or int(exp) < time.time():
        return None
    return username


def current_user(request: Request) -> str:
    user = parse_session(request.cookies.get("ma_session"))
    if not user:
        raise HTTPException(status_code=401, detail="请先登录")
    return user


# ------------------------------------------------------------------ 下载任务

@dataclass
class Task:
    id: str
    owner: str
    track: dict
    status: str = "queued"  # queued | running | done | skipped | failed
    error: str = ""
    size: int = 0
    path: str = ""
    created: float = field(default_factory=time.time)
    finished: float = 0.0
    via: str = ""  # 换源说明（如 换源→qq）
    total: int = 0  # 预计总字节（进度用）
    canceled: bool = False
    playlistGuid: str = ""
    playlistName: str = ""
    addStatus: str = ""  # "" | added | failed
    addNote: str = ""


def _task_from_dict(d: dict) -> Task:
    import dataclasses as _dc

    allowed = {f.name for f in _dc.fields(Task)}
    return Task(**{k: v for k, v in d.items() if k in allowed})


TASKS: dict[str, Task] = {}
TASKS_LOCK = threading.Lock()


def _save_tasks() -> None:
    try:
        with TASKS_LOCK:
            data = [asdict(t) for t in TASKS.values()]
        tmp = TASKS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        tmp.replace(TASKS_FILE)
    except Exception:
        pass


def _load_tasks() -> None:
    if not TASKS_FILE.exists():
        return
    try:
        for d in json.loads(TASKS_FILE.read_text(encoding="utf-8")):
            if d.get("status") in ("queued", "running"):
                d["status"] = "failed"
                d["error"] = "服务重启，任务中断"
            TASKS[d["id"]] = _task_from_dict(d)
    except Exception:
        pass


_load_tasks()


AUDIO_EXTS = (".mp3", ".m4a", ".flac", ".wav", ".ogg")


def _audio_ext(head: bytes) -> str:
    """按文件头判断真实音频格式，避免一律存成 .mp3。"""
    if head[:4] == b"fLaC":
        return ".flac"
    if head[:4] == b"OggS":
        return ".ogg"
    if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
        return ".wav"
    if head[4:8] == b"ftyp":
        return ".m4a"
    return ".mp3"


async def _download(track: Track, base_dir: Path, on_progress=None, is_canceled=None) -> tuple[str, int, str, str]:
    """拉流写盘。返回 (status, size, note, path)。"""
    base = track.filename
    for ext in AUDIO_EXTS:
        existing = base_dir / f"{base}{ext}"
        if existing.exists() and existing.stat().st_size > 0:
            return "skipped", existing.stat().st_size, "本地已有同名文件", str(existing)

    provider = providers.get(track.provider)
    tmp = base_dir / f"{base}.part"
    size = 0
    try:
        try:
            stream = provider.fetch_bytes(track, on_progress=on_progress)
        except TypeError:  # 该源还没实现进度上报
            stream = provider.fetch_bytes(track)
        async for piece in stream:
            if is_canceled and is_canceled():
                tmp.unlink(missing_ok=True)
                return "canceled", 0, "已取消", ""
            if not piece:
                continue
            with open(tmp, "ab") as fh:
                fh.write(piece)
            size += len(piece)
    except Exception as exc:  # noqa: BLE001
        tmp.unlink(missing_ok=True)
        return "failed", 0, f"取流失败：{exc}", ""

    if size < 50 * 1024:
        tmp.unlink(missing_ok=True)
        return "failed", 0, f"音频过小（{size} 字节），源可能受限或需要版权", ""

    with open(tmp, "rb") as fh:
        ext = _audio_ext(fh.read(16))
    dest = base_dir / f"{base}{ext}"
    if dest.exists() and dest.stat().st_size > 0:
        tmp.unlink(missing_ok=True)
        return "skipped", dest.stat().st_size, "本地已有同名文件", str(dest)

    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp.replace(dest)
    # 关键：容器内 umask 常是 077，写出的文件会变成 600/root，飞牛音乐（非 root）读不到 → 永远不入库
    try:
        os.chmod(dest, 0o644)
    except OSError:
        pass
    return "done", size, "", str(dest)


async def _download_any(track: Track, base_dir: Path, task: "Task | None" = None):
    """先下源；失败则自动换源重试。返回 (status, size, note, path, used_track|None)。"""

    def on_progress(size: int, total: int) -> None:
        if task is not None:
            with TASKS_LOCK:
                task.size = size
                task.total = total

    def is_canceled() -> bool:
        return bool(task is not None and task.canceled)

    st, size, note, path = await _download(track, base_dir, on_progress=on_progress, is_canceled=is_canceled)
    if st in ("done", "skipped", "canceled"):
        return st, size, note, path, None

    provider = providers.get(track.provider)
    if not hasattr(provider, "find_alternatives"):
        return st, size, note, path, None
    try:
        alts = await provider.find_alternatives(track)
    except Exception:  # noqa: BLE001
        alts = []
    for alt in alts[:3]:
        if is_canceled():
            return "canceled", 0, "已取消", "", None
        st2, size2, note2, path2 = await _download(alt, base_dir, on_progress=on_progress, is_canceled=is_canceled)
        if st2 in ("done", "skipped"):
            return st2, size2, "", path2, alt
        if st2 == "canceled":
            return "canceled", 0, "已取消", "", None
    return st, size, note, path, None


PENDING_ADDS: dict[str, float] = {}
ADDS_LOCK = threading.Lock()
ADD_WAIT_SECONDS = 1800  # 最多等 30 分钟入库（飞牛音乐扫库实测延迟较大）


def _try_add_once(task: Task) -> bool:
    """尝试一次把曲目加进歌单。返回 True = 已处理完（成功或已定性失败）。"""
    guid = task.playlistGuid
    if not guid:
        return True
    token = fnos.token_for(task.owner)
    if not token:
        with TASKS_LOCK:
            task.addStatus, task.addNote = "failed", "该账号没有飞牛音乐令牌"
        return True

    title = (task.track or {}).get("title", "")
    artist = (task.track or {}).get("artist", "")
    try:
        found = fnos.search_track(token, title, size=20)
    except FnosMusicError as exc:
        with TASKS_LOCK:
            task.addStatus, task.addNote = "failed", str(exc)
        return True

    pick = None
    for f in found:
        if _norm(f["title"]) == _norm(title) and _artist_match(f["artist"], artist):
            pick = f
            break
    if pick is None:
        for f in found:
            nt, nf = _norm(title), _norm(f["title"])
            if nt and (nt in nf or nf in nt) and _artist_match(f["artist"], artist):
                pick = f
                break
    if not pick:
        return False  # 还没入库，等下一轮

    try:
        fnos.add_tracks(token, guid, [pick["guid"]])
        with TASKS_LOCK:
            task.addStatus = "added"
            task.addNote = task.playlistName or ""
    except FnosMusicError as exc:
        with TASKS_LOCK:
            task.addStatus, task.addNote = "failed", str(exc)
    return True


def _queue_playlist_add(task: Task, wait_seconds: int = ADD_WAIT_SECONDS) -> None:
    """把任务排入后台入单队列（不阻塞下载线程）。"""
    if not task.playlistGuid:
        return
    with TASKS_LOCK:
        task.addStatus = "waiting"
        task.addNote = ""
    with ADDS_LOCK:
        PENDING_ADDS[task.id] = time.time() + wait_seconds


def _adds_worker() -> None:
    """后台入单：飞牛音乐扫库有延迟，隔一段时间试一次，超时则标失败（可手动重试）。"""
    while True:
        time.sleep(20)
        try:
            with TASKS_LOCK:
                pending = [t for t in TASKS.values() if t.addStatus == "waiting"]
            for task in pending:
                done = _try_add_once(task)
                if done:
                    with ADDS_LOCK:
                        PENDING_ADDS.pop(task.id, None)
                    _save_tasks()
                    continue
                with ADDS_LOCK:
                    deadline = PENDING_ADDS.get(task.id, time.time())
                if time.time() >= deadline:
                    with TASKS_LOCK:
                        task.addStatus = "failed"
                        task.addNote = "曲库还没扫到，可点「重试入单」"
                    with ADDS_LOCK:
                        PENDING_ADDS.pop(task.id, None)
                    _save_tasks()
        except Exception:  # noqa: BLE001
            continue


threading.Thread(target=_adds_worker, daemon=True).start()


def _run_task(task_id: str) -> None:
    with TASKS_LOCK:
        task = TASKS.get(task_id)
    if not task:
        return
    track = Track.from_dict(task.track)
    with TASKS_LOCK:
        task.status = "running"
    _save_tasks()

    status, size, note, path, used = asyncio.run(_download_any(track, MUSIC_DIR, task))

    with TASKS_LOCK:
        if task.canceled and status != "canceled":
            status, note, path = "canceled", "已取消", ""
        task.status = status
        task.size = size
        task.error = "" if status in ("done", "skipped") else note
        task.path = path
        task.via = f"换源→{used.source}" if used else ""
        task.finished = time.time()
    _save_tasks()

    if status in ("done", "skipped") and task.playlistGuid:
        _queue_playlist_add(task)
        _save_tasks()
        # 立刻试一次，命中就不用等后台轮询
        _executor.submit(lambda: (_try_add_once(task), _save_tasks()))


# ------------------------------------------------------------------ 登录

@app.post("/api/login")
async def login(payload: dict):
    want_local = bool(payload.get("local"))
    username = (payload.get("username") or "").strip()

    try:
        names = [u["name"] for u in fnos.list_users()]
    except FnosMusicError as exc:
        if not want_local:
            raise HTTPException(status_code=503, detail=f"读不到飞牛音乐账号列表：{exc}") from exc
        names = []

    if want_local:  # 本地模式：不需要飞牛音乐账号
        resp = JSONResponse({"ok": True, "username": "本地用户", "mode": "local", "fnosUsers": []})
        resp.set_cookie(
            "ma_session", make_session(LOCAL_USER), httponly=True, samesite="lax", max_age=60 * 86400
        )
        return resp

    if not username:
        raise HTTPException(status_code=400, detail="请填写飞牛音乐账号，或选“不填账号直接进入”")
    if username not in names:
        raise HTTPException(status_code=401, detail=f"飞牛音乐里没有这个账号（现有：{'、'.join(names) or '无'}）")

    resp = JSONResponse({"ok": True, "username": username, "mode": "fnos", "fnosUsers": [username]})
    resp.set_cookie(
        "ma_session", make_session(username), httponly=True, samesite="lax", max_age=60 * 86400
    )
    return resp


@app.post("/api/logout")
async def logout():
    resp = JSONResponse({"ok": True})
    resp.delete_cookie("ma_session")
    return resp


@app.get("/api/me")
async def me(user: str = Depends(current_user)):
    local = is_local(user)
    library_ok = MUSIC_DIR.exists()
    playlists = all_playlists(user)
    # 账号面板只显示「当前身份」，不下发飞牛账号列表（避免把别人的账号列出来）
    fnos_users = [] if local else [user]
    return {
        "username": "本地用户" if local else user,
        "rawUsername": user,
        "mode": "local" if local else "fnos",
        "isLocal": local,
        "allowDownload": not local,
        "fnosUsers": fnos_users,
        "library": str(MUSIC_DIR),
        "libraryWritable": os.access(MUSIC_DIR, os.W_OK) if library_ok else False,
        "providers": [{"name": p.name, "label": p.label} for p in providers.all_providers()],
        "playlists": playlists,
        "defaultPlaylist": ({} if local else default_playlist_for(user)) or None,
    }


# ------------------------------------------------------------------ 搜索 / 试听

@app.get("/api/search")
async def search(q: str, provider: str = "music-dl", user: str = Depends(current_user)):
    keyword = (q or "").strip()
    if not keyword:
        return {"items": []}
    try:
        p = providers.get(provider)
    except KeyError:
        raise HTTPException(status_code=400, detail=f"未知音源：{provider}")

    tracks = await p.search(keyword)
    tracks = providers.dedupe(tracks)
    out = []
    for t in tracks[:60]:
        d = t.to_dict()
        d["token"] = t.token()
        d["variant"] = t.is_variant
        out.append(d)
    return {"items": out, "provider": p.name}


def _track_list_out(tracks, limit: int = 200) -> list[dict]:
    out = []
    for t in tracks[:limit]:
        d = t.to_dict()
        d["token"] = t.token()
        d["variant"] = t.is_variant
        out.append(d)
    return out


@app.get("/api/categories")
async def api_categories(provider: str = "music-dl", user: str = Depends(current_user)):
    """歌单分类：音源 + 分组分类（热门/流行/主题…）。"""
    p = providers.get(provider)
    if not hasattr(p, "categories"):
        raise HTTPException(status_code=400, detail=f"音源 {provider} 暂不支持歌单分类")
    try:
        return await p.categories()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"取歌单分类失败：{exc}") from exc


@app.get("/api/category")
async def api_category(
    source: str,
    categoryId: str = "",
    categoryName: str = "",
    page: int = 1,
    provider: str = "music-dl",
    user: str = Depends(current_user),
):
    """某分类下的歌单。"""
    p = providers.get(provider)
    if not hasattr(p, "category_playlists"):
        raise HTTPException(status_code=400, detail=f"音源 {provider} 暂不支持歌单浏览")
    try:
        items = await p.category_playlists(source, categoryId, categoryName, page)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"取歌单失败：{exc}") from exc
    return {"items": items, "page": page, "source": source}


@app.get("/api/playlist")
async def api_playlist(
    source: str,
    id: str,
    page: int = 1,
    name: str = "",
    cover: str = "",
    provider: str = "music-dl",
    user: str = Depends(current_user),
):
    """歌单内曲目（可播放/下载）。"""
    p = providers.get(provider)
    if not hasattr(p, "playlist_tracks"):
        raise HTTPException(status_code=400, detail=f"音源 {provider} 暂不支持歌单详情")
    try:
        tracks, total = await p.playlist_tracks(source, id, page, name, cover)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"取歌单曲目失败：{exc}") from exc
    return {"items": _track_list_out(tracks), "total": total, "page": page}


@app.get("/api/lyric")
async def lyric(t: str, user: str = Depends(current_user)):
    """取歌词（代理音源的歌词接口，返回 LRC 文本）。"""
    try:
        track = Track.from_token(t)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="曲目标识无效") from exc
    p = providers.get(track.provider)
    if not hasattr(p, "fetch_lyric"):
        return {"lrc": "", "note": "这个音源暂时取不到歌词"}
    try:
        text = await p.fetch_lyric(track)
    except Exception as exc:  # noqa: BLE001
        return {"lrc": "", "note": f"取歌词失败：{exc}"}
    return {"lrc": text or "", "note": "" if text else "这首歌没有歌词"}


@app.get("/api/stream")
async def stream(t: str, request: Request, user: str = Depends(current_user)):
    try:
        track = Track.from_token(t)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="曲目标识无效") from exc

    provider = providers.get(track.provider)
    client, resp = await provider.stream_response(track, request.headers.get("range"))
    if resp.status_code >= 400:
        await resp.aclose()
        await client.aclose()
        raise HTTPException(status_code=502, detail=f"上游取流失败（{resp.status_code}）")

    headers = {"Accept-Ranges": "bytes", "Cache-Control": "no-store"}
    for h in ("content-type", "content-length", "content-range"):
        if h in resp.headers:
            headers[h] = resp.headers[h]

    async def body():
        try:
            async for piece in resp.aiter_bytes(64 * 1024):
                yield piece
        finally:
            await resp.aclose()
            await client.aclose()

    return StreamingResponse(body(), status_code=resp.status_code, headers=headers)


# ------------------------------------------------------------------ 下载

@app.post("/api/download")
async def download(payload: dict, user: str = Depends(current_user)):
    if is_local(user):
        raise HTTPException(status_code=403, detail="本地模式不支持下下载（没有飞牛账号，不写曲库）")
    items = payload.get("items") or []
    if not items:
        raise HTTPException(status_code=400, detail="没有选中歌曲")
    if not MUSIC_DIR.exists():
        raise HTTPException(status_code=503, detail=f"曲库目录未挂载：{MUSIC_DIR}")

    # 目标歌单：'default' = 用该用户设置的默认歌单；'' / 'none' = 只下载不加歌单
    guid = payload.get("playlistGuid")
    name = payload.get("playlistName") or ""
    if guid and guid != "default" and local_playlist(guid) is not None:
        raise HTTPException(
            status_code=400,
            detail="本地歌单不能作为下载目标（下载只写 NAS 曲库并加入飞牛音乐歌单）",
        )
    if guid == "default":
        d = default_playlist_for(user)
        guid, name = d.get("guid"), d.get("name") or ""
    if guid and local_playlist(guid) is not None:
        guid, name = "", ""      # 兜底：历史设置里的本地歌单，不参与下载
    if guid in ("", "none", None):
        guid, name = "", ""
    if guid and not name:
        try:
            for p in fnos.list_playlists(fnos.token_for(user) or "", with_count=False):
                if p["guid"] == guid:
                    name = p["name"] or ""
                    break
        except FnosMusicError:
            pass

    created = []
    for raw in items:
        try:
            track = Track.from_dict(raw)
        except Exception:
            continue
        task = Task(
            id=uuid.uuid4().hex[:12],
            owner=user,
            track=track.to_dict(),
            playlistGuid=guid or "",
            playlistName=name,
        )
        with TASKS_LOCK:
            TASKS[task.id] = task
        _executor.submit(_run_task, task.id)
        created.append(task.id)
    _save_tasks()
    return {"ok": True, "taskIds": created, "playlist": name or None}


@app.get("/api/settings")
async def get_settings(user: str = Depends(current_user)):
    return {"defaultPlaylist": default_playlist_for(user) or None}


@app.post("/api/settings")
async def set_settings(payload: dict, user: str = Depends(current_user)):
    """设置"下载后自动加入"的默认歌单；传空 guid 表示取消。

    本地歌单不参与下载（不写曲库），所以不能设为默认歌单。
    """
    guid = (payload.get("defaultPlaylistGuid") or "").strip()
    name = (payload.get("defaultPlaylistName") or "").strip()
    if guid and local_playlist(guid) is not None:
        raise HTTPException(
            status_code=400,
            detail="本地歌单不能设为下载默认歌单（它不会下载到 NAS，只保存歌单记录）",
        )
    all_s = _load_settings()
    mine = all_s.get(user) or {}
    mine["defaultPlaylist"] = {"guid": guid, "name": name} if guid else None
    all_s[user] = mine
    _save_settings(all_s)
    return {"ok": True, "defaultPlaylist": mine["defaultPlaylist"]}


@app.get("/api/alt")
async def api_alt(t: str, user: str = Depends(current_user)):
    """播放失败时换源：返回其他源的同名曲目。"""
    try:
        track = Track.from_token(t)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="曲目标识无效") from exc
    p = providers.get(track.provider)
    if not hasattr(p, "find_alternatives"):
        raise HTTPException(status_code=404, detail="该音源不支持换源")
    try:
        alts = await p.find_alternatives(track)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"换源搜索失败：{exc}") from exc
    if not alts:
        raise HTTPException(status_code=404, detail="没找到其他源的同一首歌")
    a = alts[0]
    d = a.to_dict()
    d["token"] = a.token()
    d["variant"] = a.is_variant
    d["switchedFrom"] = f"{track.source}"
    return d


@app.get("/api/tasks")
async def tasks(user: str = Depends(current_user)):
    with TASKS_LOCK:
        mine = [t for t in TASKS.values() if t.owner == user]
    mine.sort(key=lambda t: t.created, reverse=True)
    return {"items": [asdict(t) for t in mine[:60]]}


@app.post("/api/tasks/clear")
async def clear_tasks(user: str = Depends(current_user)):
    with TASKS_LOCK:
        for tid in [t.id for t in TASKS.values() if t.owner == user and t.status in ("done", "skipped", "failed")]:
            TASKS.pop(tid, None)
    _save_tasks()
    return {"ok": True}


@app.post("/api/tasks/{task_id}/remove")
async def remove_task(task_id: str, user: str = Depends(current_user)):
    """从队列移除任务；若还在下载中则一并取消（会清掉未完成的 .part 文件）。"""
    with TASKS_LOCK:
        task = TASKS.get(task_id)
        if not task or task.owner != user:
            raise HTTPException(status_code=404, detail="任务不存在")
        if task.status in ("queued", "running"):
            task.canceled = True       # 让下载线程收手
        TASKS.pop(task_id, None)
    with ADDS_LOCK:
        PENDING_ADDS.pop(task_id, None)
    _save_tasks()
    return {"ok": True}


@app.post("/api/tasks/{task_id}/readd")
async def readd_task(task_id: str, user: str = Depends(current_user)):
    """重新尝试把已完成的任务加入歌单（飞牛扫库慢时用）。"""
    with TASKS_LOCK:
        task = TASKS.get(task_id)
    if not task or task.owner != user:
        raise HTTPException(status_code=404, detail="任务不存在")
    if not task.playlistGuid:
        raise HTTPException(status_code=400, detail="这个任务没有指定歌单")
    if task.status not in ("done", "skipped"):
        raise HTTPException(status_code=400, detail="文件还没下载完成")
    task.addStatus = ""
    _queue_playlist_add(task, wait_seconds=900)
    _executor.submit(lambda: (_try_add_once(task), _save_tasks()))
    return {"ok": True}


# ------------------------------------------------------------------ 歌单

def _norm(s: str) -> str:
    return re.sub(r"[\s\-_·.,，。（）()\[\]【】!！?？'\"~～]+", "", (s or "").lower())


def _artist_match(a: str, b: str) -> bool:
    ta = {x for x in re.split(r"[、,，&;/＋+]+", re.sub(r"[（(].*?[)）]", "", a or "")) if x}
    tb = {x for x in re.split(r"[、,，&;/＋+]+", re.sub(r"[（(].*?[)）]", "", b or "")) if x}
    return bool(ta & tb)


@app.get("/api/playlists")
async def playlists(user: str = Depends(current_user)):
    """本地歌单 + 飞牛歌单（本地模式只有本地歌单）。"""
    return {"items": all_playlists(user)}


@app.post("/api/playlist/create")
async def playlist_create(payload: dict, user: str = Depends(current_user)):
    """新建歌单。syncToFnos=true 且已用飞牛账号登录 → 建到飞牛音乐；否则建本地歌单。"""
    name = (payload.get("name") or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="请填写歌单名")
    if len(name) > 60:
        raise HTTPException(status_code=400, detail="歌单名太长了（最多 60 个字）")

    local_mode = is_local(user)
    sync = bool(payload.get("syncToFnos", True)) and not local_mode

    if sync:
        token = fnos.token_for(user)
        if not token:
            raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌（请在飞牛音乐里登录一次）")
        try:
            created = fnos.create_playlist(token, name)
        except FnosMusicError as exc:
            raise HTTPException(status_code=502, detail=str(exc)) from exc
        created = {**(created or {}), "source": "fnos"}
    else:
        items = _load_local_playlists()
        created = {"guid": "local-" + uuid.uuid4().hex[:16], "name": name, "source": "local"}
        items.append({**created, "created": time.time(), "tracks": []})
        _save_local_playlists(items)

    return {
        "ok": True,
        "created": created,
        "items": all_playlists(user),
        "synced": bool(sync),
    }


@app.post("/api/playlist/rename")
async def playlist_rename(payload: dict, user: str = Depends(current_user)):
    """歌单改名。本地歌单改本地记录；飞牛歌单直接改飞牛音乐里的名字。"""
    guid = (payload.get("guid") or "").strip()
    name = (payload.get("name") or "").strip()
    if not guid:
        raise HTTPException(status_code=400, detail="缺少歌单")
    if not name:
        raise HTTPException(status_code=400, detail="请填写新的歌单名")
    if len(name) > 60:
        raise HTTPException(status_code=400, detail="歌单名太长了（最多 60 个字）")
    if any(c in name for c in "\n\r\t"):
        raise HTTPException(status_code=400, detail="歌单名里不能有换行")

    if local_playlist(guid) is not None:      # 本地歌单
        items = _load_local_playlists()
        for p in items:
            if p.get("guid") == guid:
                p["name"] = name
        _save_local_playlists(items)
        return {"ok": True, "items": all_playlists(user), "local": True}

    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式只能修改本地歌单")

    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌（请在飞牛音乐里登录一次）")
    try:
        fnos.rename_playlist(token, guid, name)
        items = all_playlists(user)
    except FnosMusicError as exc:
        raise HTTPException(status_code=502, detail=f"改名失败：{exc}") from exc
    return {"ok": True, "items": items, "local": False}


@app.get("/api/playlist/tracks")
async def playlist_tracks(guid: str, user: str = Depends(current_user)):
    """歌单里的曲目。本地歌单 → 直接可播放；飞牛歌单 → 请在飞牛音乐里播放。"""
    lp = local_playlist(guid)
    if lp is not None:
        tracks = lp.get("tracks") or []
        return {"local": True, "name": lp.get("name"), "items": tracks, "total": len(tracks)}
    return {
        "local": False,
        "name": "",
        "items": [],
        "total": 0,
        "note": "这是飞牛音乐的歌单，请在飞牛音乐里播放",
    }


@app.post("/api/playlist/remove-track")
async def playlist_remove_track(payload: dict, user: str = Depends(current_user)):
    """从本地歌单里移除一首歌。"""
    guid = (payload.get("guid") or "").strip()
    index = payload.get("index")
    if local_playlist(guid) is None:
        raise HTTPException(status_code=400, detail="只能管理本地歌单")
    if index is None:
        raise HTTPException(status_code=400, detail="缺少曲目序号")
    index = int(index)
    items = _load_local_playlists()
    for p in items:
        if p.get("guid") == guid:
            tracks = p.get("tracks") or []
            if 0 <= index < len(tracks):
                tracks.pop(index)
                p["tracks"] = tracks
    _save_local_playlists(items)
    return {"ok": True, "items": all_playlists(user)}


@app.post("/api/playlist/delete")
async def playlist_delete(payload: dict, user: str = Depends(current_user)):
    """删除歌单（本地歌单删记录；飞牛歌单调飞牛接口删除）。"""
    guid = (payload.get("guid") or "").strip()
    if not guid:
        raise HTTPException(status_code=400, detail="缺少歌单")

    if local_playlist(guid) is not None:
        items = [p for p in _load_local_playlists() if p.get("guid") != guid]
        _save_local_playlists(items)
        # 顺手清掉把它当默认歌单的设置
        s = _load_settings()
        for u, v in list(s.items()):
            if (v or {}).get("defaultPlaylist", {}).get("guid") == guid:
                s[u] = {"defaultPlaylist": {}}
        _save_settings(s)
        return {"ok": True, "items": all_playlists(user), "local": True}

    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式只能删除本地歌单")

    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌")
    try:
        fnos.delete_playlist(token, guid)
        items = all_playlists(user)
    except FnosMusicError as exc:
        raise HTTPException(status_code=502, detail=f"删除歌单失败：{exc}") from exc
    return {"ok": True, "items": items, "local": False}


def _match_and_add_to_fnos(token: str, playlist_guid: str, items: list[dict]) -> tuple[list, list, list, list]:
    """把 items 在飞牛曲库里匹配后加入指定飞牛歌单。

    返回 (added, missing, errors, missing_tracks)。
    """
    added: list[str] = []
    missing: list[str] = []
    errors: list[str] = []
    missing_tracks: list[dict] = []
    guids: list[str] = []
    for raw in items:
        title = (raw.get("title") or "").strip()
        artist = (raw.get("artist") or "").strip()
        if not title:
            continue
        try:
            found = fnos.search_track(token, title, size=20)
        except FnosMusicError as exc:
            errors.append(f"{title}：{exc}")
            continue

        # 用打分匹配挑最像的一条（歌名归一 + 歌手分词 + 时长）
        best, best_score = None, 0
        for f in found:
            cand = providers.Track(
                provider="fnos", id=f["guid"], source="fnos",
                title=f["title"], artist=f["artist"], duration=f["duration"] or 0,
            )
            sc = providers.match_track(cand, title, artist, int(raw.get("duration") or 0))
            if sc > best_score:
                best, best_score = f, sc
        if best is None or best_score < 3:
            missing.append(f"{title} - {artist}")
            item = dict(raw)
            item.setdefault("provider", "")
            missing_tracks.append(item)
            continue
        guids.append(best["guid"])
        added.append(f"{title} - {artist}")

    if guids:
        try:
            fnos.add_tracks(token, playlist_guid, guids)
        except FnosMusicError as exc:
            raise HTTPException(status_code=502, detail=f"加入歌单失败：{exc}") from exc
    return added, missing, errors, missing_tracks


@app.post("/api/playlist/import-to-fnos")
async def playlist_import_to_fnos(payload: dict, user: str = Depends(current_user)):
    """把本地歌单导入成飞牛音乐歌单（曲库里没有的歌返回 missingTracks，由前端触发下载）。"""
    local_guid = (payload.get("localGuid") or "").strip()
    target_guid = (payload.get("targetGuid") or "").strip()
    new_name = (payload.get("newName") or "").strip()

    lp = local_playlist(local_guid)
    if lp is None:
        raise HTTPException(status_code=400, detail="这不是本地歌单")
    if is_local(user):
        raise HTTPException(status_code=400, detail="先登录飞牛音乐账号，再导入")
    tracks = lp.get("tracks") or []
    if not tracks:
        raise HTTPException(status_code=400, detail="这个本地歌单还是空的")

    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌（请在飞牛音乐里登录一次）")

    if target_guid:
        tgt = target_guid
    else:
        if not new_name:
            raise HTTPException(status_code=400, detail="请选择要导入到哪个飞牛歌单（或填一个新歌单名）")
        if len(new_name) > 60:
            raise HTTPException(status_code=400, detail="歌单名太长了（最多 60 个字）")
        try:
            created = fnos.create_playlist(token, new_name)
        except FnosMusicError as exc:
            raise HTTPException(status_code=502, detail=f"新建歌单失败：{exc}") from exc
        tgt = (created or {}).get("guid")
        if not tgt:
            raise HTTPException(status_code=502, detail="新建歌单失败：没拿到歌单 id")

    try:
        added, missing, errors, missing_tracks = _match_and_add_to_fnos(token, tgt, tracks)
    except HTTPException:
        raise
    except FnosMusicError as exc:
        raise HTTPException(status_code=502, detail=f"导入失败：{exc}") from exc

    return {
        "ok": True,
        "targetGuid": tgt,
        "added": added,
        "missing": missing,
        "missingTracks": missing_tracks,
        "errors": errors,
        "items": all_playlists(user),
    }


@app.post("/api/playlist/add")
async def playlist_add(payload: dict, user: str = Depends(current_user)):
    guid = payload.get("playlistGuid")
    items = payload.get("items") or []
    if not guid or not items:
        raise HTTPException(status_code=400, detail="缺少歌单或曲目")

    # 本地歌单：只存曲目（不下载、不进飞牛音乐），之后点了就能在线播放
    if local_playlist(guid) is not None:
        playlists = _load_local_playlists()
        added: list[str] = []
        for p in playlists:
            if p.get("guid") != guid:
                continue
            tracks = p.setdefault("tracks", [])
            have = {(t.get("title"), t.get("artist")) for t in tracks}
            for raw in items:
                title = (raw.get("title") or "").strip()
                artist = (raw.get("artist") or "").strip()
                if not title or (title, artist) in have:
                    continue
                tracks.append(
                    {
                        "title": title,
                        "artist": artist,
                        "duration": int(raw.get("duration") or 0),
                        "provider": raw.get("provider") or "",
                        "id": raw.get("id") or "",
                        "source": raw.get("source") or "",
                        "token": raw.get("token") or "",
                        "addedAt": time.time(),
                    }
                )
                have.add((title, artist))
                added.append(f"{title} - {artist}")
        _save_local_playlists(playlists)
        return {
            "ok": True,
            "added": added,
            "missing": [],
            "missingTracks": [],
            "errors": [],
            "local": True,
        }

    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式只能把歌加到本地歌单")

    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌")

    added, missing, errors, missing_tracks = _match_and_add_to_fnos(token, guid, items)

    return {
        "ok": True,
        "added": added,
        "missing": missing,
        "missingTracks": missing_tracks,
        "errors": errors,
    }


# ------------------------------------------------------------------ 静态页

@app.get("/")
async def index():
    # 页面是单文件（HTML+内联JS）→ 必须禁用缓存，否则手机/WebView 会一直跑旧版
    return FileResponse(
        STATIC_DIR / "index.html",
        headers={
            "Cache-Control": "no-cache, no-store, must-revalidate",
            "Pragma": "no-cache",
            "Expires": "0",
        },
    )


@app.get("/favicon.ico")
async def favicon():
    f = STATIC_DIR / "favicon.ico"
    if not f.exists():
        raise HTTPException(status_code=404, detail="no favicon")
    return FileResponse(
        f,
        media_type="image/x-icon",
        headers={"Cache-Control": "public, max-age=600"},
    )


@app.get("/manifest.webmanifest")
async def manifest():
    f = STATIC_DIR / "manifest.webmanifest"
    if not f.exists():
        raise HTTPException(status_code=404, detail="no manifest")
    return FileResponse(f, media_type="application/manifest+json")


@app.get("/healthz")
async def healthz():
    return {"app": "music-add", "ok": True, "library": str(MUSIC_DIR), "libraryExists": MUSIC_DIR.exists()}
