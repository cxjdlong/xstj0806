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
    revived: bool = False  # 是否已经自动补单过一次（避免反复复活）


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


def _is_transient_add_error(exc: Exception) -> bool:
    """飞牛音乐暂时不可用（socket 失效、容器挂载过期…）而不是这条歌有问题。

    这类错误必须留在队列里等恢复，判成 failed 就再也不会自动重试了 ——
    用户看到的现象就是「下载的歌在飞牛音乐里能看到，歌单里却没有」。
    """
    s = str(exc)
    return any(k in s for k in ("不可达", "Connection refused", "No such file", "Connection reset",
                                "timed out", "timeout"))


def _match_candidate(cand: dict, title: str, artist: str, exact_only: bool = False) -> bool:
    """飞牛曲库条目 vs 我们下载的曲目，判断是不是同一首。

    难点：飞牛经常把「歌名 - 歌手」整串当 title、artist 字段留空（例如
    title='下沙 - 游鸿明'、artists=[]），这时只比歌手字段永远匹配不上，
    歌就永远加不进歌单。所以歌手字段缺失时，退一步看歌手名在不在标题串里。
    """
    nt, nf = _norm(title), _norm(cand.get("title") or "")
    na, naf = _norm(artist), _norm(cand.get("artist") or "")
    if not nt:
        return False
    exact = nt == nf
    if exact_only and not exact:
        return False
    if not (exact or nt in nf or nf in nt):
        return False
    if na and naf:
        # 两边歌手字段都有 → 必须能对上（_artist_match 对空串恒为 False，别拿它做入口判断）
        return _artist_match(cand.get("artist") or "", artist)
    if exact:
        return True                   # 标题完全一致，另一边歌手字段缺失就认了
    # 有一边歌手字段为空：要求歌手名确实出现在标题串里，避免张冠李戴
    return bool((na and na in nf) or (naf and (naf in nt or naf in nf)))


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
        if _is_transient_add_error(exc):
            # 飞牛音乐 socket 暂时不可用（升级/重启后容器挂载失效等）：
            # 不能定性失败，留在队列里等它恢复，否则歌永远进不了歌单。
            with TASKS_LOCK:
                task.addNote = "等飞牛音乐就绪后自动重试"
            return False
        with TASKS_LOCK:
            task.addStatus, task.addNote = "failed", str(exc)
        return True

    pick = None
    for exact_only in (True, False):     # 先认标题完全一致的，再退到互相包含
        for f in found:
            if _match_candidate(f, title, artist, exact_only):
                pick = f
                break
        if pick:
            break
    if not pick:
        return False  # 还没入库，等下一轮

    try:
        fnos.add_tracks(token, guid, [pick["guid"]])
        with TASKS_LOCK:
            task.addStatus = "added"
            task.addNote = task.playlistName or ""
    except FnosMusicError as exc:
        if _is_transient_add_error(exc):
            with TASKS_LOCK:
                task.addNote = "等飞牛音乐就绪后自动重试"
            return False
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


NEXT_TRY: dict[str, float] = {}


def _revive_transient_failed() -> int:
    """把「因飞牛音乐不可达 / 曲库一直没扫到而失败」的入单任务重新排队。

    容器重建、socket 修好、或匹配规则修好后自动补单，不用用户一个个点「重试入单」。
    每条任务最多自动复活一次（revived 标志），避免反复复活死循环。
    """
    with TASKS_LOCK:
        cand = [
            t for t in TASKS.values()
            if t.addStatus == "failed" and t.playlistGuid and t.status in ("done", "skipped")
            and not t.revived
            and (_is_transient_add_error(Exception(t.addNote or "")) or "还没扫到" in (t.addNote or ""))
        ]
    for t in cand:
        with TASKS_LOCK:
            t.addStatus, t.addNote, t.revived = "waiting", "", True
        with ADDS_LOCK:
            PENDING_ADDS[t.id] = time.time() + ADD_WAIT_SECONDS
    if cand:
        _save_tasks()
    return len(cand)


def _adds_worker() -> None:
    """后台入单线程。

    退避节奏：刚下完的前 90 秒每 3 秒试一次（多数情况此时飞牛已入库，几秒内入单），
    90 秒~5 分钟每 10 秒，之后每 30 秒，最长等 ADD_WAIT_SECONDS 才标失败。
    """
    revived = False
    while True:
        try:
            if not revived:
                revived = True
                n = _revive_transient_failed()
                if n:
                    print(f"[adds] 自动重试 {n} 个此前因飞牛音乐不可达而失败的入单任务", flush=True)
            with TASKS_LOCK:
                pending = [t for t in TASKS.values() if t.addStatus == "waiting"]
            now = time.time()
            for task in pending:
                if now < NEXT_TRY.get(task.id, 0.0):
                    continue
                if _try_add_once(task):
                    with ADDS_LOCK:
                        PENDING_ADDS.pop(task.id, None)
                    NEXT_TRY.pop(task.id, None)
                    _save_tasks()
                    continue
                with ADDS_LOCK:
                    deadline = PENDING_ADDS.get(task.id)
                    if deadline is None:
                        # 容器重启后 PENDING_ADDS 是空的，历史 waiting 任务要补一个 deadline，
                        # 否则下面 now >= deadline 会立刻被判成失败。
                        deadline = now + ADD_WAIT_SECONDS
                        PENDING_ADDS[task.id] = deadline
                if now >= deadline:
                    with TASKS_LOCK:
                        task.addStatus = "failed"
                        task.addNote = "曲库还没扫到，可点「重试入单」"
                    with ADDS_LOCK:
                        PENDING_ADDS.pop(task.id, None)
                    NEXT_TRY.pop(task.id, None)
                    _save_tasks()
                    continue
                waited = ADD_WAIT_SECONDS - (deadline - now)
                delay = 3 if waited < 90 else (10 if waited < 300 else 30)
                NEXT_TRY[task.id] = now + delay
        except Exception:  # noqa: BLE001
            pass
        time.sleep(2)


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


@app.get("/api/resolve")
async def api_resolve(
    title: str,
    artist: str = "",
    duration: int = 0,
    provider: str = "music-dl",
    user: str = Depends(current_user),
):
    """按「歌名+歌手+时长」在音源里挑**最佳匹配**的那首（供飞牛歌单整单播放逐首找源）。

    与 /api/search 直接取第一条不同：这里复用 providers.match_track 打分，
    避免「我的歌声里」被匹配成「承认」这类张冠李戴。
    duration 单位＝秒（飞牛歌单接口已 //1000）。
    """
    want = (title or "").strip()
    if not want:
        raise HTTPException(status_code=400, detail="缺少歌名")
    try:
        p = providers.get(provider)
    except KeyError:
        raise HTTPException(status_code=400, detail=f"未知音源：{provider}")

    keyword = (want + " " + (artist or "")).strip()
    try:
        cands = await p.search(keyword)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"搜索失败：{exc}") from exc

    cands = providers.dedupe(cands)
    scored: list[tuple[int, object]] = []
    for c in cands:
        sc = providers.match_track(c, want, artist or "", int(duration or 0))
        if sc >= 3:
            scored.append((sc, c))
    if not scored:
        return {"found": False, "item": None, "tried": len(cands)}
    scored.sort(
        key=lambda x: (
            -x[0],
            providers.SOURCE_ORDER.index(x[1].source) if x[1].source in providers.SOURCE_ORDER else 99,
        )
    )
    best = scored[0][1]
    d = best.to_dict()
    d["token"] = best.token()
    d["variant"] = best.is_variant
    d["score"] = scored[0][0]
    return {"found": True, "item": d}


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


# ---------------- 首页聚合：每日推荐 / 新歌榜 / 当月热门 / 推荐歌单 ----------------
_HOME_CACHE: dict[str, tuple[float, dict]] = {}
_HOME_TTL = 1800          # 30 分钟；跨月后 key 变了自然重取


def _pick_playlist(items: list[dict], keyword: str) -> dict | None:
    """从歌单搜索结果里挑最像“官方榜单”的那个（名字贴近 + 创作者像官号 + 曲目够多）。"""
    best, best_score = None, float("-inf")
    kw = (keyword or "").replace(" ", "")
    for it in items or []:
        name = (it.get("name") or "").strip()
        if not name:
            continue
        flat = name.replace(" ", "")
        score = 0.0
        if flat == kw:
            score += 100
        elif kw and kw in flat:
            score += 60
        else:
            hit = sum(1 for ch in kw if ch in flat)
            if not kw or hit < len(kw) * 0.6:
                continue
            score += hit * 8
        creator = it.get("creator") or ""
        if any(k in creator for k in ("官方", "网易云", "QQ音乐", "酷狗", "酷我", "音乐", "精选", "歌单")):
            score += 25
        n = int(it.get("trackCount") or 0)
        score += min(n, 300) * 0.15      # 曲目多更像榜单，但别让超大歌单压过名字匹配
        if n < 10:
            score -= 30
        if score > best_score:
            best, best_score = it, score
    return best


@app.get("/api/home")
async def api_home(source: str = "", provider: str = "music-dl", user: str = Depends(current_user)):
    """首页：每日推荐 + 新歌榜 + {当月}月热门 + 推荐歌单网格（内存缓存 30 分钟）。"""
    p = providers.get(provider)
    if not hasattr(p, "recommend_playlists"):
        raise HTTPException(status_code=400, detail=f"音源 {provider} 暂不支持首页推荐")

    month = int(time.strftime("%m"))
    now = time.time()
    key = f"{provider}|{source}|{month}"
    hit = _HOME_CACHE.get(key)
    if hit and now - hit[0] < _HOME_TTL:
        return hit[1]

    srcs = [source] if source else list(getattr(p, "RECOMMEND_SOURCES", []) or [])
    try:
        rec = await p.recommend_playlists(srcs)
    except Exception:  # noqa: BLE001
        rec = []

    async def _search(kw: str, use_all: bool = True) -> list[dict]:
        # 榜单类搜索始终跨 4 个推荐源：单个音源（比如网易云）里常常没有“新歌榜”这种歌单
        ss = list(getattr(p, "RECOMMEND_SOURCES", []) or []) if use_all else srcs
        try:
            return await p.playlist_search(kw, ss)
        except Exception:  # noqa: BLE001
            return []

    kw_new = "新歌榜"
    kw_month = f"{month}月热门"
    got_new, got_month = await asyncio.gather(_search(kw_new), _search(kw_month))
    if not got_new:
        got_new = await _search("新歌")
    if not got_month:
        got_month = await _search(f"{month}月")

    entries: list[dict] = []
    daily = _pick_playlist(rec, "每日推荐") or (rec[0] if rec else None)
    if daily:
        entries.append({"key": "daily", "title": "每日推荐",
                        "sub": daily.get("creator") or "官方推荐", "playlist": daily})
    hit_new = _pick_playlist(got_new, kw_new)
    if hit_new:
        entries.append({"key": "new", "title": "新歌榜",
                        "sub": f"{hit_new.get('trackCount') or 0} 首", "playlist": hit_new})
    hit_month = _pick_playlist(got_month, kw_month)
    if hit_month:
        entries.append({"key": "month", "title": f"{month}月热门",
                        "sub": f"{hit_month.get('trackCount') or 0} 首", "playlist": hit_month})

    # 推荐歌单网格：只用平台官方推荐（质量稳）；太少才拿「当月热门」搜索结果补位
    grid: list[dict] = []
    seen: set[str] = set()

    def _add(items: list[dict]) -> None:
        for it in items:
            k = f"{it.get('source')}:{it.get('id')}"
            if k in seen or not it.get("cover"):
                continue
            seen.add(k)
            grid.append(it)

    _add(rec)
    if len(grid) < 16:
        _add(got_month)

    data = {"month": month, "entries": entries, "playlists": grid, "cachedAt": int(now)}
    _HOME_CACHE[key] = (now, data)
    if len(_HOME_CACHE) > 32:          # 简单裁剪，别无限涨
        for k in sorted(_HOME_CACHE, key=lambda x: _HOME_CACHE[x][0])[:16]:
            _HOME_CACHE.pop(k, None)
    return data


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


@app.get("/api/library/list")
async def library_list(
    page: int = 1, size: int = 15, q: str = "", user: str = Depends(current_user)
):
    """曲库歌曲列表（只给歌名/歌手，不给磁盘路径），每页默认 15 条。"""
    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式没有曲库")
    if not MUSIC_DIR.is_dir():
        return {"items": [], "total": 0, "page": 1, "size": size, "pages": 0}
    kw = (q or "").strip().lower()
    rows: list[dict] = []
    for it in _scan_audio_files():
        title, artist = it["title"], it["artist"]
        if kw and kw not in title.lower() and kw not in (artist or "").lower():
            continue
        rows.append(it)
    size = max(1, min(int(size or 15), 100))
    page = max(1, int(page or 1))
    start = (page - 1) * size
    pages = (len(rows) + size - 1) // size if rows else 0
    return {
        "items": rows[start : start + size],
        "total": len(rows),
        "page": page,
        "size": size,
        "pages": pages,
    }


@app.post("/api/library/delete-file")
async def library_delete_file(payload: dict, user: str = Depends(current_user)):
    """按相对路径直接删除曲库里的一个文件（连同同名 .lrc）。"""
    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式没有曲库")
    key = (payload.get("key") or "").strip()
    if not key or ".." in key or key.startswith("/"):
        raise HTTPException(status_code=400, detail="非法路径")
    root = MUSIC_DIR.resolve()
    p = (root / key).resolve()
    if root not in p.parents:
        raise HTTPException(status_code=400, detail="非法路径")
    if not p.is_file() or p.suffix.lower() not in AUDIO_EXTS:
        raise HTTPException(status_code=404, detail="文件不存在")
    # 歌单保护：在别人歌单里 → 拒绝；在自己歌单里 → 要确认
    t_title, t_artist = _title_artist_of(key)
    _guard_delete(t_title, t_artist, user, bool(payload.get("confirm")))
    try:
        p.unlink()
    except OSError as exc:
        raise HTTPException(status_code=500, detail=f"删除失败：{exc}") from exc
    lrc = p.with_suffix(".lrc")
    if lrc.is_file():
        try:
            lrc.unlink()
        except OSError:
            pass
    try:
        if not any(p.parent.iterdir()):
            p.parent.rmdir()
    except OSError:
        pass
    return {"ok": True, "key": key}


@app.post("/api/library/scan")
async def library_scan(user: str = Depends(current_user)):
    """触发飞牛音乐扫库 + 重建索引（曲库里新下的歌搜不到时用）。"""
    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式没有飞牛账号")
    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌")
    return {"ok": True, "result": fnos.scan_library(token)}


@app.get("/api/playlist/tracks")
async def playlist_tracks(guid: str, user: str = Depends(current_user)):
    """歌单里的曲目。本地歌单 → 存的曲目；飞牛歌单 → 走飞牛接口列出来。"""
    lp = local_playlist(guid)
    if lp is not None:
        tracks = lp.get("tracks") or []
        return {"local": True, "name": lp.get("name"), "items": tracks, "total": len(tracks)}

    if is_local(user):
        return {"local": False, "name": "", "items": [], "total": 0, "note": "本地模式看不到飞牛歌单"}
    token = fnos.token_for(user)
    if not token:
        return {"local": False, "name": "", "items": [], "total": 0, "note": "该账号还没有飞牛音乐登录令牌"}
    try:
        items = fnos.playlist_tracks(token, guid)
    except FnosMusicError as exc:
        raise HTTPException(status_code=502, detail=f"取歌单曲目失败：{exc}") from exc
    name = ""
    for p in all_playlists(user):
        if p.get("guid") == guid:
            name = p.get("name") or ""
            break
    return {"local": False, "name": name, "items": items, "total": len(items)}


@app.post("/api/playlist/remove-track")
async def playlist_remove_track(payload: dict, user: str = Depends(current_user)):
    """从歌单里移除一首歌。本地歌单改本地记录；飞牛歌单调飞牛接口（真同步）。"""
    guid = (payload.get("guid") or "").strip()
    if not guid:
        raise HTTPException(status_code=400, detail="缺少歌单")

    # —— 本地歌单：按序号删本地记录 ——
    if local_playlist(guid) is not None:
        index = payload.get("index")
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
        return {"ok": True, "items": all_playlists(user), "local": True}

    # —— 飞牛歌单：按曲目 guid 调飞牛接口 ——
    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式只能管理本地歌单")
    track_guid = (payload.get("trackGuid") or "").strip()
    if not track_guid:
        raise HTTPException(status_code=400, detail="缺少曲目")
    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌")
    try:
        fnos.remove_tracks(token, guid, [track_guid])
    except FnosMusicError as exc:
        raise HTTPException(status_code=502, detail=f"从歌单移除失败：{exc}") from exc
    return {"ok": True, "items": all_playlists(user), "local": False}


def _scan_audio_files() -> list[dict]:
    """列出曲库里的音频文件，兼容两种存放布局：

    1) <歌手>/<歌名>.mp3          （历史入库的）
    2) <歌名> - <歌手>.mp3        （新下载直接放根目录的）
    返回 [{key, title, artist}, ...]，key 是相对曲库根的路径。
    """
    items: list[dict] = []
    if not MUSIC_DIR.is_dir():
        return items
    for p in sorted(MUSIC_DIR.rglob("*")):
        try:
            if not p.is_file() or p.suffix.lower() not in AUDIO_EXTS:
                continue
            rel = p.relative_to(MUSIC_DIR)
        except OSError:
            continue
        parts = rel.parts
        if len(parts) >= 2:
            title, artist = p.stem, p.parent.name
        else:
            stem = p.stem
            if " - " in stem:
                t, a = stem.rsplit(" - ", 1)
                title, artist = t.strip(), a.strip()
            else:
                title, artist = stem, ""
        items.append({"key": str(rel), "title": title, "artist": artist})
    return items


def _title_artist_of(key: str) -> tuple[str, str]:
    """由曲库相对路径推出 (歌名, 歌手)，兼容两种布局。"""
    p = MUSIC_DIR / key
    parts = Path(key).parts
    if len(parts) >= 2:
        return p.stem, p.parent.name
    stem = p.stem
    if " - " in stem:
        t, a = stem.rsplit(" - ", 1)
        return t.strip(), a.strip()
    return stem, ""


def _who_has_track(title: str, artist: str = "") -> dict[str, list[str]]:
    """查这首歌出现在哪些账号的哪些歌单里。

    直接查飞牛的音乐库（playlist_track × playlist × track × user），毫秒级，
    而且查的是当前真实状态 —— 歌单里加了歌/删了歌，下一次检查立刻反映，
    不存在缓存过期问题。查询失败时抛错（宁可拒绝删除，也不冒险误删）。
    """
    if not title:
        return {}
    want = title.strip()
    try:
        rows = fnos._db_query(
            "SELECT p.name, u.name FROM playlist_track pt "
            "JOIN playlist p ON p.id = pt.playlist_id "
            "JOIN track t ON t.id = pt.track_id "
            "LEFT JOIN user u ON u.id = pt.user_id "
            "WHERE t.title = ?",
            (want,),
        )
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=503,
            detail=f"无法检查这首歌是否被歌单占用（{exc}），为保证数据安全已拒绝删除",
        ) from exc
    out: dict[str, list[str]] = {}
    for pname, uname in rows:
        key = (uname or "").strip() or "未知账号"
        out.setdefault(key, []).append((pname or "").strip() or "(未命名)")
    return out


def _guard_delete(title: str, artist: str, user: str, confirmed: bool) -> None:
    """删除前的歌单保护检查。"""
    owner_map = _who_has_track(title, artist)
    others = {u: pls for u, pls in owner_map.items() if u != user}
    if others:
        detail = "；".join(f"{u} 的歌单「{'、'.join(pls)}」" for u, pls in others.items())
        raise HTTPException(
            status_code=403,
            detail=f"这首歌还在别人的歌单里，不允许删除：{detail}",
        )
    mine = owner_map.get(user)
    if mine and not confirmed:
        raise HTTPException(
            status_code=409,
            detail=f"这首歌在你的歌单「{'、'.join(mine)}」里，确认要删除吗？",
        )


AUDIO_EXTS = {".mp3", ".flac", ".m4a", ".wav", ".ape", ".ogg", ".aac", ".wma"}


def _delete_library_files(title: str, artist: str) -> list[str]:
    """删除曲库磁盘上对应的音频文件（连同同名 .lrc 歌词），返回删掉的文件名。"""
    if not title or not MUSIC_DIR.is_dir():
        return []
    artists = [a.strip() for a in re.split(r"[、,/&;]", artist or "") if a.strip()]
    targets: list[Path] = []
    # 1) 优先按「歌手/歌名」精确定位（艺术家可能是多人，逐个试）
    for a in artists:
        for ext in AUDIO_EXTS:
            p = MUSIC_DIR / a / f"{title}{ext}"
            if p.is_file():
                targets.append(p)
    # 2) 没命中就在二级目录里按歌名兜底找
    if not targets:
        for p in MUSIC_DIR.glob(f"*/{title}.*"):
            if p.is_file() and p.suffix.lower() in AUDIO_EXTS:
                targets.append(p)
    removed: list[str] = []
    for p in targets:
        try:
            p.unlink()
        except OSError:
            continue
        removed.append(str(p.relative_to(MUSIC_DIR)))
        lrc = p.with_suffix(".lrc")
        if lrc.is_file():
            try:
                lrc.unlink()
            except OSError:
                pass
    return removed


@app.post("/api/track/delete")
async def track_delete(payload: dict, user: str = Depends(current_user)):
    """彻底删除一首歌：飞牛音乐库记录 + 曲库磁盘文件（真删，不可恢复）。"""
    if is_local(user):
        raise HTTPException(status_code=400, detail="本地模式没有飞牛曲库")
    guid = (payload.get("guid") or "").strip()
    title = (payload.get("title") or "").strip()
    artist = (payload.get("artist") or "").strip()
    if not guid and not (title and artist):
        raise HTTPException(status_code=400, detail="缺少曲目信息")
    token = fnos.token_for(user)
    if not token:
        raise HTTPException(status_code=503, detail="该账号还没有飞牛音乐登录令牌")

    # 歌单保护：在别人歌单里 → 拒绝；在自己歌单里 → 要确认
    _guard_delete(title, artist, user, bool(payload.get("confirm")))

    # 1) 飞牛音乐库记录：飞牛没有开放「删曲目」的 API（试遍路径都是 502），
    #    删掉磁盘文件后，飞牛下次扫库会自动把这条清掉，效果一致。
    db_ok, db_err = False, ""
    if guid:
        try:
            fnos.delete_tracks(token, [guid])
            db_ok = True
        except FnosMusicError:
            db_err = "飞牛未开放删曲目接口，已改由删文件同步（下次扫库自动清理）"

    # 2) 曲库磁盘文件删掉（真正生效的一步）
    removed = _delete_library_files(title, artist)
    if not removed and not db_ok:
        raise HTTPException(status_code=502, detail="没找到这首歌的文件（可能已经被删掉了）")
    return {
        "ok": True,
        "db": db_ok,
        "dbError": db_err,
        "files": removed,
        "note": "文件已删除，飞牛音乐下次扫库后会自动消失" if not db_ok else "已同步删除",
    }


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


@app.get("/preview")
async def preview_page():
    """新版首页效果预览页（给用户手机上看的静态稿，不需要登录）"""
    f = STATIC_DIR / "preview.html"
    if not f.exists():
        raise HTTPException(status_code=404, detail="no preview")
    return FileResponse(
        f,
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
