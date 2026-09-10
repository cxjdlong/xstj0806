#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通讯录 · 本地版服务（只用 Python 标准库，无需联网、无需安装依赖）

用途：双击「启动.bat」（或打包后的 通讯录.exe）→ 启动本地服务并自动打开浏览器。
     数据落在本程序所在目录的 db/contacts.db（SQLite），任何浏览器访问都读同一份，
     不需要任何文件夹授权点击。

接口：
  GET  /                → 返回 通讯录.html（同目录）
  GET  /api/health      → {"ok": true, ...}
  GET  /api/db          → {"contacts":[...], "backups":[...]}
  PUT  /api/db          → 整体写入 SQLite（db/contacts.db），并自动快照到 backup/
  GET  /api/info        → 数据文件位置、条数等
  POST /api/backup-xlsx → {"name": "...", "base64": "..."} 把备份 Excel 写进 backup/
  GET  /api/backup-files → 列出 backup/ 里的文件
  DELETE /api/backup-file?name=xxx.xlsx → 删除 backup/ 里的文件
"""
import base64
import json
import os
import sqlite3
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, "frozen", False) else __file__))
DB_DIR = os.path.join(BASE_DIR, "db")
BACKUP_DIR = os.path.join(BASE_DIR, "backup")
DB_PATH = os.path.join(DB_DIR, "contacts.db")
HTML_CANDIDATES = ["通讯录.html", "contacts.html", "index.html"]
DEFAULT_PORT = 19118

SCHEMA = """
CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY, name TEXT, codes TEXT, phones TEXT,
  created_at INTEGER, updated_at INTEGER
);
CREATE TABLE IF NOT EXISTS backups (
  id TEXT PRIMARY KEY, ts INTEGER, label TEXT,
  file_name TEXT, path TEXT, count INTEGER
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
CREATE INDEX IF NOT EXISTS idx_contacts_name ON contacts(name);
"""

_lock = threading.Lock()


def ensure_dirs():
    os.makedirs(DB_DIR, exist_ok=True)
    os.makedirs(BACKUP_DIR, exist_ok=True)


def connect():
    ensure_dirs()
    con = sqlite3.connect(DB_PATH, timeout=10)
    con.executescript(SCHEMA)
    return con


def load_all():
    with _lock:
        con = connect()
        try:
            cur = con.cursor()
            contacts = []
            for rid, name, codes, phones, ca, ua in cur.execute(
                    "SELECT id,name,codes,phones,created_at,updated_at FROM contacts"):
                contacts.append({
                    "id": rid, "name": name or "",
                    "codes": safe_list(codes), "phones": safe_list(phones),
                    "createdAt": ca or 0, "updatedAt": ua or 0,
                })
            backups = []
            for bid, ts, label, fname, path, cnt in cur.execute(
                    "SELECT id,ts,label,file_name,path,count FROM backups"):
                backups.append({
                    "id": bid, "ts": ts or 0, "label": label or "",
                    "fileName": fname or "", "path": path or "", "count": cnt or 0,
                    "data": [], "noSnapshot": True,
                })
            backups.sort(key=lambda b: b["ts"], reverse=True)
            return {"contacts": contacts, "backups": backups}
        finally:
            con.close()


def safe_list(s):
    try:
        v = json.loads(s or "[]")
        return v if isinstance(v, list) else []
    except Exception:
        return []


def save_all(payload):
    contacts = payload.get("contacts") or []
    backups = payload.get("backups") or []
    with _lock:
        con = connect()
        try:
            cur = con.cursor()
            cur.execute("BEGIN")
            cur.execute("DELETE FROM contacts")
            cur.executemany(
                "INSERT INTO contacts (id,name,codes,phones,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                [(c.get("id") or "", c.get("name") or "",
                  json.dumps(c.get("codes") or [], ensure_ascii=False),
                  json.dumps(c.get("phones") or [], ensure_ascii=False),
                  int(c.get("createdAt") or 0), int(c.get("updatedAt") or 0)) for c in contacts])
            cur.execute("DELETE FROM backups")
            cur.executemany(
                "INSERT INTO backups (id,ts,label,file_name,path,count) VALUES (?,?,?,?,?,?)",
                [(b.get("id") or "", int(b.get("ts") or 0), b.get("label") or "",
                  b.get("fileName") or "", b.get("path") or "", int(b.get("count") or 0)) for b in backups])
            cur.execute("INSERT OR REPLACE INTO meta (k,v) VALUES ('app','通讯录')")
            cur.execute("INSERT OR REPLACE INTO meta (k,v) VALUES ('savedAt',?)", (str(int(time.time() * 1000)),))
            con.commit()
        finally:
            con.close()
    auto_snapshot()
    return {"contacts": len(contacts), "backups": len(backups)}


def auto_snapshot():
    """每天保留一份 db 快照到 backup/，避免误删（同一天只留最新一份）。"""
    try:
        ensure_dirs()
        day = time.strftime("%Y%m%d")
        dst = os.path.join(BACKUP_DIR, "contacts_%s.db" % day)
        if os.path.exists(dst):
            return
        with open(DB_PATH, "rb") as src, open(dst, "wb") as out:
            out.write(src.read())
    except Exception:
        pass


def find_html():
    dirs = [BASE_DIR]
    if getattr(sys, "frozen", False):
        dirs.insert(0, getattr(sys, "_MEIPASS", BASE_DIR))   # PyInstaller 打包后 html 内置在 exe 里
    for d in dirs:
        for name in HTML_CANDIDATES:
            p = os.path.join(d, name)
            if os.path.exists(p):
                return p
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "ContactsLocal/1.0"

    def log_message(self, *args):
        pass

    # ---------- 工具 ----------
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def _read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    # ---------- 路由 ----------
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def _query(self):
        from urllib.parse import urlparse, parse_qs
        return parse_qs(urlparse(self.path).query)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            html = find_html()
            if not html:
                self._send(500, "找不到 通讯录.html（应与本程序放在同一文件夹）", "text/plain; charset=utf-8")
                return
            with open(html, "rb") as f:
                self._send(200, f.read(), "text/html; charset=utf-8")
        elif path == "/api/health":
            self._json({"ok": True, "app": "contacts-local", "db": DB_PATH})
        elif path == "/api/info":
            data = load_all()
            self._json({
                "ok": True,
                "dbPath": DB_PATH,
                "backupDir": BACKUP_DIR,
                "contacts": len(data["contacts"]),
                "backups": len(data["backups"]),
                "dbSize": os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0,
            })
        elif path == "/api/db":
            self._json(load_all())
        elif path == "/api/backup-files":
            ensure_dirs()
            items = []
            for n in sorted(os.listdir(BACKUP_DIR), reverse=True):
                p = os.path.join(BACKUP_DIR, n)
                if os.path.isfile(p):
                    items.append({"name": n, "size": os.path.getsize(p),
                                  "mtime": int(os.path.getmtime(p))})
            self._json({"ok": True, "files": items})
        else:
            self._json({"ok": False, "error": "not found"}, 404)

    def do_PUT(self):
        path = self.path.split("?")[0]
        if path == "/api/db":
            try:
                payload = self._read_body()
                r = save_all(payload)
                self._json({"ok": True, **r})
            except Exception as e:
                self._json({"ok": False, "error": str(e)}, 500)
        else:
            self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/api/backup-xlsx":
            try:
                body = self._read_body()
                name = os.path.basename(str(body.get("name") or "backup.xlsx"))
                if not name.lower().endswith((".xlsx", ".xls", ".db")):
                    name += ".xlsx"
                data = base64.b64decode(body.get("base64") or "")
                ensure_dirs()
                dst = os.path.join(BACKUP_DIR, name)
                with open(dst, "wb") as f:
                    f.write(data)
                self._json({"ok": True, "path": dst, "size": len(data), "name": name})
            except Exception as e:
                self._json({"ok": False, "error": str(e)}, 500)
        else:
            self.do_PUT()

    def do_DELETE(self):
        from urllib.parse import unquote
        path = self.path.split("?")[0]
        if path == "/api/backup-file":
            try:
                name = os.path.basename(unquote((self._query().get("name") or [""])[0]))
                if not name:
                    self._json({"ok": False, "error": "缺少 name"}, 400)
                    return
                dst = os.path.join(BACKUP_DIR, name)
                if os.path.exists(dst):
                    os.remove(dst)
                    self._json({"ok": True})
                else:
                    self._json({"ok": False, "error": "文件不存在"}, 404)
            except Exception as e:
                self._json({"ok": False, "error": str(e)}, 500)
        else:
            self._json({"ok": False, "error": "not found"}, 404)


def main():
    port = DEFAULT_PORT
    for arg in sys.argv[1:]:
        if arg.startswith("--port="):
            port = int(arg.split("=", 1)[1])
    no_open = "--no-open" in sys.argv
    ensure_dirs()
    con = connect()
    con.close()

    httpd = None
    for p in range(port, port + 20):
        try:
            httpd = ThreadingHTTPServer(("127.0.0.1", p), Handler)
            port = p
            break
        except OSError:
            continue
    if httpd is None:
        print("端口被占用，启动失败。")
        sys.exit(1)

    url = "http://127.0.0.1:%d/" % port
    print("=" * 56)
    print("  通讯录 · 本地版已启动")
    print("  打开地址： %s" % url)
    print("  数据库：   %s" % DB_PATH)
    print("  备份目录： %s" % BACKUP_DIR)
    print("  关闭本窗口即停止服务（数据已存在 db/contacts.db）")
    print("=" * 56)
    if not no_open:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()