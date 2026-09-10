<template>
  <div class="dl-app">
    <!-- 顶栏 -->
    <header class="dl-top">
      <div class="dl-brand"><span class="dl-logo">☰</span> 通讯录管理</div>
      <div class="dl-top-right">
        <span class="dl-chip" :class="dfState.connected ? 'ok' : 'warn'" :title="dfState.connected ? '数据自动写入 html 同目录的数据文件' : '数据存在浏览器本地存储'">
          {{ dfState.connected ? ('📁 ' + dfState.dirName + '/' + dfState.fileName) : '🗄 本地数据库(IndexedDB)' }}
        </span>
        <span class="dl-stat">共 <b>{{ store.contacts.length }}</b> 位联系人</span>
        <span class="dl-stat">备份 <b>{{ store.backups.length }}</b> 条</span>
      </div>
    </header>

    <div class="dl-body">
      <!-- 左侧导航 -->
      <nav class="dl-side">
        <button v-for="t in tabs" :key="t.k" class="dl-nav" :class="{ on: tab === t.k }" @click="go(t.k)">
          <span class="dl-nav-ico">{{ t.ico }}</span>{{ t.n }}
        </button>
        <div class="dl-side-foot">
          <div>{{ dfState.connected ? '数据：db/contacts.db' : '数据：本地数据库' }}</div>
          <div>建议定期「全部备份」</div>
          <div class="dl-ver">版本 v{{ APP_VERSION }} · {{ BUILD_TIME }}</div>
        </div>
      </nav>

      <!-- 内容区 -->
      <main class="dl-main">
        <DataFileBanner />
        <DContacts v-if="tab === 'contacts'" @add="go('edit', null)" @edit="id => go('edit', id)" />
        <DEdit v-else-if="tab === 'edit'" :key="'e' + editKey" :edit-id="editId" @done="onDone" />
        <DBackup v-else />
      </main>
    </div>

    <div class="dl-toasts">
      <div v-for="t in toasts" :key="t.id" class="dl-toast" :class="t.kind">{{ t.msg }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import DContacts from './DContacts.vue'
import DEdit from './DEdit.vue'
import DBackup from './DBackup.vue'
import DataFileBanner from './DataFileBanner.vue'
import { store, saveNowForce } from '../db.js'
import { toasts } from '../toast.js'
import { dfState, restoreFromStore, shouldWrite, writeNow } from '../dataFile.js'
import { APP_VERSION, BUILD_TIME } from '../version.js'

const tabs = [
  { k: 'contacts', n: '通讯录', ico: '📇' },
  { k: 'edit', n: '添加 / 修改', ico: '➕' },
  { k: 'backup', n: '备份 / 导入', ico: '💾' },
]

const tab = ref('contacts')
const editId = ref(null)
const editKey = ref(0)

function go(k, id = null) {
  if (k === 'edit') {
    editId.value = id || null
    editKey.value++
  }
  tab.value = k
}

function onDone() {
  tab.value = 'contacts'
}

/* ---- 数据文件：启动时尝试重连（浏览器若要求授权则页面提示点一下） ---- */
onMounted(() => { restoreFromStore() })

/* ---- 任何改动都自动落到数据文件（防抖 600ms） ---- */
let saveTimer = null
watch(store, () => {
  if (!shouldWrite()) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => { saveTimer = null; writeNow(true) }, 600)
}, { deep: true })

/* ---- 切到后台/关闭前尽量补写一次 ---- */
function flush() {
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null }
  saveNowForce()                       // 先保证浏览器本地存储落盘
  if (shouldWrite()) writeNow(true)    // 再写数据文件
}
document.addEventListener('visibilitychange', () => { if (document.hidden) flush() })
window.addEventListener('beforeunload', flush)
</script>
