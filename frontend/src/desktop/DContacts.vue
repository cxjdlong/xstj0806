<template>
  <div class="dl-view">
    <div class="dl-view-hd">
      <div>
        <h2>通讯录</h2>
        <div class="dl-sub">查询支持 编码 / 电话 / 姓名，包含即命中；点姓名或电话可直接拨打。</div>
      </div>
      <button class="dl-btn primary" @click="$emit('add')">＋ 新增联系人</button>
    </div>

    <div class="dl-toolbar">
      <input v-model="kw" class="dl-input" type="search" placeholder="输入编码 / 电话 / 姓名（包含即查询）"
             @keyup.enter="doSearch" />
      <button class="dl-btn" @click="doSearch">查询</button>
      <button class="dl-btn ghost" @click="resetSearch">重置</button>
      <span class="dl-hits" v-if="searched">命中 <b>{{ list.length }}</b> 条</span>
      <span class="dl-hits" v-else>每页 {{ PAGE_SIZE }} 条</span>
    </div>

    <div class="dl-card">
      <table class="dl-table">
        <thead>
          <tr>
            <th style="width:56px">#</th>
            <th style="width:150px">姓名</th>
            <th>编码</th>
            <th>电话</th>
            <th style="width:170px">更新时间</th>
            <th style="width:150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in pageItems" :key="c.id" @dblclick="$emit('edit', c.id)">
            <td class="dl-muted">{{ (page - 1) * PAGE_SIZE + i + 1 }}</td>
            <td><span class="dl-name" @click="$emit('edit', c.id)" title="点击编辑">{{ titleOf(c) }}</span></td>
            <td>
              <span v-if="!c.codes.length" class="dl-muted">—</span>
              <span v-for="v in c.codes" :key="'c' + v" class="dl-tag code">{{ v }}</span>
            </td>
            <td>
              <span v-if="!c.phones.length" class="dl-muted">—</span>
              <a v-for="v in c.phones" :key="'p' + v" class="dl-tag phone" @click.prevent="call(v)" title="点击拨打">📞 {{ v }}</a>
            </td>
            <td class="dl-muted">{{ fmtTime(c.updatedAt) }}</td>
            <td>
              <button class="dl-btn xs" @click="$emit('edit', c.id)">编辑</button>
              <button class="dl-btn xs danger" @click="askDelete(c)">删除</button>
            </td>
          </tr>
          <tr v-if="!pageItems.length">
            <td colspan="6" class="dl-empty">
              {{ searched ? '没有匹配的通讯录' : '还没有联系人，点右上角「＋ 新增联系人」开始' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="dl-pager" v-if="totalPages > 1">
      <button class="dl-btn xs" :disabled="page <= 1" @click="page = 1">首页</button>
      <button class="dl-btn xs" :disabled="page <= 1" @click="page--">上一页</button>
      <span class="dl-muted">第 {{ page }} / {{ totalPages }} 页 · 共 {{ list.length }} 条 · 每页 {{ PAGE_SIZE }} 条</span>
      <button class="dl-btn xs" :disabled="page >= totalPages" @click="page++">下一页</button>
      <button class="dl-btn xs" :disabled="page >= totalPages" @click="page = totalPages">末页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { store, titleOf, removeContact, fmtTime } from '../db.js'
import { dial } from '../native.js'
import { toast } from '../toast.js'

const PAGE_SIZE = 8
defineEmits(['add', 'edit'])

const kw = ref('')
const kwUsed = ref('')
const searched = ref(false)
const page = ref(1)

const result = computed(() => {
  if (!searched.value) return store.contacts
  const k = kwUsed.value.trim().toLowerCase()
  if (!k) return store.contacts
  return store.contacts.filter(c =>
    [...(c.codes || []), ...(c.phones || []), c.name || '']
      .some(v => String(v).toLowerCase().includes(k))
  )
})

const list = computed(() => result.value)
const totalPages = computed(() => Math.max(1, Math.ceil(list.value.length / PAGE_SIZE)))
const pageItems = computed(() => {
  const p = Math.min(page.value, totalPages.value)
  return list.value.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE)
})

watch(list, () => { page.value = 1 })

function doSearch() {
  kwUsed.value = kw.value
  searched.value = true
  page.value = 1
  if (kw.value.trim()) toast(`命中 ${result.value.length} 条`, 'info', 1600)
}

function resetSearch() {
  kw.value = ''
  kwUsed.value = ''
  searched.value = false
  page.value = 1
}

function call(p) {
  if (!p) return
  if (!window.confirm(`拨打 ${p}？`)) return
  if (!dial(p)) toast('无法拨打：号码无效', 'warn')
}

function askDelete(c) {
  const msg = `确认删除「${titleOf(c)}」？\n编码：${(c.codes || []).join('、') || '无'}\n电话：${(c.phones || []).join('、') || '无'}\n创建：${fmtTime(c.createdAt)}\n删除后不可恢复。`
  if (!window.confirm(msg)) return
  removeContact(c.id)
  toast('已删除', 'ok')
}
</script>
