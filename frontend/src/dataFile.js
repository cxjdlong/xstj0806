/**
 * 数据文件（桌面网页版专用）：把数据存成 **和 html 同目录** 的 `通讯录数据.json`。
 *
 * 为什么需要一次点击授权：浏览器安全策略不允许 file:// 页面静默读写磁盘，
 * 所以第一次要你「选一次 html 所在文件夹」（Chrome/Edge 会记住上次选的目录，之后就自动了）。
 * 句柄存在 IndexedDB 里，下次打开自动重连；若浏览器要求重新授权，页面上点一下「授权并连接」即可。
 *
 * 未连接时：仍用浏览器本地存储（localStorage），功能不受影响。
 */
import { reactive } from 'vue'
import { store } from './db.js'

const FILE_NAME = '通讯录数据.json'
const LEGACY = '通讯录数据.json'
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
  needGrant: false,   // 已记住文件夹，但本次会话需要再点一下授权
})

let dirHandle = null
let suppressWrite = false

/* ---------------- IndexedDB 存取句柄 ---------------- */
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

/* ---------------- 数据打包/落地 ---------------- */
export function pack() {
  // 文件里只存联系人 + 备份的「记录」（不含快照 data），保证万级数据下文件小、写入快。
  // 快照留在本地数据库(IndexedDB)里，文件用于跨电脑携带 + 长期归档。
  return {
    app: '通讯录',
    version: 2,
    savedAt: Date.now(),
    contacts: JSON.parse(JSON.stringify(store.contacts)),
    backups: store.backups.map(b => ({
      id: b.id, ts: b.ts, label: b.label, fileName: b.fileName, path: b.path,
      count: b.count, noSnapshot: !!b.noSnapshot,
    })),
  }
}

function apply(obj) {
  suppressWrite = true
  try {
    if (Array.isArray(obj.contacts)) store.contacts.splice(0, store.contacts.length, ...obj.contacts)
    if (Array.isArray(obj.backups)) {
      // 文件里的备份只有记录；本地若有同名快照则保留快照
      const localSnap = new Map(store.backups.filter(b => b.data && b.data.length).map(b => [b.id, b.data]))
      const merged = obj.backups.map(b => ({ ...b, data: b.data && b.data.length ? b.data : (localSnap.get(b.id) || []) }))
      store.backups.splice(0, store.backups.length, ...merged)
    }
  } finally {
    setTimeout(() => { suppressWrite = false }, 120)
  }
}

async function readFile(dir) {
  try {
    const fh = await dir.getFileHandle(FILE_NAME, { create: false })
    const f = await fh.getFile()
    const txt = await f.text()
    if (!txt.trim()) return null
    return JSON.parse(txt)
  } catch (e) {
    return null   // 文件不存在或读失败
  }
}

async function writeFile(dir, data) {
  const fh = await dir.getFileHandle(FILE_NAME, { create: true })
  const w = await fh.createWritable()
  // 数据量大时用紧凑格式（文件更小、写入更快）；小数据保留缩进便于查看
  const small = !data.contacts || data.contacts.length <= 1000
  await w.write(small ? JSON.stringify(data, null, 2) : JSON.stringify(data))
  await w.close()
}

/** 按需把内存数据写入数据文件（防抖在调用侧做） */
export async function writeNow(silent = true) {
  if (!dfState.connected || !dirHandle) return false
  try {
    await writeFile(dirHandle, pack())
    dfState.lastSaved = Date.now()
    dfState.lastError = ''
    return true
  } catch (e) {
    dfState.lastError = String(e && e.message ? e.message : e)
    if (!silent) window.alert('写入数据文件失败：' + dfState.lastError)
    return false
  }
}

/** 用户点选文件夹（必须是 html 所在的文件夹） */
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
        `数据文件将保存在你选的文件夹里（${dir.name}/${FILE_NAME}）。\n` +
        `要让数据跟着 html 走，请改选 html 所在的那个文件夹。\n\n仍要用「${dir.name}」吗？`
      )
      if (!go) { dfState.busy = false; return false }
    }
    try { await idbSet(IDB_KEY, dir) } catch (e) { /* 句柄存不下也能用本次会话 */ }
    const existing = await readFile(dir)
    dirHandle = dir
    dfState.dirName = dir.name
    dfState.connected = true
    dfState.needGrant = false
    if (existing) {
      const useFile = window.confirm(`该文件夹里已有数据文件（${existing.contacts ? existing.contacts.length : 0} 条联系人）。\n\n点「确定」= 用文件里的数据覆盖当前页面数据\n点「取消」= 用当前页面数据覆盖那个文件`)
      if (useFile) apply(existing)
      else await writeFile(dir, pack())
    } else {
      await writeFile(dir, pack())
    }
    dfState.lastSaved = Date.now()
    return true
  } catch (e) {
    if (e && e.name !== 'AbortError') window.alert('选择文件夹失败：' + (e.message || e))
    return false
  } finally {
    dfState.busy = false
  }
}

/** 本次会话需要重新授权时点一下（浏览器安全要求用户手势） */
export async function grantAndConnect() {
  if (!dirHandle) return false
  try {
    const p = await dirHandle.requestPermission({ mode: 'readwrite' })
    if (p !== 'granted') return false
    const existing = await readFile(dirHandle)
    dfState.connected = true
    dfState.needGrant = false
    if (existing) {
      // 有文件就先问清楚，避免把刚录入的数据直接冲掉
      const useFile = window.confirm(`数据文件已存在（${existing.contacts ? existing.contacts.length : 0} 条联系人）。\n\n点「确定」= 用文件里的数据覆盖当前页面数据\n点「取消」= 用当前页面数据覆盖那个文件`)
      if (useFile) apply(existing)
      else await writeFile(dirHandle, pack())
    } else {
      await writeFile(dirHandle, pack())
    }
    dfState.lastSaved = Date.now()
    return true
  } catch (e) {
    return false
  }
}

/** 启动时：从 IndexedDB 取回上次的文件夹句柄 */
export async function restoreFromStore() {
  if (!supported) return
  try {
    const h = await idbGet(IDB_KEY)
    if (!h) return
    dirHandle = h
    dfState.dirName = h.name || ''
    const p = await h.queryPermission({ mode: 'readwrite' })
    if (p === 'granted') {
      const existing = await readFile(h)
      dfState.connected = true
      if (existing) apply(existing)
      else await writeFile(h, pack())
      dfState.lastSaved = Date.now()
    } else if (p === 'prompt') {
      dfState.needGrant = true          // 页面上点一下「授权并连接」
    }
  } catch (e) {
    /* 忽略 */
  }
}

export function disconnect() {
  dirHandle = null
  dfState.connected = false
  dfState.needGrant = false
  dfState.dirName = ''
  idbDel(IDB_KEY)
}

/** 是否应写入文件（供 store watch 调用） */
export function shouldWrite() {
  return dfState.connected && !!dirHandle && !suppressWrite
}

/** 当前 html 所在文件夹名（用于校验用户是否选对） */
export function currentDirName() {
  try {
    const p = decodeURIComponent(location.pathname)
    if (location.protocol === 'file:') {
      const parts = p.split('/').filter(Boolean)
      // Windows: /D:/folder/file.html → 取 folder；Linux: /a/b/file.html → 取 b
      return parts.length >= 2 ? parts[parts.length - 2] : (parts[0] || '')
    }
    return (location.pathname.split('/').filter(Boolean).slice(-2, -1)[0] || '')
  } catch (e) {
    return ''
  }
}

/* 排障/自测钩子：可注入模拟文件夹句柄验证读写逻辑（不影响正常使用） */
if (typeof window !== 'undefined') {
  window.__contactsDataFile = {
    state: dfState,
    pack, readFile, writeFile,
    setDir: d => { dirHandle = d; dfState.dirName = (d && d.name) || '' },
    setConnected: v => { dfState.connected = !!v },
    currentDirName,
  }
}
