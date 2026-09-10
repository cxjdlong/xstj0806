<template>
  <div class="dl-view">
    <div class="dl-view-hd">
      <div>
        <h2>备份 / 导入</h2>
        <div class="dl-sub">
          备份文件位置：{{ dirLabel }}（导出后会显示完整路径）；共 {{ store.contacts.length }} 位联系人。
        </div>
      </div>
    </div>

    <div class="dl-alert danger" v-if="persist.error">⚠ {{ persist.error }}</div>

    <DataFilePanel />

    <div class="dl-cards">
      <div class="dl-panel">
        <div class="dl-panel-hd">💾 全部备份</div>
        <div class="dl-panel-bd">
          把全部联系人备份成 Excel 存到本机（页面显示保存路径），同时记入下方备份列表，可随时恢复。
        </div>
        <div class="dl-panel-ft"><button class="dl-btn primary" :disabled="!!busy" @click="fullBackup">{{ busy || '立即全部备份' }}</button></div>
      </div>
      <div class="dl-panel">
        <div class="dl-panel-hd">📥 完全导入</div>
        <div class="dl-panel-bd">
          选 Excel 导入；<b>导入前自动备份一次</b>当前数据。按编码/电话完全一致匹配 → 已存在则更新，否则新增。
        </div>
        <div class="dl-panel-ft"><button class="dl-btn warn" :disabled="!!busy" @click="pickImport">选择 Excel 导入</button></div>
      </div>
    </div>

    <div class="dl-alert ok" v-if="lastPath">
      ✅ 文件已保存到{{ targetLabel }}：<span class="mono">{{ lastPath }}</span>
    </div>

    <div class="dl-card">
      <div class="dl-card-hd">备份列表（{{ store.backups.length }} 条 · 每页 {{ PAGE_SIZE }} 条）</div>
      <table class="dl-table">
        <thead>
          <tr>
            <th style="width:56px">#</th>
            <th style="width:180px">时间</th>
            <th style="width:150px">类型</th>
            <th style="width:90px">条数</th>
            <th>文件名 / 路径</th>
            <th style="width:150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(b, i) in pageItems" :key="b.id">
            <td class="dl-muted">{{ (page - 1) * PAGE_SIZE + i + 1 }}</td>
            <td>{{ fmtTime(b.ts) }}</td>
            <td>{{ b.label }}</td>
            <td>{{ b.count }}</td>
            <td>
              <div v-if="b.fileName">{{ b.fileName }}</div>
              <div class="dl-muted mono" v-if="b.path">{{ b.path }}</div>
              <span v-if="!b.fileName" class="dl-muted">—</span>
            </td>
            <td>
              <button class="dl-btn xs" @click="restore(b)">恢复</button>
              <button class="dl-btn xs danger" @click="askDelete(b)">删除</button>
            </td>
          </tr>
          <tr v-if="!pageItems.length">
            <td colspan="6" class="dl-empty">还没有备份，点上面「立即全部备份」生成</td>
          </tr>
        </tbody>
      </table>
      <div class="dl-pager" v-if="totalPages > 1">
        <button class="dl-btn xs" :disabled="page <= 1" @click="page = 1">首页</button>
        <button class="dl-btn xs" :disabled="page <= 1" @click="page--">上一页</button>
        <span class="dl-muted">第 {{ page }} / {{ totalPages }} 页</span>
        <button class="dl-btn xs" :disabled="page >= totalPages" @click="page++">下一页</button>
        <button class="dl-btn xs" :disabled="page >= totalPages" @click="page = totalPages">末页</button>
      </div>
    </div>

    <input ref="fileEl" class="dl-hidden" type="file" accept=".xlsx,.xls" @change="onFile" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { store, addBackup, removeBackup, replaceAll, snapshot, fmtTime, fmtStamp, normList, pauseSave, resumeSave, persist, addContactsBulk, touchSave } from '../db.js'
import { sheetToBase64, xlsxName, parseWorkbook } from '../excel.js'
import { saveFile, deleteFile, exportDirLabel, saveTargetLabel, isApp } from '../native.js'
import { toast } from '../toast.js'
import DataFilePanel from './DataFilePanel.vue'

const PAGE_SIZE = 10
const page = ref(1)
const busy = ref('')
const lastPath = ref('')
const fileEl = ref(null)
const dirLabel = exportDirLabel()
const targetLabel = saveTargetLabel()

const totalPages = computed(() => Math.max(1, Math.ceil(store.backups.length / PAGE_SIZE)))
const pageItems = computed(() => {
  const p = Math.min(page.value, totalPages.value)
  return store.backups.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE)
})

function writeExcel(prefix) {
  const list = snapshot()
  if (!list.length) {
    window.alert('暂无联系人数据，先添加再导出。')
    return null
  }
  const fileName = xlsxName(prefix, fmtStamp(Date.now()))
  const path = saveFile(fileName, sheetToBase64(list, '通讯录'))
  if (!path) {
    window.alert(isApp() ? '写入文件失败，请确认已授予存储权限后重试。' : '保存失败，请允许浏览器下载后重试。')
    return null
  }
  lastPath.value = path
  return { fileName, path, list }
}

function fullBackup() {
  if (busy.value) return
  busy.value = '正在生成 Excel…'
  try { return doFullBackup() } finally { busy.value = '' }
}
function doFullBackup() {
  const r = writeExcel('通讯录_全部备份')
  if (!r) return
  const rec = addBackup({ label: '全部备份', fileName: r.fileName, path: r.path, data: r.list })
  window.alert(`全部备份成功！\n\n文件：${r.fileName}\n条数：${r.list.length}\n路径：${r.path}\n（已记入备份列表，可恢复/删除）`)
  toast(`已备份 ${rec.count} 条`, 'ok')
  page.value = 1
}

function pickImport() {
  if (!fileEl.value) return
  fileEl.value.value = ''
  fileEl.value.click()
}

function onFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  busy.value = '正在导入…'
  if (!window.confirm(`即将导入文件：\n${f.name}\n\n导入前会自动备份一次当前数据，导入后已存在的编码/电话将被覆盖更新。确认导入？`)) return

  const before = snapshot()
  let autoName = ''
  if (before.length) {
    const r = writeExcel('通讯录_导入前自动备份')
    autoName = r ? r.fileName : ''
    addBackup({ label: '导入前自动备份', fileName: autoName, path: r ? r.path : '', data: before })
  }

  const reader = new FileReader()
  reader.onload = ev => {
    try {
      const rows = parseWorkbook(ev.target.result)
      if (!rows.length) {
        window.alert('导入失败：没解析到有效数据（请使用本程序导出的 Excel 模板，表头含 姓名/编码1/电话1）。')
        return
      }
      pauseSave()
      let added
      try { added = applyImport(rows) } finally { resumeSave(); touchSave(80) }
      window.alert(`导入完成！\n\n文件：${f.name}\n解析：${rows.length} 行\n新增：${added.add} 条\n更新：${added.upd} 条\n\n导入前已自动备份 ${before.length} 条${autoName ? '（' + autoName + '）' : ''}`)
      toast(`导入完成：新增 ${added.add} · 更新 ${added.upd}`, 'ok', 4000)
      page.value = 1
      busy.value = ''
    } catch (err) {
      console.warn(err)
      window.alert('导入失败：文件无法解析（' + (err && err.message ? err.message : err) + '）')
    }
  }
  reader.onerror = () => { busy.value = ''; window.alert('读取文件失败，请重试。') }
  reader.readAsArrayBuffer(f)
}

function applyImport(rows) {
  // 大文件导入：先建索引（编码/电话 → 联系人），再批量插入，避免 O(n*m) 与逐条响应式更新
  const index = new Map()
  for (const c of store.contacts) {
    for (const v of [...(c.codes || []), ...(c.phones || [])]) index.set(String(v), c)
  }
  let upd = 0
  const fresh = []
  for (const r of rows) {
    const keys = [...normList(r.codes), ...normList(r.phones)]
    let hit = null
    for (const k of keys) { if (index.has(k)) { hit = index.get(k); break } }
    if (hit) {
      hit.codes = normList([...(hit.codes || []), ...r.codes])
      hit.phones = normList([...(hit.phones || []), ...r.phones])
      if (r.name) hit.name = r.name
      hit.updatedAt = Date.now()
      for (const v of [...hit.codes, ...hit.phones]) index.set(String(v), hit)
      upd++
    } else {
      fresh.push(r)
    }
  }
  const add = addContactsBulk(fresh)
  return { add, upd }
}

function restore(b) {
  if (b.noSnapshot || !b.data || !b.data.length) {
    window.alert(`该备份只保留了记录（数据量大时旧快照会被释放）。\n\n请用该备份导出的 Excel 文件走「完全导入」来恢复：\n${b.fileName || '（未记录文件名）'}`)
    return
  }
  if (!window.confirm(`确认用该备份恢复？\n\n类型：${b.label}\n时间：${fmtTime(b.ts)}\n条数：${b.count}\n\n注意：恢复会【整体覆盖】当前 ${store.contacts.length} 条联系人数据。`)) return
  replaceAll(b.data)
  toast(`已恢复 ${b.count} 条联系人`, 'ok')
}

function askDelete(b) {
  if (!window.confirm(`确认删除该备份？\n\n类型：${b.label}\n时间：${fmtTime(b.ts)}\n条数：${b.count}\n${b.path ? '文件：' + b.path + '\n' : ''}删除后不可恢复。`)) return
  if (b.path) deleteFile(b.path)
  removeBackup(b.id)
  toast('备份已删除', 'ok')
}
</script>
