"""飞牛音乐（trim.music）API 客户端。

飞牛音乐是 fnOS 系统内置应用（Go 写的），本模块通过宿主 unix socket
直接调它的 HTTP API（base path /music），用于：
  - 按登录用户名查该用户的 token（读只读挂载的 music.db）
  - 列出该用户的歌单
  - 把曲目加入歌单
"""

from __future__ import annotations

import http.client
import json
import socket
import sqlite3
from typing import Any


class FnosMusicError(Exception):
    pass


class FnosMusic:
    def __init__(
        self,
        socket_path: str = "/run/trim_music.socket",
        db_path: str = "/fnos-db/music.db",
        timeout: int = 20,
    ) -> None:
        self.socket_path = socket_path
        self.db_path = db_path
        self.timeout = timeout

    # ---------------- 底层 HTTP over unix socket ----------------

    def _request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        token: str | None = None,
        timeout: int | None = None,
    ) -> Any:
        to = timeout or self.timeout
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = token  # 裸 token，带 Bearer 会被判 401

        class _UnixHTTP(http.client.HTTPConnection):
            def __init__(self, sock_path: str) -> None:
                super().__init__("localhost", timeout=to)
                self._sock_path = sock_path

            def connect(self) -> None:  # type: ignore[override]
                s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                s.settimeout(to)
                s.connect(self._sock_path)
                self.sock = s

        conn = _UnixHTTP(self.socket_path)
        payload = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        try:
            conn.request(method, path, payload, headers)
            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", "ignore")
        except (OSError, http.client.HTTPException) as exc:
            raise FnosMusicError(f"飞牛音乐接口不可达：{exc}") from exc
        finally:
            conn.close()

        try:
            data = json.loads(raw)
        except ValueError as exc:
            raise FnosMusicError(f"飞牛音乐返回非 JSON：{raw[:200]}") from exc

        code = data.get("code")
        if code not in (0, None):
            raise FnosMusicError(f"飞牛音乐接口错误 {code}: {data.get('msg')}")
        return data.get("data")

    # ---------------- 账号（读 music.db） ----------------

    def list_users(self) -> list[dict]:
        """列出飞牛音乐里的 active 用户。"""
        sql = (
            "select u.name, u.role, u.status from user u "
            "where u.status = 'active' order by u.id"
        )
        rows = self._db_query(sql)
        return [{"name": r[0], "role": r[1], "status": r[2]} for r in rows]

    def token_for(self, username: str) -> str | None:
        """取该用户的 token（取有效期最晚的一个）。"""
        sql = (
            "select t.token, t.expired_at from user_token t "
            "join user u on u.id = t.user_id "
            "where u.name = ? and u.status = 'active' "
            "order by t.expired_at desc limit 1"
        )
        rows = self._db_query(sql, (username,))
        return rows[0][0] if rows else None

    def _db_query(self, sql: str, params: tuple = ()) -> list[tuple]:
        try:
            con = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True, timeout=10)
        except sqlite3.Error as exc:
            raise FnosMusicError(f"无法打开飞牛音乐数据库：{exc}") from exc
        try:
            return list(con.execute(sql, params))
        except sqlite3.Error as exc:
            raise FnosMusicError(f"查询飞牛音乐数据库失败：{exc}") from exc
        finally:
            con.close()

    # ---------------- 业务接口 ----------------

    def list_playlists(self, token: str, with_count: bool = True) -> list[dict]:
        """列出歌单。列表接口不返回曲目数，需要时逐个查 detail 补齐。"""
        data = self._request("GET", "/music/api/v1/playlist/list", token=token) or {}
        out = []
        for p in data.get("list") or []:
            item = {
                "guid": p.get("guid"),
                "name": p.get("name"),
                "trackCount": p.get("trackCount"),
            }
            if with_count and item["trackCount"] is None and item["guid"]:
                try:
                    detail = self._request(
                        "GET",
                        f"/music/api/v1/playlist/detail?guid={item['guid']}",
                        token=token,
                    ) or {}
                    item["trackCount"] = detail.get("trackCount")
                except FnosMusicError:
                    pass
            out.append(item)
        return out

    def search_track(self, token: str, keyword: str, size: int = 20) -> list[dict]:
        from urllib.parse import quote

        path = f"/music/api/v1/search/track?q={quote(keyword)}&page=1&size={size}"
        data = self._request("GET", path, token=token) or {}
        out = []
        for t in data.get("list") or []:
            artists = t.get("artists") or []
            out.append(
                {
                    "guid": t.get("guid"),
                    "title": t.get("title"),
                    "artist": "、".join(a.get("name", "") for a in artists),
                    "duration": (t.get("duration") or 0) // 1000,
                }
            )
        return out

    def add_tracks(self, token: str, playlist_guid: str, track_guids: list[str]) -> None:
        if not track_guids:
            return
        self._request(
            "POST",
            "/music/api/v1/playlist/add-track",
            {"guid": playlist_guid, "trackGUIDs": track_guids},
            token=token,
        )

    def create_playlist(self, token: str, name: str) -> dict:
        data = self._request(
            "POST", "/music/api/v1/playlist/create", {"name": name}, token=token
        ) or {}
        return {"guid": data.get("guid"), "name": data.get("name")}

    def rename_playlist(self, token: str, playlist_guid: str, name: str) -> None:
        """给歌单改名（直接改飞牛音乐里的歌单名）。

        实测接口：POST /music/api/v1/playlist/edit  {"guid": <歌单guid>, "name": <新名>}
        参数名就是小写 guid + name（用 playlistGUID/playlistGuid 会报 100002 invalid arguments）。
        """
        self._request(
            "POST",
            "/music/api/v1/playlist/edit",
            {"guid": playlist_guid, "name": name},
            token=token,
        )

    def playlist_tracks(self, token: str, playlist_guid: str, size: int = 100) -> list[dict]:
        """列歌单内曲目。

        唯一正确接口：GET /music/api/v1/track/playlist-detail/list
        参数名必须是大写 playlistGUID（小写/别的名字不报错但返回曲库列表）。
        """
        out: list[dict] = []
        page = 1
        while True:
            data = self._request(
                "GET",
                f"/music/api/v1/track/playlist-detail/list"
                f"?playlistGUID={playlist_guid}&page={page}&size={size}&sort=",
                token=token,
            ) or {}
            lst = data.get("list") or []
            for t in lst:
                artists = t.get("artists") or []
                out.append(
                    {
                        "guid": t.get("guid"),
                        "title": t.get("title"),
                        "artist": "、".join(a.get("name", "") for a in artists),
                        "duration": (t.get("duration") or 0) // 1000,
                    }
                )
            total = data.get("total") or 0
            if not lst or len(out) >= total:
                break
            page += 1
        return out

    def scan_library(self, token: str) -> dict:
        """触发飞牛音乐扫库 + 重建搜索索引（把曲库里的新文件扫进去）。

        路由是从应用前端 JS 里挖出来的，前缀/归属有几种可能，逐个试。
        """
        out: dict = {}
        for path in (
            "/music/api/v1/scan-all",
            "/music/api/v1/library/scan-all",
            "/music/api/v1/library/scan",
            "/music/api/v1/index/rebuild",
            "/music/api/v1/library/index/rebuild",
        ):
            try:
                out[path] = self._request("POST", path, {}, token=token)
            except Exception as exc:  # noqa: BLE001
                out[path] = f"ERR {exc}"
        return out

    def remove_tracks(self, token: str, playlist_guid: str, track_guids: list[str]) -> None:
        """从歌单里移除曲目（与 add_tracks 对称：POST /playlist/remove-track）。"""
        if not track_guids:
            return
        self._request(
            "POST",
            "/music/api/v1/playlist/remove-track",
            {"guid": playlist_guid, "trackGUIDs": list(track_guids)},
            token=token,
        )

    def delete_tracks(self, token: str, track_guids: list[str]) -> None:
        """从飞牛音乐曲库里删除曲目。

        路由和参数名都没在文档里，自动逐个试（路径 × 参数格式），
        哪个先成功就用哪个。用假 guid 探测不会误删。
        """
        if not track_guids:
            return
        g0 = track_guids[0]
        bodies: list[dict] = [
            {"trackGUIDs": list(track_guids)},
            {"guids": list(track_guids)},
            {"guid": g0},
            {"trackGuid": g0},
            {"trackGUID": g0},
            {"trackGuids": list(track_guids)},
        ]
        paths = [
            "/music/api/v1/track/delete",
            "/music/api/v1/track/remove",
            "/music/api/v1/track/delete-track",
            "/music/api/v1/tracks/delete",
            "/music/api/v1/library/track/delete",
            "/music/api/v1/media/delete",
        ]
        last: Exception | None = None
        for path in paths:
            for body in bodies:
                try:
                    self._request("POST", path, body, token=token)
                    self._delete_route = (path, tuple(body.keys()))  # 记住成功的组合
                    return
                except Exception as exc:  # noqa: BLE001
                    last = exc
        raise last if last else FnosMusicError("删除曲目失败")

    def delete_playlist(self, token: str, playlist_guid: str) -> None:
        """删除飞牛音乐里的歌单（POST /music/api/v1/playlist/delete {"guid": ...}）。"""
        self._request(
            "POST",
            "/music/api/v1/playlist/delete",
            {"guid": playlist_guid},
            token=token,
        )
