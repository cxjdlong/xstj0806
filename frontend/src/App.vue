<template>
  <div class="app">
    <header class="tabs">
      <button
        v-for="t in tabs"
        :key="t.k"
        class="tab"
        :class="{ on: tab === t.k }"
        @click="go(t.k)"
      >{{ t.n }}</button>
    </header>

    <main class="body">
      <ContactsView
        v-if="tab === 'contacts'"
        @add="go('edit', null)"
        @edit="id => go('edit', id)"
      />
      <EditView
        v-else-if="tab === 'edit'"
        :key="'edit-' + editKey"
        :edit-id="editId"
        @done="onDone"
      />
      <BackupView v-else />
    </main>

    <div class="toasts">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind">{{ t.msg }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ContactsView from './views/ContactsView.vue'
import EditView from './views/EditView.vue'
import BackupView from './views/BackupView.vue'
import { toasts } from './toast.js'
import { saveNowForce } from './db.js'

const tabs = [
  { k: 'contacts', n: '通讯录' },
  { k: 'edit', n: '添加' },
  { k: 'backup', n: '备份' },
]

const tab = ref('contacts')
const editId = ref(null)
const editKey = ref(0)

function go(k, id = null) {
  if (k === 'edit') {
    editId.value = id || null
    editKey.value++          // 每次进入都重建表单（清空/载入目标数据）
  }
  tab.value = k
}

function onDone() {
  tab.value = 'contacts'
}

// 切后台/关闭前把待写入的数据落盘（防抖期间关掉页面也不会丢）
document.addEventListener('visibilitychange', () => { if (document.hidden) saveNowForce() })
window.addEventListener('beforeunload', () => saveNowForce())
</script>
