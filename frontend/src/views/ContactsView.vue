<template>
  <div class="page contacts">
    <!-- 查询：编码或电话 包含即命中，结果直接在本页展示 -->
    <div class="searchbar">
      <input
        v-model="kw"
        class="search-input"
        type="search"
        placeholder="输入编码或电话（包含即查询）"
        @keyup.enter="doSearch"
      />
      <button class="btn primary" @click="doSearch">查询</button>
      <button v-if="searched" class="btn ghost" @click="resetSearch">重置</button>
    </div>

    <div class="hint">
      点某条可修改；点绿色电话号可直接拨打；每页 7 条。
    </div>

    <div class="hint" v-if="searched">
      查询「{{ kwUsed }}」命中 <b>{{ result.length }}</b> 条（{{ result.length ? '点某条可修改' : '无结果' }}）
    </div>

    <div class="list" v-if="pageItems.length">
      <div class="item" v-for="c in pageItems" :key="c.id" @click="$emit('edit', c.id)">
        <div class="item-main">
          <div class="nm">{{ titleOf(c) }}</div>
          <div class="chips">
            <span class="chip code" v-for="v in c.codes" :key="'c' + v">编码 {{ v }}</span>
            <span class="chip phone" v-for="v in c.phones" :key="'p' + v" @click.stop="call(v)" title="点击拨打">📞 {{ v }}</span>
            <span class="chip none" v-if="!c.codes.length && !c.phones.length">无编码/电话</span>
          </div>
        </div>
        <button class="btn danger sm" @click.stop="askDelete(c)">删除</button>
      </div>
    </div>
    <div class="empty" v-else>
      {{ searched ? '没有匹配的通讯录' : '通讯录还是空的，点下面「添加」新增' }}
    </div>

    <div class="pager" v-if="totalPages > 1">
      <button class="btn ghost sm" :disabled="page <= 1" @click="page--">上一页</button>
      <span class="pginfo">{{ page }} / {{ totalPages }}（共 {{ list.length }} 条，每页 {{ PAGE_SIZE }}）</span>
      <button class="btn ghost sm" :disabled="page >= totalPages" @click="page++">下一页</button>
    </div>

    <!-- 底部中间添加按钮：位置固定不变，不被列表遮挡 -->
    <div class="fab-wrap">
      <button class="fab" @click="$emit('add')">＋ 添加</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { store, titleOf, removeContact, fmtTime } from '../db.js'
import { dial } from '../native.js'
import { toast } from '../toast.js'

const PAGE_SIZE = 7
const emit = defineEmits(['add', 'edit'])

const kw = ref('')
const kwUsed = ref('')
const searched = ref(false)
const page = ref(1)

/** 编码或电话 只要「包含」关键字就命中 */
const result = computed(() => {
  if (!searched.value) return store.contacts
  const k = kwUsed.value.trim().toLowerCase()
  if (!k) return store.contacts
  return store.contacts.filter(c =>
    [...(c.codes || []), ...(c.phones || [])].some(v => String(v).toLowerCase().includes(k))
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
  if (kw.value.trim()) toast(`已查询，命中 ${result.value.length} 条`, 'info', 1600)
}

function resetSearch() {
  kw.value = ''
  kwUsed.value = ''
  searched.value = false
  page.value = 1
}

/** 点击电话号码直接拨打（二次确认，避免误触） */
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
