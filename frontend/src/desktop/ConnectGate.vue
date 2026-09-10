<template>
  <div class="dl-gate-mask" v-if="show">
    <div class="dl-gate">
      <div class="dl-gate-ico">🗄</div>
      <h3>连接数据文件夹</h3>
      <div class="dl-gate-txt">
        数据会保存到 <b>本 html 所在文件夹</b> 里的
        <code>db/{{ dfState.fileName }}</code>（SQLite 数据库文件）。<br />
        <b>换浏览器、清缓存、换电脑 —— 只要这个文件夹还在，数据就在。</b>
        <div class="dl-gate-sub">
          浏览器安全策略要求网页不能自己读写磁盘，所以需要你点一次选择文件夹（Chrome/Edge 会记住位置，之后自动读写；换浏览器需再点一次）。
        </div>
      </div>
      <div class="dl-gate-ops">
        <button v-if="dfState.needGrant" class="dl-btn primary lg" :disabled="dfState.busy" @click="grant">授权并连接（上次的文件夹：{{ dfState.dirName }}）</button>
        <button class="dl-btn lg" :class="{ primary: !dfState.needGrant }" :disabled="dfState.busy" @click="pick">
          {{ dfState.busy ? '处理中…' : '选择 html 所在文件夹' }}
        </button>
        <div class="dl-gate-row">
          <button class="dl-btn ghost" @click="importDb">导入已有数据文件(.db)</button>
          <button class="dl-btn ghost" @click="skip">本次先用浏览器存储</button>
        </div>
      </div>
      <div class="dl-gate-warn" v-if="!dfState.supported">
        ⚠ 当前浏览器不支持直接读写文件（需 Chrome / Edge）。可以先「本次先用浏览器存储」，或换浏览器打开。
      </div>
    </div>
    <input ref="dbEl" class="dl-hidden" type="file" accept=".db,.sqlite,.sqlite3" @change="onDbFile" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { dfState, pickFolder, grantAndConnect, importDbBytes } from '../dataFile.js'
import { toast } from '../toast.js'

const SKIP_KEY = 'contacts_gate_skipped'      // sessionStorage：只在本次会话内记住
const skipped = ref(sessionStorage.getItem(SKIP_KEY) === '1')
const dbEl = ref(null)

const show = computed(() => !dfState.connected && !skipped.value)

async function pick() {
  const ok = await pickFolder()
  if (ok) {
    toast('已连接：数据保存在 ' + dfState.dirName + '/db/' + dfState.fileName, 'ok', 4200)
  }
}

async function grant() {
  const ok = await grantAndConnect()
  if (ok) toast('已连接，数据已从 db 文件读回', 'ok', 3600)
}

function importDb() { if (dbEl.value) { dbEl.value.value = ''; dbEl.value.click() } }

async function onDbFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  try {
    const buf = new Uint8Array(await f.arrayBuffer())
    const n = await importDbBytes(buf)
    skipped.value = true
    sessionStorage.setItem(SKIP_KEY, '1')
    toast(`已从数据文件载入 ${n} 条联系人`, 'ok', 4200)
    window.alert(`导入成功：${n} 条联系人\n\n当前数据已载入。要让后续改动也自动存进 db 文件，请再点「选择 html 所在文件夹」。`)
  } catch (err) {
    window.alert('导入失败：' + (err && err.message ? err.message : err))
  }
}

function skip() {
  skipped.value = true
  sessionStorage.setItem(SKIP_KEY, '1')
  toast('本次先用浏览器存储：换浏览器会看不到数据，记得「全部备份」', 'warn', 5000)
}
</script>
