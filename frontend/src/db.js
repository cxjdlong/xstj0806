/**
 * 通讯录 · 本地数据层
 * 真离线：数据只存本机 localStorage（安卓壳用 WebViewAssetLoader，真 origin，断网可用）。
 * 结构：
 *   contacts: [{ id, codes:[], phones:[], name, createdAt, updatedAt }]
 *   backups:  [{ id, ts, label, fileName, path, count, data:[contacts...] }]
 */
import { reactive, watch } from 'vue'

const KEY = 'contacts_db_v1'
const VERSION = 1

function emptyDb() {
  return { version: VERSION, contacts: [], backups: [] }
}

function load() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return emptyDb()
    const d = JSON.parse(raw)
    const db = emptyDb()
    if (Array.isArray(d.contacts)) db.contacts = d.contacts.map(fixContact)
    if (Array.isArray(d.backups)) db.backups = d.backups
    db.version = VERSION
    return db
  } catch (e) {
    console.warn('load db failed', e)
    return emptyDb()
  }
}

function fixContact(c) {
  return {
    id: c.id || uid(),
    codes: normList(c.codes),
    phones: normList(c.phones),
    name: (c.name || '').trim(),
    createdAt: c.createdAt || Date.now(),
    updatedAt: c.updatedAt || c.createdAt || Date.now(),
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

export const store = reactive(load())

watch(store, () => {
  try {
    localStorage.setItem(KEY, JSON.stringify(store))
  } catch (e) {
    console.warn('save db failed', e)
  }
}, { deep: true })

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
 * 命中即「载入现有数据 → 用户改 → 提交确认（覆盖）」。
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

export function addContact({ codes, phones, name }) {
  const now = Date.now()
  const c = { id: uid(), codes: normList(codes), phones: normList(phones), name: (name || '').trim(), createdAt: now, updatedAt: now }
  store.contacts.unshift(c)
  return c
}

export function updateContact(id, { codes, phones, name }) {
  const c = store.contacts.find(x => x.id === id)
  if (!c) return null
  c.codes = normList(codes)
  c.phones = normList(phones)
  c.name = (name || '').trim()
  c.updatedAt = Date.now()
  return c
}

export function removeContact(id) {
  const i = store.contacts.findIndex(x => x.id === id)
  if (i >= 0) store.contacts.splice(i, 1)
}

/** 写一条备份记录（数据快照，用于恢复） */
export function addBackup({ label, fileName, path, data }) {
  const rec = {
    id: uid(),
    ts: Date.now(),
    label: label || '备份',
    fileName: fileName || '',
    path: path || '',
    count: (data || []).length,
    data: JSON.parse(JSON.stringify(data || [])),
  }
  store.backups.unshift(rec)
  return rec
}

export function removeBackup(id) {
  const i = store.backups.findIndex(x => x.id === id)
  if (i >= 0) return store.backups.splice(i, 1)[0]
  return null
}

/** 用快照整体覆盖联系人（恢复/导入覆盖） */
export function replaceAll(list) {
  store.contacts.splice(0, store.contacts.length, ...list.map(fixContact))
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
