<template>
  <div class="dl-card dl-panel" style="margin-bottom:16px">
    <div class="dl-card-hd">📁 数据库文件（让数据跟着 html 走）</div>
    <div style="padding:14px 16px">
      <div class="dl-df-row">
        <span class="dl-df-dot" :class="dfState.connected ? 'on' : 'off'"></span>
        <template v-if="dfState.connected">
          已连接：<b>{{ dfState.dirName }}/{{ dfState.fileName }}</b>
          <span class="dl-muted" v-if="dfState.lastSaved">（最近写入 {{ fmtTime(dfState.lastSaved) }}）</span>
        </template>
        <template v-else-if="dfState.needGrant">
          上次的文件夹已记住（<b>{{ dfState.dirName }}</b>），但浏览器本次会话需要你再点一下授权。
        </template>
        <template v-else>
          未连接 —— 数据目前存在浏览器本地存储里
        </template>
      </div>
      <div class="dl-df-desc">
        连接后每次改动都会自动写入这个 <b>SQLite 数据库文件</b>（<code>{{ dfState.fileName }}</code>），可用 DB Browser for SQLite 等工具直接打开查看；
        下次双击 html 打开会自动读回，<b>文件夹拷到别的电脑数据一起带走</b>。
        （浏览器安全策略要求首次手选一次文件夹；Chrome/Edge 会记住位置。）
      </div>
      <div class="dl-df-note">
        本页面所在文件夹：<b>{{ dirNameNow }}</b>
        <template v-if="dfState.connected && dfState.dirName && dfState.dirName !== dirNameNow">
          　⚠ 与已连接的文件夹「<b>{{ dfState.dirName }}</b>」不同 —— 数据正写在那个文件夹里，不是当前这个。
        </template>
      </div>
      <div class="dl-df-ops">
        <button v-if="!dfState.connected && dfState.needGrant" class="dl-btn primary" :disabled="dfState.busy" @click="grant">授权并连接</button>
        <button class="dl-btn" :class="{ primary: !dfState.connected }" :disabled="dfState.busy" @click="pick">
          {{ dfState.connected ? '更换文件夹' : '选择 html 所在文件夹' }}
        </button>
        <button class="dl-btn" v-if="dfState.connected" @click="saveNow">立即写入</button>
        <button class="dl-btn ghost" v-if="dfState.connected" @click="off">断开（改回浏览器存储）</button>
        <button class="dl-btn ghost" @click="diagnose">写入自检（排查没生成文件）</button>
        <button class="dl-btn" @click="exportDb">导出数据文件(.db)</button>
        <button class="dl-btn" @click="pickDb">导入数据文件(.db)</button>
      </div>
      <div class="dl-df-warn" v-if="!dfState.supported">
        ⚠ 当前浏览器不支持直接读写文件（需 Chrome / Edge）。数据会继续存在浏览器存储里。
      </div>
      <div class="dl-df-warn" v-if="dfState.lastError">⚠ 上次写入失败：{{ dfState.lastError }}</div>
    </div>
  </div>
  <input ref="dbEl" class="dl-hidden" type="file" accept=".db,.sqlite,.sqlite3" @change="onDbFile" />
</template>

<script setup>
import { ref } from 'vue'
import { dfState, pickFolder, grantAndConnect, writeNow, disconnect, selfTest, currentDirName, storeFromBytes, dumpBytesToBlob, importDbBytes } from '../dataFile.js'
import { fmtTime } from '../db.js'

const dirNameNow = currentDirName()
import { toast } from '../toast.js'

async function pick() {
  const ok = await pickFolder()
  if (ok) toast('已连接数据库文件', 'ok')
}
async function grant() {
  const ok = await grantAndConnect()
  toast(ok ? '已连接数据库文件' : '授权未完成', ok ? 'ok' : 'warn')
}
async function saveNow() {
  const ok = await writeNow(false)
  if (ok) toast('已写入数据库文件', 'ok')
}
const dbEl = ref(null)

/** 导出 .db：任何浏览器都能用（走下载，不需要文件夹授权） */
async function exportDb() {
  try {
    const blob = await dumpBytesToBlob()
    const stamp = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '').slice(0, 14)
    const name = `contacts_${stamp}.db`
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
    toast('已导出 ' + name + '（换浏览器/换电脑时用它一步还原）', 'ok', 4200)
  } catch (e) {
    window.alert('导出失败：' + e.message)
  }
}

function pickDb() { if (dbEl.value) { dbEl.value.value = ''; dbEl.value.click() } }

async function onDbFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  if (!window.confirm(`用数据文件「${f.name}」覆盖当前数据？\n\n（当前 ${dfState.state && dfState.state.contacts ? '' : ''}条数会以文件为准）`)) return
  try {
    const buf = new Uint8Array(await f.arrayBuffer())
    const n = await importDbBytes(buf)
    toast(`已从数据文件载入 ${n} 条联系人`, 'ok', 4200)
    window.alert(`导入成功：${n} 条联系人\n（数据文件已是当前数据源，后续改动会自动保存）`)
  } catch (err) {
    window.alert('导入失败：' + (err && err.message ? err.message : err))
  }
}

function diagnose() {
  selfTest().then(r => window.alert('自检结果：\n\n' + r)).catch(e => window.alert('自检异常：' + e.message))
}

function off() {
  if (!window.confirm('确认断开？断开后数据只存在浏览器里（不会删除已生成的数据库文件）。')) return
  disconnect()
  toast('已断开，改回浏览器存储', 'info')
}
</script>
