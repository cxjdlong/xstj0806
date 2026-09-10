/**
 * 本地服务版同步：当页面是通过「通讯录本地版」小程序（http://127.0.0.1:19118）打开时，
 * 数据直接读写程序目录下的 SQLite 文件 db/contacts.db —— 无任何授权点击、任何浏览器都读同一份。
 *
 * 判定方式：http(s) 打开 且 /api/health 有响应 → 进入 server 模式；
 * 否则（file:// 双击 html）继续用原来的浏览器存储 + 可选文件夹。
 */
import { reactive } from 'vue'
import { store, onStoreChange } from './db.js'
import { toast } from './toast.js'

export const srv = reactive({
  mode: 'local',      // 'local' | 'server'
  ready: false,
  saving: false,
  dbPath: '',
  backupDir: '',
  lastSaved: 0,
  saveMs: 0,
  error: '',
})

let saveTimer = null
let suppress = false

function applyRemote(data) {
  suppress = true
  try {
    if (Array.isArray(data.contacts)) store.contacts.splice(0, store.contacts.length, ...data.contacts)
    if (Array.isArray(data.backups)) store.backups.splice(0, store.backups.length, ...data.backups)
  } finally {
    setTimeout(() => { suppress = false }, 150)
  }
}

function snapshotPayload() {
  return {
    contacts: store.contacts.map(c => ({
      id: c.id, name: c.name || '', province: c.province || '', codes: c.codes || [], phones: c.phones || [],
      createdAt: c.createdAt || 0, updatedAt: c.updatedAt || 0,
    })),
    backups: store.backups.map(b => ({
      id: b.id, ts: b.ts || 0, label: b.label || '', fileName: b.fileName || '',
      path: b.path || '', count: b.count || 0,
    })),
  }
}

async function push() {
  if (srv.mode !== 'server' || suppress) return
  const t0 = performance.now()
  srv.saving = true
  try {
    const r = await fetch('/api/db', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(snapshotPayload()),
    })
    const j = await r.json()
    if (!j || !j.ok) throw new Error(j && j.error ? j.error : '保存失败')
    srv.lastSaved = Date.now()
    srv.saveMs = Math.round(performance.now() - t0)
    srv.error = ''
  } catch (e) {
    srv.error = String(e && e.message ? e.message : e)
    toast('保存到 db/contacts.db 失败：' + srv.error, 'warn', 5000)
  } finally {
    srv.saving = false
  }
}

function queuePush(delay = 700) {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => { saveTimer = null; push() }, delay)
}

/** 启动时尝试进入本地服务模式；返回是否成功 */
export async function initServerSync() {
  if (typeof location === 'undefined') return false
  if (!/^https?:$/.test(location.protocol)) return false
  try {
    const r = await fetch('/api/health', { cache: 'no-store' })
    if (!r.ok) return false
    const j = await r.json()
    if (!j || !j.ok) return false

    const data = await (await fetch('/api/db', { cache: 'no-store' })).json()
    applyRemote(data)
    srv.mode = 'server'
    srv.ready = true
    try {
      const info = await (await fetch('/api/info', { cache: 'no-store' })).json()
      srv.dbPath = info.dbPath || ''
      srv.backupDir = info.backupDir || ''
    } catch (e) { /* 忽略 */ }
    onStoreChange(() => { if (!suppress) queuePush() })
    window.addEventListener('beforeunload', () => { if (saveTimer) { clearTimeout(saveTimer); push() } })
    return true
  } catch (e) {
    return false
  }
}
