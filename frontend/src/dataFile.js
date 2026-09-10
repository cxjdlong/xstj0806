/**
 * 数据文件（桌面网页版专用）：把数据存成 **和 html 同目录** 的 `通讯录数据.db`
 * —— **真正的 SQLite 数据库文件**（能被 DB Browser for SQLite 等工具直接打开）。
 *
 * 结构：
 *   contacts(id, name, codes, phones, created_at, updated_at)   codes/phones 为 JSON 数组文本
 *   backups (id, ts, label, file_name, path, count)
 *
 * 工作方式（离线、无服务端）：
 *   ① 浏览器 IndexedDB 仍是运行时主存储（快）；
 *   ② 每次改动（防抖 1s）把内存数据整体写入这个 .db 文件 → 文件夹拷到哪、数据跟到哪；
 *   ③ 首次需点一次「选择 html 所在文件夹」授权（浏览器安全要求），之后自动读写。
 *   旧版 `通讯录数据.json` 会被自动识别并迁移成 .db。
 */
import { reactive } from 'vue'
import { store } from './db.js'
import { toast } from './toast.js'
import initSqlJs from 'sql.js/dist/sql-wasm.js'
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url'

const FILE_NAME = 'contacts.db'      // 数据库文件名（ASCII，避免中文名在部分环境出问题）
const LEGACY_DB = '通讯录数据.db'      // 旧中文名，读取时兼容
const DB_SUBDIR = 'db'          // 数据库放在 <html目录>/db/
const BACKUP_SUBDIR = 'backup'  // 备份 Excel 放在 <html目录>/backup/
const LEGACY_JSON = '通讯录数据.json'
const IDB_NAME = 'contacts_fs'
const IDB_STORE = 'kv'
const IDB_KEY = 'dirHandle'

const supported = typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function'

export const dfState = reactive({
  supported,
  connected: false,
  dirName: '',
  fileName: FILE_NAME,
  busy: false,
  lastSaved: 0,
  lastError: '',
  needGrant: false,
  dbMs: 0,
})

let dirHandle = null
let SQL = null
let suppressWrite = false

/* ---------------- IndexedDB 存目录句柄 ---------------- */
function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open(IDB_NAME, 1)
    r.onupgradeneeded = () => { if (!r.result.objectStoreNames.contains(IDB_STORE)) r.result.createObjectStore(IDB_STORE) }
    r.onsuccess = () => res(r.result)
    r.onerror = () => rej(r.error)
  })
}
async function idbSet(key, val) {
  const db = await idb()
  return new Promise((res, rej) => {
    const tx = db.transaction(IDB_STORE, 'readwrite')
    tx.objectStore(IDB_STORE).put(val, key)
    tx.oncomplete = () => res()
    tx.onerror = () => rej(tx.error)
  })
}
async function idbGet(key) {
  const db = await idb()
  return new Promise((res, rej) => {
    const tx = db.transaction(IDB_STORE, 'readonly')
    const q = tx.objectStore(IDB_STORE).get(key)
    q.onsuccess = () => res(q.result)
    q.onerror = () => rej(q.error)
  })
}
async function idbDel(key) {
  const db = await idb()
  return new Promise((res) => {
    const tx = db.transaction(IDB_STORE, 'readwrite')
    tx.objectStore(IDB_STORE).delete(key)
    tx.oncomplete = () => res()
    tx.onerror = () => res()
  })
}

/* ---------------- SQLite ---------------- */
async function ensureSql() {
  if (SQL) return SQL
  SQL = await initSqlJs({ locateFile: () => wasmUrl })
  return SQL
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS contacts (
  id TEXT PRIMARY KEY, name TEXT, province TEXT, codes TEXT, phones TEXT,
  created_at INTEGER, updated_at INTEGER
);
CREATE TABLE IF NOT EXISTS backups (
  id TEXT PRIMARY KEY, ts INTEGER, label TEXT,
  file_name TEXT, path TEXT, count INTEGER
);
CREATE INDEX IF NOT EXISTS idx_contacts_name ON contacts(name);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
`

function newDb() {
  const db = new SQL.Database()
  db.run(SCHEMA)
  return db
}

/** 内存数据 → SQLite（整体重建，保证文件与界面一致） */
function dbFromStore() {
  const db = newDb()
  db.run('BEGIN')
  const insC = db.prepare('INSERT INTO contacts (id,name,province,codes,phones,created_at,updated_at) VALUES (?,?,?,?,?,?,?)')
  for (const c of store.contacts) {
    insC.run([c.id, c.name || '', c.province || '', JSON.stringify(c.codes || []), JSON.stringify(c.phones || []), c.createdAt || 0, c.updatedAt || 0])
  }
  insC.free()
  const insB = db.prepare('INSERT INTO backups (id,ts,label,file_name,path,count) VALUES (?,?,?,?,?,?)')
  for (const b of store.backups) {
    insB.run([b.id, b.ts || 0, b.label || '', b.fileName || '', b.path || '', b.count || 0])
  }
  insB.free()
  db.run('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)', ['app', '通讯录'])
  db.run('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)', ['version', '1'])
  db.run('INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)', ['savedAt', String(Date.now())])
  db.run('COMMIT')
  return db
}

/** SQLite 字节 → 内存 */
function storeFromBytes(bytes) {
  const db = new SQL.Database(bytes)
  const out = { contacts: [], backups: [] }
  try {
    // 兼容旧库（没有 province 列时自动补列）
    try {
      const cols = db.exec('PRAGMA table_info(contacts)')
      const names = cols[0] ? cols[0].values.map(v => v[1]) : []
      if (!names.includes('province')) db.run('ALTER TABLE contacts ADD COLUMN province TEXT')
    } catch (e) { /* 忽略 */ }
    const r1 = db.exec('SELECT id,name,province,codes,phones,created_at,updated_at FROM contacts')
    if (r1[0]) {
      for (const row of r1[0].values) {
        out.contacts.push({
          id: String(row[0]),
          name: row[1] || '',
          province: row[2] || '',
          codes: safeArr(row[3]),
          phones: safeArr(row[4]),
          createdAt: Number(row[5]) || Date.now(),
          updatedAt: Number(row[6]) || Date.now(),
        })
      }
    }
    const r2 = db.exec('SELECT id,ts,label,file_name,path,count FROM backups')
    if (r2[0]) {
      for (const row of r2[0].values) {
        out.backups.push({
          id: String(row[0]), ts: Number(row[1]) || 0, label: row[2] || '',
          fileName: row[3] || '', path: row[4] || '', count: Number(row[5]) || 0,
          data: [], noSnapshot: true,
        })
      }
    }
  } finally {
    db.close()
  }
  return out
}

function safeArr(s) {
  try { const v = JSON.parse(s || '[]'); return Array.isArray(v) ? v : [] } catch (e) { return [] }
}

function applyToStore(data) {
  suppressWrite = true
  try {
    if (Array.isArray(data.contacts)) store.contacts.splice(0, store.contacts.length, ...data.contacts)
    if (Array.isArray(data.backups)) store.backups.splice(0, store.backups.length, ...data.backups)
  } finally {
    setTimeout(() => { suppressWrite = false }, 150)
  }
}

/** 取（并按需创建）子目录，例如 <html目录>/db、<html目录>/backup */
async function subDir(root, name, create = true) {
  return root.getDirectoryHandle(name, { create })
}

async function readBytes(root) {
  const dir = await (async () => { try { return await subDir(root, DB_SUBDIR, false) } catch (e) { return null } })()
  if (!dir) return null
  for (const name of [FILE_NAME, LEGACY_DB]) {
    try {
      const fh = await dir.getFileHandle(name, { create: false })
      const buf = await (await fh.getFile()).arrayBuffer()
      if (buf && buf.byteLength > 0) return new Uint8Array(buf)
    } catch (e) { /* 试下一个 */ }
  }
  return null
}

async function writeBytes(root, bytes) {
  const dir = await subDir(root, DB_SUBDIR, true)
  const fh = await dir.getFileHandle(FILE_NAME, { create: true })
  const w = await fh.createWritable()
  await w.write(bytes)
  await w.close()
}

function b64ToBytes(b64) {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

/** 导出当前数据为 .db 字节的 Blob（不需要文件夹授权，任何浏览器可用） */
export async function dumpBytesToBlob() {
  await ensureSql()
  const db = dbFromStore()
  const bytes = db.export()
  db.close()
  return new Blob([bytes], { type: 'application/x-sqlite3' })
}

/** 导入 .db 字节：覆盖当前数据，并立即落库（已连接则同时写回文件夹） */
export async function importDbBytes(bytes) {
  await ensureSql()
  const parsed = storeFromBytes(bytes)
  applyToStore(parsed)
  const { saveNowForce } = await import('./db.js')
  saveNowForce()
  if (dfState.connected) await writeNow(false)
  return parsed.contacts.length
}

/** 备份 Excel 落到 <html目录>/backup/，返回展示用路径 */
export async function saveBackupExcel(fileName, base64) {
  if (!dfState.connected || !dirHandle) return ''
  const dir = await subDir(dirHandle, BACKUP_SUBDIR, true)
  const fh = await dir.getFileHandle(fileName, { create: true })
  const w = await fh.createWritable()
  await w.write(b64ToBytes(base64))
  await w.close()
  return `${dfState.dirName}/${BACKUP_SUBDIR}/${fileName}`
}

/** 删除 backup/ 里的备份文件（path 为展示路径或文件名） */
export async function deleteBackupExcel(path) {
  if (!dfState.connected || !dirHandle || !path) return false
  const name = String(path).split('/').pop()
  try {
    const dir = await subDir(dirHandle, BACKUP_SUBDIR, false)
    await dir.removeEntry(name)
    return true
  } catch (e) {
    return false
  }
}

/** 列出 backup/ 目录里的文件（用于校验/展示） */
export async function listBackupFiles() {
  if (!dfState.connected || !dirHandle) return []
  try {
    const dir = await subDir(dirHandle, BACKUP_SUBDIR, false)
    const out = []
    for await (const [name, handle] of dir.entries()) {
      if (handle.kind === 'file') {
        const f = await handle.getFile()
        out.push({ name, size: f.size, lastModified: f.lastModified })
      }
    }
    return out.sort((a, b) => b.lastModified - a.lastModified)
  } catch (e) {
    return []
  }
}

/** 旧版 JSON 数据文件迁移（存在则读取并转成 .db） */
async function readLegacyJson(dir) {
  try {
    const fh = await dir.getFileHandle(LEGACY_JSON, { create: false })
    const txt = await (await fh.getFile()).text()
    const obj = JSON.parse(txt)
    if (obj && Array.isArray(obj.contacts)) {
      return { contacts: obj.contacts, backups: Array.isArray(obj.backups) ? obj.backups : [] }
    }
  } catch (e) { /* 忽略 */ }
  return null
}

/** 把内存数据写进 .db 文件 */
export async function writeNow(silent = true) {
  if (!dfState.connected || !dirHandle) return false
  try {
    const t0 = performance.now()
    await ensureSql()
    const db = dbFromStore()
    const bytes = db.export()
    db.close()
    await writeBytes(dirHandle, bytes)
    dfState.dbMs = Math.round(performance.now() - t0)
    dfState.lastSaved = Date.now()
    dfState.lastError = ''
    return true
  } catch (e) {
    dfState.lastError = String(e && e.message ? e.message : e)
    if (dfState.lastError !== dfState._lastToasted) {
      dfState._lastToasted = dfState.lastError
      toast('写入数据库文件失败：' + dfState.lastError, 'warn', 6000)
    }
    if (!silent) window.alert('写入数据库文件失败：' + dfState.lastError)
    return false
  }
}

/** 用户点选文件夹（应是 html 所在文件夹） */
export async function pickFolder() {
  if (!supported) {
    window.alert('当前浏览器不支持直接读写文件。请用 Chrome / Edge 打开本页面。')
    return false
  }
  dfState.busy = true
  try {
    const dir = await window.showDirectoryPicker({ id: 'contacts-data', mode: 'readwrite', startIn: 'documents' })
    if (dir.name !== currentDirName()) {
      const go = window.confirm(
        `你选的是文件夹「${dir.name}」，而本页面所在文件夹是「${currentDirName()}」。\n\n` +
        `数据库文件将保存在你选的文件夹里（${dir.name}/${FILE_NAME}）。\n` +
        `要让数据跟着 html 走，请改选 html 所在的那个文件夹。\n\n仍要用「${dir.name}」吗？`
      )
      if (!go) { dfState.busy = false; return false }
    }
    try { await idbSet(IDB_KEY, dir) } catch (e) { /* 句柄存不下也能用本次会话 */ }
    dirHandle = dir
    dfState.dirName = dir.name
    dfState.connected = true
    dfState.needGrant = false
    await ensureSql()
    // 先把目录结构建好：<html目录>/db（数据库）、<html目录>/backup（备份）
    try { await subDir(dir, DB_SUBDIR, true); await subDir(dir, BACKUP_SUBDIR, true) } catch (e) {}

    const bytes = await readBytes(dir)
    const legacy = bytes ? null : await readLegacyJson(dir)
    if (bytes && bytes.length > 100) {
      const parsed = storeFromBytes(bytes)
      const useFile = window.confirm(`该文件夹里已有数据库文件 ${FILE_NAME}（${parsed.contacts.length} 条联系人）。\n\n点「确定」= 用文件里的数据覆盖当前页面数据\n点「取消」= 用当前页面数据覆盖那个数据库文件`)
      if (useFile) applyToStore(parsed)
      else await writeNow(false)
    } else if (legacy) {
      if (window.confirm(`发现旧版数据文件 ${LEGACY_JSON}（${legacy.contacts.length} 条联系人）。\n\n点「确定」= 迁移成 ${FILE_NAME}（推荐）\n点「取消」= 忽略，用当前页面数据`)) {
        applyToStore(legacy)
      }
      await writeNow(false)
    } else {
      await writeNow(false)
    }
    return true
  } catch (e) {
    if (e && e.name !== 'AbortError') window.alert('选择文件夹失败：' + (e.message || e))
    return false
  } finally {
    dfState.busy = false
  }
}

/** 本次会话需要重新授权时点一下 */
export async function grantAndConnect() {
  if (!dirHandle) return false
  try {
    const p = await dirHandle.requestPermission({ mode: 'readwrite' })
    if (p !== 'granted') return false
    await ensureSql()
    const bytes = await readBytes(dirHandle)
    dfState.connected = true
    dfState.needGrant = false
    if (bytes && bytes.length > 100) {
      const parsed = storeFromBytes(bytes)
      const useFile = window.confirm(`数据库文件已存在（${parsed.contacts.length} 条联系人）。\n\n点「确定」= 用文件里的数据覆盖当前页面数据\n点「取消」= 用当前页面数据覆盖那个数据库文件`)
      if (useFile) applyToStore(parsed)
      else await writeNow(false)
    } else {
      await writeNow(false)
    }
    return true
  } catch (e) {
    return false
  }
}

/** 启动时：取回上次的文件夹句柄 */
export async function restoreFromStore() {
  if (!supported) return
  try {
    const h = await idbGet(IDB_KEY)
    if (!h) return
    dirHandle = h
    dfState.dirName = h.name || ''
    const p = await h.queryPermission({ mode: 'readwrite' })
    if (p === 'granted') {
      await ensureSql()
      const bytes = await readBytes(h)
      dfState.connected = true
      if (bytes && bytes.length > 100) applyToStore(storeFromBytes(bytes))
      else await writeNow(true)
    } else if (p === 'prompt') {
      dfState.needGrant = true
    }
  } catch (e) { /* 忽略 */ }
}

/**
 * 写入自检：逐步验证「建目录 / 写文件 / 读回 / 删文件」，返回可读报告。
 * 用户排查「没创建文件」时点一下即可定位。
 */
export async function selfTest() {
  const lines = []
  lines.push('浏览器支持 direct 读写: ' + (supported ? '是' : '否（需 Chrome / Edge）'))
  if (!supported) return lines.join('\n')
  lines.push('已连接文件夹: ' + (dfState.connected ? ('是 → ' + dfState.dirName) : '否（需先点「选择 html 所在文件夹」授权）'))
  if (!dfState.connected || !dirHandle) return lines.join('\n')
  try {
    const p = await dirHandle.queryPermission({ mode: 'readwrite' })
    lines.push('文件夹权限: ' + p + (p === 'granted' ? '（可读写）' : '（需点「授权并连接」或重选文件夹）'))
    if (p !== 'granted') return lines.join('\n')
  } catch (e) {
    lines.push('权限查询失败: ' + e.message)
  }
  try {
    await ensureSql()
    lines.push('SQLite 引擎: 正常')
  } catch (e) {
    lines.push('SQLite 引擎初始化失败: ' + e.message)
    return lines.join('\n')
  }
  try {
    const dbd = await subDir(dirHandle, DB_SUBDIR, true)
    const bd = await subDir(dirHandle, BACKUP_SUBDIR, true)
    lines.push('目录: ' + dfState.dirName + '/' + DB_SUBDIR + ' 、 ' + dfState.dirName + '/' + BACKUP_SUBDIR + ' —— 已就绪')
    // 写测试文件
    const fh = await dbd.getFileHandle('__写入自检.txt', { create: true })
    const w = await fh.createWritable()
    await w.write(new TextEncoder().encode('ok'))
    await w.close()
    const back = await (await dbd.getFileHandle('__写入自检.txt')).getFile()
    const txt = await back.text()
    lines.push('写/读测试文件: ' + (txt === 'ok' ? '成功' : '内容异常'))
    await dbd.removeEntry('__写入自检.txt')
    lines.push('删除测试文件: 成功')
    const ok = await writeNow(false)
    lines.push('写数据库文件(' + DB_SUBDIR + '/' + FILE_NAME + '): ' + (ok ? ('成功，用时 ' + dfState.dbMs + 'ms') : ('失败 → ' + (dfState.lastError || '未知'))))
  } catch (e) {
    lines.push('出错: ' + (e && e.message ? e.message : e) + (e && e.name ? '（' + e.name + '）' : ''))
  }
  return lines.join('\n')
}

export function disconnect() {
  dirHandle = null
  dfState.connected = false
  dfState.needGrant = false
  dfState.dirName = ''
  idbDel(IDB_KEY)
}

export function shouldWrite() {
  return dfState.connected && !!dirHandle && !suppressWrite
}

export function currentDirName() {
  try {
    const p = decodeURIComponent(location.pathname)
    if (location.protocol === 'file:') {
      const parts = p.split('/').filter(Boolean)
      return parts.length >= 2 ? parts[parts.length - 2] : (parts[0] || '')
    }
    return (location.pathname.split('/').filter(Boolean).slice(-2, -1)[0] || '')
  } catch (e) {
    return ''
  }
}

/* 排障/自测钩子 */
if (typeof window !== 'undefined') {
  window.__contactsDataFile = {
    state: dfState,
    storeFromBytes, dbFromStore,
    setDir: d => { dirHandle = d; dfState.dirName = (d && d.name) || '' },
    setConnected: v => { dfState.connected = !!v },
    ensureSql, currentDirName,
    async dumpBytes() {
      await ensureSql()
      const db = dbFromStore()
      const b = db.export()
      db.close()
      return b
    },
  }
}
