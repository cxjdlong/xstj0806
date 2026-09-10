<template>
  <div class="dl-app">
    <!-- 顶栏 -->
    <header class="dl-top">
      <div class="dl-brand"><span class="dl-logo">☰</span> 通讯录管理</div>
      <div class="dl-top-right">
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
          <div>数据存本机浏览器</div>
          <div>建议定期「全部备份」</div>
        </div>
      </nav>

      <!-- 内容区 -->
      <main class="dl-main">
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
import { ref } from 'vue'
import DContacts from './DContacts.vue'
import DEdit from './DEdit.vue'
import DBackup from './DBackup.vue'
import { store } from '../db.js'
import { toasts } from '../toast.js'

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
</script>
