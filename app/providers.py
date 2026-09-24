"""可插拔音乐源适配层。

统一结构 Track；每个源实现 `search()` / `stream_url()` / `fetch_bytes()`。
当前实现：music-dl（go-music-dl，宿主 19010）。
后期加「落雪音乐」只需新增一个同接口的类并在 PROVIDERS 里注册，前端无需改动。
"""

from __future__ import annotations

import base64
import html
import json
import re
from dataclasses import dataclass, field, asdict
from typing import Iterable
from urllib.parse import parse_qsl, urlencode

import httpx

# 变体/非原唱关键词：排序时压后，避免默认选中 DJ/伴奏/翻唱版
VARIANT_RE = re.compile(
    r"伴奏|伴唱|纯音乐|instrumental|DJ|慢摇|变速|环绕|Live|现场|演唱会|音乐会|片段|"
    r"改编|remix|合唱版|女声版|翻唱|cover|抖音|降调|降速|KTV|消音|钢琴版|吉他版|"
    r"弹唱|清唱|剧场版|试听|加长|重置版|重制版|remaster|原唱标注|TV版|自白版|独唱",
    re.I,
)

# 源优先级（酷狗取流常失败，排最后）
SOURCE_ORDER = ["netease", "qq", "kuwo", "migu", "kugou", "bilibili"]
DEFAULT_SOURCES = ["netease", "qq", "kuwo", "migu"]


@dataclass
class Track:
    provider: str
    id: str
    source: str
    title: str
    artist: str = ""
    album: str = ""
    cover: str = ""
    duration: int = 0
    extra: str = ""

    @property
    def key(self) -> str:
        return f"{self.provider}:{self.source}:{self.id}"

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict) -> "Track":
        return Track(
            provider=d.get("provider", ""),
            id=str(d.get("id", "")),
            source=d.get("source", ""),
            title=d.get("title", ""),
            artist=d.get("artist", ""),
            album=d.get("album", ""),
            cover=d.get("cover", ""),
            duration=int(d.get("duration") or 0),
            extra=d.get("extra", ""),
        )

    def token(self) -> str:
        """把整条曲目压成一个 URL-safe token，前端只传这个。"""
        raw = json.dumps(self.to_dict(), ensure_ascii=False).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    @staticmethod
    def from_token(tok: str) -> "Track":
        pad = "=" * (-len(tok) % 4)
        raw = base64.urlsafe_b64decode(tok + pad)
        return Track.from_dict(json.loads(raw))

    @property
    def is_variant(self) -> bool:
        return bool(VARIANT_RE.search(f"{self.title} {self.artist}"))

    @property
    def filename(self) -> str:
        """曲库命名规范：`歌名 - 歌手.mp3`（与 /vol2/1000/video/music 现有条目一致）。"""
        title = sanitize(self.title) or "未知歌曲"
        artist = sanitize(self.artist)
        base = f"{title} - {artist}" if artist else title
        return base[:150]


def sanitize(name: str) -> str:
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', " ", name or "")
    name = re.sub(r"\s+", " ", name).strip().strip(".")
    return name


def parse_song_cards(page_html: str) -> list[dict]:
    """解析 go-music-dl 搜索页里的 <li class="song-card" ...>。"""
    cards = re.findall(r'<li class="song-card"([^>]*)>', page_html)
    out: list[dict] = []
    for attrs in cards:
        d = dict(re.findall(r'data-([a-z-]+)="([^"]*)"', attrs))
        if not d.get("id"):
            continue
        out.append({k: html.unescape(v) for k, v in d.items()})
    return out


def parse_playlist_cards(page: str) -> list[dict]:
    """解析歌单卡片（category_playlists / user_playlists 页共用）。同 id 去重。"""
    seen: set[str] = set()
    out: list[dict] = []
    for raw in re.findall(r"navigateTo\('([^']*playlist\?[^']*)'\)", page):
        q = raw.replace("\\u0026", "&").replace("\\/", "/")
        q = q.split("?", 1)[1] if "?" in q else q
        d = dict(parse_qsl(q))
        pid = d.get("id")
        if not pid:
            continue
        key = f"{d.get('source','')}:{pid}"
        if key in seen:
            continue
        seen.add(key)
        out.append(
            {
                "id": pid,
                "source": d.get("source", ""),
                "name": d.get("name", ""),
                "cover": d.get("cover", ""),
                "creator": d.get("creator", ""),
                "description": d.get("description", ""),
                "trackCount": int(re.sub(r"\D", "", d.get("track_count", "") or "0") or 0),
                "link": d.get("link", ""),
            }
        )
    return out


def parse_categories(page: str) -> dict:
    """解析 /playlist_categories 页面 → {sources:[{id,name}], groups:{source: [{title, items}]}}"""
    sources = []
    for m in re.finditer(
        r'<button[^>]*class="category-source-tab[^"]*"[^>]*data-target="category-panel-([a-z]+)"[^>]*>\s*'
        r'<span class="category-source-tab-name">([^<]*)</span>',
        page,
    ):
        sources.append({"id": m.group(1), "name": m.group(2)})

    parts = re.split(r'<div class="category-source-panel[^"]*" id="category-panel-([a-z]+)"', page)
    groups: dict[str, list] = {}
    for i in range(1, len(parts), 2):
        src, content = parts[i], parts[i + 1]
        gs = []
        for gm in re.finditer(
            r'<div class="category-group">\s*<div class="category-group-title"><span>([^<]*)</span></div>\s*'
            r'<div class="category-chip-list">(.*?)</div>\s*</div>',
            content,
            re.S,
        ):
            title, chips_html = gm.group(1), gm.group(2)
            items = []
            for cm in re.finditer(
                r'<a class="category-chip[^"]*" href="([^"]+)"[^>]*>\s*'
                r'<span class="category-chip-name">([^<]*)</span>',
                chips_html,
            ):
                href = html.unescape(cm.group(1))
                q = dict(parse_qsl(href.split("?", 1)[1])) if "?" in href else {}
                items.append(
                    {
                        "name": cm.group(2),
                        "id": q.get("category_id") or q.get("category_name", ""),
                        "source": q.get("source", src),
                    }
                )
            if items:
                gs.append({"title": title, "items": items})
        groups[src] = gs
    return {"sources": sources, "groups": groups}


def norm_song(s: str) -> str:
    """歌名归一：去空格/标点/括号内容（仅用于宽筛，定选用 strict_norm）。"""
    s = re.sub(r"[（(【\[].*?[)）】\]]", "", s or "")
    return re.sub(r"[\s\-_·.,，。!！?？'\"~～]+", "", s.lower())


def strict_norm(s: str) -> str:
    """严格归一：保留括号内容（区分 伴奏/Live/DJ 版）。"""
    return re.sub(r"[\s\-_·.,，。!！?？'\"~～]+", "", (s or "").lower())


def artist_tokens(s: str) -> set[str]:
    """歌手分词（去括号、按分隔符切），用于模糊歌手匹配。"""
    s = re.sub(r"[（(].*?[)）]", "", s or "")
    return {t for t in re.split(r"[、,，&;/＋+]+", s) if t.strip()}


def match_track(cand: Track, title: str, artist: str = "", duration: int = 0) -> int:
    """候选与目标歌曲的匹配分（0 = 不匹配）。越大约好。"""
    nt, nc = norm_song(title), norm_song(cand.title)
    if not nt or not nc:
        return 0
    if nt == nc:
        score = 3
    elif len(nt) >= 4 and (nt in nc or nc in nt):
        score = 2
    else:
        return 0
    if artist:
        if artist_tokens(artist) & artist_tokens(cand.artist):
            score += 2
        else:
            score -= 2  # 同名不同歌手：多半是翻唱，压低但不直接排除
    if duration and cand.duration:
        if abs(duration - cand.duration) <= 20:
            score += 1
        else:
            score -= 1
    if cand.is_variant:
        score -= 1
    return score


class BaseProvider:
    name = "base"
    label = "未命名源"

    async def search(self, keyword: str, sources: Iterable[str] | None = None) -> list[Track]:
        raise NotImplementedError

    async def fetch_bytes(self, track: Track, chunk: int = 64 * 1024):
        raise NotImplementedError

    async def stream_response(self, track: Track, range_header: str | None = None):
        raise NotImplementedError


class MusicDlProvider(BaseProvider):
    """go-music-dl（宿主 19010）。"""

    name = "music-dl"
    label = "music-dl"

    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")

    # ---- 搜索 ----
    async def search(self, keyword: str, sources: Iterable[str] | None = None) -> list[Track]:
        params: list[tuple[str, str]] = [("q", keyword), ("type", "song")]
        for s in sources or DEFAULT_SOURCES:
            params.append(("sources", s))  # 必须重复传，逗号分隔会被当成一个源名
        url = f"{self.base}/search?{urlencode(params)}"
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as c:
            r = await c.get(url, headers={"User-Agent": "music-add/1.0"})
            r.raise_for_status()
        tracks = [
            Track(
                provider=self.name,
                id=c.get("id", ""),
                source=c.get("source", ""),
                title=c.get("name", ""),
                artist=c.get("artist", ""),
                album=c.get("album", ""),
                cover=c.get("cover", ""),
                duration=int(re.sub(r"\D", "", c.get("duration", "") or "0") or 0),
                extra=c.get("extra", ""),
            )
            for c in parse_song_cards(r.text)
        ]
        return sort_tracks(tracks)

    # ---- 歌单分类 / 歌单浏览 ----
    async def categories(self) -> dict:
        """歌单分类：{sources:[{id,name}], groups:{source:[{title,items}]}}"""
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as c:
            r = await c.get(f"{self.base}/playlist_categories", headers={"User-Agent": "music-add/1.0"})
            r.raise_for_status()
        return parse_categories(r.text)

    async def category_playlists(
        self, source: str, category_id: str = "", category_name: str = "", page: int = 1
    ) -> list[dict]:
        """某分类下的歌单列表。"""
        params = {
            "source": source,
            "page": page,
            "category_id": category_id or category_name,
            "category_name": category_name or category_id,
        }
        async with httpx.AsyncClient(timeout=40, follow_redirects=True) as c:
            r = await c.get(
                f"{self.base}/category_playlists?{urlencode(params)}",
                headers={"User-Agent": "music-add/1.0"},
            )
            r.raise_for_status()
        return parse_playlist_cards(r.text)

    async def playlist_tracks(
        self, source: str, playlist_id: str, page: int = 1, name: str = "", cover: str = ""
    ) -> tuple[list[Track], int]:
        """歌单内曲目（每页 100 首）。返回 (tracks, total)。"""
        params = {"id": playlist_id, "source": source, "page": page, "name": name, "cover": cover}
        async with httpx.AsyncClient(timeout=40, follow_redirects=True) as c:
            r = await c.get(
                f"{self.base}/playlist?{urlencode(params)}", headers={"User-Agent": "music-add/1.0"}
            )
            r.raise_for_status()
        page_html = r.text
        total = 0
        m = re.search(r"显示\s*\d+\s*-\s*\d+\s*/\s*(\d+)", page_html)
        if m:
            total = int(m.group(1))
        tracks = [
            Track(
                provider=self.name,
                id=c_.get("id", ""),
                source=c_.get("source", source),
                title=c_.get("name", ""),
                artist=c_.get("artist", ""),
                album=c_.get("album", ""),
                cover=c_.get("cover", "") or cover,
                duration=int(re.sub(r"\D", "", c_.get("duration", "") or "0") or 0),
                extra=c_.get("extra", ""),
            )
            for c_ in parse_song_cards(page_html)
        ]
        return tracks, total

    # ---- 失败换源：找其他源的同名曲目 ----
    async def find_alternatives(self, track: Track, limit: int = 6) -> list[Track]:
        """按 歌名+歌手 重搜，返回可替换的其他源候选（已排除自身，按匹配分排序）。"""
        keyword = f"{track.title} {track.artist}".strip() or track.title
        try:
            cands = await self.search(keyword)
        except Exception:  # noqa: BLE001
            return []
        scored = []
        for c in cands:
            if c.source == track.source and c.id == track.id:
                continue
            sc = match_track(c, track.title, track.artist, track.duration)
            if sc >= 3:
                scored.append((sc, c))
        scored.sort(key=lambda x: (-x[0], SOURCE_ORDER.index(x[1].source) if x[1].source in SOURCE_ORDER else 99))
        out, seen = [], set()
        for _, c in scored:
            k = f"{c.source}:{c.id}"
            if k in seen:
                continue
            seen.add(k)
            out.append(c)
            if len(out) >= limit:
                break
        return out

    # ---- 取流 ----
    def _url(self, track: Track, stream: bool) -> str:
        params = {
            "id": track.id,
            "source": track.source,
            "name": track.title,
            "artist": track.artist,
            "album": track.album,
            "cover": track.cover,
            "extra": track.extra,
        }
        if stream:
            params["stream"] = "1"
        return f"{self.base}/download?{urlencode(params)}"

    def stream_url(self, track: Track) -> str:
        return self._url(track, stream=True)

    async def fetch_lyric(self, track: Track) -> str:
        """取 LRC 歌词：music-dl 的 GET /lyric?source=<源>&id=<id> → 纯文本 LRC。"""
        url = f"{self.base}/lyric?{urlencode({'source': track.source, 'id': track.id})}"
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(connect=10, read=20, write=10, pool=10),
            follow_redirects=True,
        ) as c:
            r = await c.get(url, headers={"User-Agent": "music-add/1.0"})
            if r.status_code >= 400:
                return ""
            return r.text

    def download_url(self, track: Track) -> str:
        return self._url(track, stream=False)

    async def _get(self, url: str, stream: bool):
        # read=45s：长时间收不到数据就判失败 → 交给上层自动换源
        client = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=15, read=45, write=30, pool=15),
            follow_redirects=True,
        )
        req = client.build_request("GET", url, headers={"User-Agent": "music-add/1.0"})
        resp = await client.send(req, stream=True)
        return client, resp

    async def stream_response(self, track: Track, range_header: str | None = None):
        """把上游音频流原样转发（返回 (client, response)）。"""
        headers = {"User-Agent": "music-add/1.0"}
        if range_header:
            headers["Range"] = range_header
        client = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=15, read=60, write=30, pool=15),
            follow_redirects=True,
        )
        req = client.build_request("GET", self.stream_url(track), headers=headers)
        resp = await client.send(req, stream=True)
        return client, resp

    async def fetch_bytes(self, track: Track, chunk: int = 128 * 1024, on_progress=None):
        """下载整曲（服务端落盘用）。on_progress(size, total) 用于上报进度。"""
        client, resp = await self._get(self.download_url(track), stream=True)
        total = 0
        try:
            total = int(resp.headers.get("content-length") or 0)
        except (TypeError, ValueError):
            total = 0
        size = 0
        if on_progress:
            on_progress(0, total)
        try:
            async for piece in resp.aiter_bytes(chunk):
                size += len(piece)
                if on_progress:
                    on_progress(size, total)
                yield piece
        finally:
            await resp.aclose()
            await client.aclose()


def sort_tracks(tracks: list[Track]) -> list[Track]:
    """原版优先、源优先级次之、时长合理再次之。"""
    def score(t: Track) -> tuple:
        return (
            1 if t.is_variant else 0,
            SOURCE_ORDER.index(t.source) if t.source in SOURCE_ORDER else 99,
            len(t.title),  # 同名时短标题更可能是原版
        )

    return sorted(tracks, key=score)


def dedupe(tracks: list[Track]) -> list[Track]:
    seen: set[str] = set()
    out: list[Track] = []
    for t in tracks:
        k = f"{t.title}|{t.artist}|{t.duration // 5}"
        if k in seen:
            continue
        seen.add(k)
        out.append(t)
    return out


class LxMusicProvider(BaseProvider):
    """落雪音乐（LX Music）docker 版适配模板（插槽）。

    部署好落雪服务后，按它的实际接口补三处即可，其余全部复用：
      1) search()         : 调它的搜索接口 → 映射成 Track(provider=self.name, ...)
      2) stream_response(): 返回 (httpx_client, response)，把音频流原样转发（支持 Range）
      3) fetch_bytes()    : 下载整曲，供服务端落盘

    只要 name 唯一、Track 字段一致，前端搜索/播放/下载/加歌单逻辑无需改动。
    在 main.py 里设置环境变量 LXMUSIC_BASE 即自动注册；没有该变量则完全不启用。
    """

    name = "lx-music"
    label = "落雪音乐"

    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")

    async def search(self, keyword: str, sources: Iterable[str] | None = None) -> list[Track]:
        raise NotImplementedError("落雪服务部署好后，按其搜索接口实现这里")

    async def stream_response(self, track: Track, range_header: str | None = None):
        raise NotImplementedError("按落雪的取流接口实现（参考 MusicDlProvider.stream_response）")

    async def fetch_bytes(self, track: Track, chunk: int = 128 * 1024):
        raise NotImplementedError("按落雪的取流接口实现（参考 MusicDlProvider.fetch_bytes）")


PROVIDERS: dict[str, BaseProvider] = {}


def register(provider: BaseProvider) -> None:
    PROVIDERS[provider.name] = provider


def get(name: str) -> BaseProvider:
    return PROVIDERS[name]


def all_providers() -> list[BaseProvider]:
    return list(PROVIDERS.values())
