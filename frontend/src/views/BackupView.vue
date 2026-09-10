<template>
  <div class="page backup">
    <div class="bk-head">备份与导入（Excel）</div>
    <div class="bk-sub">
      备份目录：{{ dirLabel }}；共 {{ store.contacts.length }} 条联系人
    </div>

    <div class="bk-btns">
      <button class="btn primary" @click="fullBackup">全部备份</button>
      <button class="btn warn" @click="pickImport">完全导入</button>
    </div>
    <div class="tip">
      「全部备份」= 把全部联系人备份成 Excel 存到手机（<b>显示保存路径</b>），同时记入下面的备份列表（可恢复/删除）；「完全导入」= 选 Excel 导入，<b>导入前自动备份一次</b>当前数据，按编码/电话匹配 → 已存在则更新、否则新增。
    </div>

    <div class="lastpath" v-if="lastPath">
      ✅ 文件已保存到手机：<br /><span class="mono">{{ lastPath }}</span>
    </div>

    <div class="bk-list-hd">
      备份列表（{{ store.backups.length }} 条，每页 10）
      <span class="pginfo" v-if="totalPages > 1">{{ page }} / {{ totalPages }}</span>
    </div>

    <div class="list" v-if="pageItems.length">
      <div class="item bk-item" v-for="(b, i) in pageItems" :key="b.id">
        <div class="item-main">
          <div class="nm">#{{ (page - 1) * PAGE_SIZE + i + 1 }} {{ b.label }} · {{ b.count }} 条</div>
          <div class="meta">时间：{{ fmtTime(b.ts) }}</div>
          <div class="meta" v-if="b.fileName">文件：{{ b.fileName }}</div>
          <div class="meta mono" v-if="b.path">路径：{{ b.path }}</div>
        </div>
        <div class="bk-ops">
          <button class="btn ghost sm" @click="restore(b)">恢复</button>
          <button class="btn danger sm" @click="askDelete(b)">删除</button>
        </div>
      </div>
    </div>
    <div class="empty" v-else>还没有备份，点上面「全部备份」或「完全导出」生成</div>

    <div class="pager" v-if="totalPages > 1">
      <button class="btn ghost sm" :disabled="page <= 1" @click="page--">上一页</button>
      <button class="btn ghost sm" :disabled="page >= totalPages" @click="page++">下一页</button>
    </div>

    <!-- 导入用的隐藏文件选择器（安卓壳走 onShowFileChooser） -->
    <input ref="fileEl" class="hidden-file" type="file" accept=".xlsx,.xls" @change="onFile" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import {
  store, addBackup, removeBackup, replaceAll, snapshot, fmtTime, fmtStamp, uid, normList
} from '../db.js'
import { sheetToBase64, xlsxName, parseWorkbook } from '../excel.js'
import { saveFile, deleteFile, exportDirLabel } from '../native.js'
import { toast } from '../toast.js'

const PAGE_SIZE = 10
const page = ref(1)
const lastPath = ref('')
const fileEl = ref(null)
const dirLabel = exportDirLabel()

const totalPages = computed(() => Math.max(1, Math.ceil(store.backups.length / PAGE_SIZE)))
const pageItems = computed(() => {
  const p = Math.min(page.value, totalPages.value)
  return store.backups.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE)
})

/** 导出 Excel 并落盘到手机，返回 { fileName, path } */
function writeExcel(prefix) {
  const list = snapshot()
  if (!list.length) {
    window.alert('暂无联系人数据，先添加再导出。')
    return null
  }
  const stamp = fmtStamp(Date.now())
  const fileName = xlsxName(prefix, stamp)
  const base64 = sheetToBase64(list, '通讯录')
  const path = saveFile(fileName, base64)
  if (!path) {
    window.alert('写入手机文件失败，请确认已授予存储权限后重试。')
    return null
  }
  lastPath.value = path
  return { fileName, path, list }
}

/** 全部备份：Excel 落手机 + 记一条备份（备份列表可恢复/删除），路径直接显示 */
function fullBackup() {
  const r = writeExcel('通讯录_全部备份')
  if (!r) return
  const rec = addBackup({ label: '全部备份', fileName: r.fileName, path: r.path, data: r.list })
  window.alert(`全部备份成功！\n\n文件：${r.fileName}\n条数：${r.list.length}\n路径：${r.path}\n（已记入备份列表，可恢复/删除）`)
  toast(`已备份 ${rec.count} 条`, 'ok')
  page.value = 1
}

/* ---------------- 导入 ---------------- */
function pickImport() {
  if (!fileEl.value) return
  fileEl.value.value = ''
  fileEl.value.click()
}

function onFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  if (!window.confirm(`即将导入文件：\n${f.name}\n\n导入前会自动备份一次当前数据，导入后已存在的编码/电话将被覆盖更新。确认导入？`)) return

  const before = snapshot()
  // ① 导入前先自动备份一次
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
        window.alert('导入失败：没解析到有效数据（请使用本 App 导出的 Excel 模板，表头含 姓名/编码1/电话1）。')
        return
      }
      const added = applyImport(rows)
      const msg = `导入完成！\n\n文件：${f.name}\n解析：${rows.length} 行\n新增：${added.add} 条\n更新：${added.upd} 条\n\n导入前已自动备份 ${before.length} 条${autoName ? '（' + autoName + '）' : ''}`
      window.alert(msg)
      toast(`导入完成：新增 ${added.add} · 更新 ${added.upd}`, 'ok', 4000)
      page.value = 1
    } catch (err) {
      console.warn(err)
      window.alert('导入失败：文件无法解析（' + (err && err.message ? err.message : err) + '）')
    }
  }
  reader.onerror = () => window.alert('读取文件失败，请重试。')
  reader.readAsArrayBuffer(f)
}

/** 按编码/电话精确匹配：命中则覆盖更新，未命中则新增 */
function applyImport(rows) {
  let add = 0, upd = 0
  for (const r of rows) {
    const keys = [...normList(r.codes), ...normList(r.phones)]
    let hit = null
    if (keys.length) {
      hit = store.contacts.find(c =>
        [...(c.codes || []), ...(c.phones || [])].some(v => keys.includes(String(v)))
      ) || null
    }
    if (hit) {
      hit.codes = normList([...(hit.codes || []), ...r.codes])
      hit.phones = normList([...(hit.phones || []), ...r.phones])
      if (r.name) hit.name = r.name
      hit.updatedAt = Date.now()
      upd++
    } else {
      const now = Date.now()
      store.contacts.unshift({
        id: uid(), codes: normList(r.codes), phones: normList(r.phones),
        name: r.name || '', createdAt: now, updatedAt: now,
      })
      add++
    }
  }
  return { add, upd }
}

/* ---------------- 备份列表操作 ---------------- */
function restore(b) {
  const msg = `确认用该备份恢复？\n\n类型：${b.label}\n时间：${fmtTime(b.ts)}\n条数：${b.count}\n\n注意：恢复会【整体覆盖】当前 ${store.contacts.length} 条联系人数据。`
  if (!window.confirm(msg)) return
  replaceAll(b.data)
  toast(`已恢复 ${b.count} 条联系人`, 'ok')
}

function askDelete(b) {
  const msg = `确认删除该备份？\n\n类型：${b.label}\n时间：${fmtTime(b.ts)}\n条数：${b.count}\n${b.path ? '文件：' + b.path + '\n' : ''}删除后不可恢复。`
  if (!window.confirm(msg)) return
  if (b.path) deleteFile(b.path)
  removeBackup(b.id)
  toast('备份已删除', 'ok')
}
</script>
