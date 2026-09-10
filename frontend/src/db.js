/**
 * 通讯录 · 数据层
 *
 * 存储策略（避免万级数据卡顿 / 撑爆）：
 *  ① 主存储 = **IndexedDB**（真正的本地数据库）：异步、无 5MB 限制、直接存对象（不做 JSON.stringify），
 *     因此每次改动不会因全量序列化而卡主线程。
 *  ② 旧版的 localStorage 数据 **自动迁移** 进 IndexedDB（只迁一次）。
 *  ③ 桌面网页版可再接「数据文件」（html 同目录），做跨电脑携带/备份（见 dataFile.js）。
 *
 * 结构：
 *   contacts: [{ id, codes:[], phones:[], name, createdAt, updatedAt }]
 *   backups:  [{ id, ts, label, fileName, path, count, data:[...]|[], noSnapshot }]
 */
import { reactive, watch, markRaw } from 'vue'

const LEGACY_KEY = 'contacts_db_v1'      // 旧版 localStorage 键
const IDB_NAME = 'contacts_db'
const IDB_STORE = 'kv'
const IDB_KEY = 'db'
const VERSION = 2

function emptyDb() {
  return { version: VERSION, contacts: [], backups: [] }
}

function fixContact(c) {
  return markRaw({
    id: c.id || uid(),
    codes: normList(c.codes),
    phones: normList(c.phones),
    name: (c.name || '').trim(),
    province: (c.province || '').trim(),
    createdAt: c.createdAt || Date.now(),
    updatedAt: c.updatedAt || c.createdAt || Date.now(),
  })
}

function normalize(d) {
  const db = emptyDb()
  if (d && Array.isArray(d.contacts)) db.contacts = d.contacts.map(fixContact)
  if (d && Array.isArray(d.backups)) db.backups = d.backups.map(b => markRaw(b))
  return db
}

/** 同步取旧版 localStorage 数据（有就先渲染，随后被 IndexedDB 覆盖） */
function loadLegacy() {
  try {
    const raw = localStorage.getItem(LEGACY_KEY)
    if (!raw) return emptyDb()
    return normalize(JSON.parse(raw))
  } catch (e) {
    return emptyDb()
  }
}

export function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

/** 去空白、去重（保序） */
export function normList(arr) {
  const out = []
  const seen = new Set()
  for (const v of arr || []) {
    const s = String(v == null ? '' : v).trim()
    if (!s) continue
    if (seen.has(s)) continue
    seen.add(s)
    out.push(s)
  }
  return out
}

/* ================= IndexedDB 底层（无第三方依赖） ================= */
function openIdb() {
  return new Promise((res, rej) => {
    if (typeof indexedDB === 'undefined') { rej(new Error('no indexedDB')); return }
    const r = indexedDB.open(IDB_NAME, 1)
    r.onupgradeneeded = () => { if (!r.result.objectStoreNames.contains(IDB_STORE)) r.result.createObjectStore(IDB_STORE) }
    r.onsuccess = () => res(r.result)
    r.onerror = () => rej(r.error)
  })
}
function idbPut(db, val) {
  return new Promise((res, rej) => {
    const tx = db.transaction(IDB_STORE, 'readwrite')
    tx.objectStore(IDB_STORE).put(val, IDB_KEY)
    tx.oncomplete = () => res()
    tx.onerror = () => rej(tx.error)
  })
}
function idbGet(db) {
  return new Promise((res, rej) => {
    const tx = db.transaction(IDB_STORE, 'readonly')
    const q = tx.objectStore(IDB_STORE).get(IDB_KEY)
    q.onsuccess = () => res(q.result)
    q.onerror = () => rej(q.error)
  })
}

/** 纯数据副本（去掉 Vue 响应式代理，IndexedDB 用结构化克隆直接存对象，不做 JSON 序列化） */
function plain() {
  try {
    return structuredClone({ contacts: store.contacts, backups: store.backups, version: VERSION })
  } catch (e) {
    return JSON.parse(JSON.stringify({ contacts: store.contacts, backups: store.backups, version: VERSION }))
  }
}

/* ================= 响应式状态 ================= */
export const store = reactive(loadLegacy())

/** 存储状态（供界面提示） */
export const persist = reactive({
  ready: false,        // 是否已完成 IndexedDB 水合
  hydrateMs: 0,
  error: '',
  lastSaved: 0,
  saveMs: 0,
  count: 0,
  bytes: 0,
  paused: false,
})

let db = null
let saveTimer = null
let pauseCount = 0

function applyDb(d) {
  const nd = normalize(d)
  store.contacts.splice(0, store.contacts.length, ...nd.contacts)
  store.backups.splice(0, store.backups.length, ...nd.backups)
}

/** 启动水合：读 IndexedDB；若是首次则把旧 localStorage 数据迁移进去 */
export async function hydrate() {
  const t0 = performance.now()
  try {
    db = await openIdb()
    const d = await idbGet(db)
    if (d && (d.contacts || d.backups)) {
      applyDb(d)
    } else {
      // 首次：迁移 localStorage 旧数据（如果有）
      const legacy = loadLegacy()
      if (legacy.contacts.length || legacy.backups.length) {
        await idbPut(db, { version: VERSION, contacts: legacy.contacts, backups: legacy.backups })
        try { localStorage.removeItem(LEGACY_KEY) } catch (e) {}
      }
    }
    persist.ready = true
    persist.hydrateMs = Math.round(performance.now() - t0)
    if (persist.error) persist.error = ''
    saveNow()
  } catch (e) {
    persist.error = '本地数据库不可用，已降级为浏览器内存存储：' + (e && e.message ? e.message : e)
    persist.ready = true
  }
}

function saveNow() {
  const t0 = performance.now()
  const payload = plain()
  persist.count = payload.contacts.length
  if (!db) {                        // 极端降级：仅内存
    persist.lastSaved = Date.now()
    return
  }
  idbPut(db, payload).then(() => {
    persist.error = ''
    persist.lastSaved = Date.now()
    persist.saveMs = Math.round(performance.now() - t0)
  }).catch(e => {
    persist.error = '数据保存失败：' + (e && e.message ? e.message : e)
    console.warn('idb save failed', e)
  })
}

/** 批量操作（如导入上万条）时暂停落盘，结束再一次性写 */
export function pauseSave() { pauseCount++; persist.paused = true }
export function resumeSave() {
  pauseCount = Math.max(0, pauseCount - 1)
  persist.paused = pauseCount > 0
  if (!pauseCount) queueSave(60)
}

const changeListeners = new Set()

/** 注册数据变更回调（本地服务版用它同步到 db 文件） */
export function onStoreChange(fn) {
  changeListeners.add(fn)
  return () => changeListeners.delete(fn)
}

function notifyChange() {
  for (const fn of changeListeners) { try { fn() } catch (e) { /* 忽略 */ } }
}

function queueSave(delay = 500) {
  notifyChange()
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => { saveTimer = null; if (!pauseCount) saveNow() }, delay)
}

watch(store, () => {
  if (pauseCount) return
  queueSave()
}, { deep: true })

/** 对象内部字段被直接修改时（markRaw 不触发深层 watch）显式排队落盘 */
export function touchSave(delay = 400) { if (!pauseCount) queueSave(delay) }

/** 立即落盘（页面隐藏/关闭前用） */
export function saveNowForce() {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null }
  saveNow()
}

/* ================= 业务操作（与旧版一致） ================= */
export function titleOf(c) {
  return (c.name && c.name.trim()) || (c.codes && c.codes[0]) || (c.phones && c.phones[0]) || '未命名'
}

/** 编码/电话 完全匹配查询（导入匹配用） */
export function findByAny(list, values, excludeId) {
  const set = new Set(normList(values))
  if (!set.size) return null
  for (const c of list) {
    if (excludeId && c.id === excludeId) continue
    for (const v of [...(c.codes || []), ...(c.phones || [])]) {
      if (set.has(v)) return c
    }
  }
  return null
}

/**
 * 添加表单查重：返回首个命中的已存在联系人 + 命中明细。
 * 为避免打字中途误命中：编码至少 3 位、电话至少 5 位才参与判定（可传 opts 覆盖）。
 */
export function findDup(codes, phones, excludeId, opts = {}) {
  const codeMin = opts.codeMin == null ? 3 : opts.codeMin
  const phoneMin = opts.phoneMin == null ? 5 : opts.phoneMin
  const long = (v, n) => String(v || '').trim().length >= n
  const cs = normList(codes).filter(v => long(v, codeMin))
  const ps = normList(phones).filter(v => long(v, phoneMin))
  for (const c of store.contacts) {
    if (excludeId && c.id === excludeId) continue
    for (const v of cs) {
      if ((c.codes || []).includes(v)) return { contact: c, kind: '编码', value: v, where: 'codes' }
    }
    for (const v of ps) {
      if ((c.phones || []).includes(v)) return { contact: c, kind: '电话', value: v, where: 'phones' }
    }
  }
  return null
}

export function addContact({ codes, phones, name, province }) {
  const now = Date.now()
  const c = markRaw({ id: uid(), codes: normList(codes), phones: normList(phones), name: (name || '').trim(), province: (province || '').trim(), createdAt: now, updatedAt: now })
  store.contacts.unshift(c)
  touchSave()
  return c
}

export function updateContact(id, { codes, phones, name, province }) {
  const c = store.contacts.find(x => x.id === id)
  if (!c) return null
  c.codes = normList(codes)
  c.phones = normList(phones)
  c.name = (name || '').trim()
  c.province = (province || '').trim()
  c.updatedAt = Date.now()
  touchSave()
  return c
}

export function removeContact(id) {
  const i = store.contacts.findIndex(x => x.id === id)
  if (i >= 0) { store.contacts.splice(i, 1); touchSave() }
}

/** 批量新增（导入用，一次性插入，避免逐条触发） */
export function addContactsBulk(list) {
  const now = Date.now()
  const arr = list.map(x => markRaw({
    id: uid(), codes: normList(x.codes), phones: normList(x.phones),
    name: (x.name || '').trim(), province: (x.province || '').trim(), createdAt: now, updatedAt: now,
  }))
  store.contacts.unshift(...arr)
  touchSave()
  return arr.length
}

/* 备份：快照很占空间，做上限与释放策略 */
const MAX_BACKUPS = 30
const BIG_DATA = 5000          // 超过这个条数，只保留最近 5 条的完整快照
const KEEP_SNAPSHOT = 5

export function addBackup({ label, fileName, path, data }) {
  const list = data || []
  const big = list.length > BIG_DATA
  const rec = {
    id: uid(),
    ts: Date.now(),
    label: label || '备份',
    fileName: fileName || '',
    path: path || '',
    count: list.length,
    data: big ? [] : JSON.parse(JSON.stringify(list)),
    noSnapshot: big,
  }
  store.backups.unshift(rec)
  while (store.backups.length > MAX_BACKUPS) store.backups.pop()
  if (big) {
    store.backups.forEach((b, i) => {
      if (i >= KEEP_SNAPSHOT && b.data && b.data.length) { b.data = []; b.noSnapshot = true }
    })
  }
  touchSave()
  return rec
}

export function removeBackup(id) {
  const i = store.backups.findIndex(x => x.id === id)
  if (i >= 0) { const r = store.backups.splice(i, 1)[0]; touchSave(); return r }
  return null
}

/** 用快照整体覆盖联系人（恢复/导入覆盖） */
export function replaceAll(list) {
  store.contacts.splice(0, store.contacts.length, ...list.map(fixContact))
  touchSave()
}

export function snapshot() {
  return JSON.parse(JSON.stringify(store.contacts))
}

export function fmtTime(ts) {
  const d = new Date(ts || Date.now())
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtStamp(ts) {
  const d = new Date(ts || Date.now())
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

/* 排障/自测钩子 */
if (typeof window !== 'undefined') {
  window.__db = {
    store, persist,
    hydrate, saveNow: saveNowForce,
    stats() {
      return {
        contacts: store.contacts.length,
        backups: store.backups.length,
        saveMs: persist.saveMs,
        hydrateMs: persist.hydrateMs,
        lastSaved: persist.lastSaved,
        ready: persist.ready,
        error: persist.error,
        bytes: (() => { try { return JSON.stringify({ c: store.contacts, b: store.backups }).length } catch (e) { return -1 } })(),
      }
    },
    bulk: addContactsBulk,
    pauseSave, resumeSave,
  }
}
