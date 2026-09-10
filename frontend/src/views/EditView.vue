<template>
  <div class="page edit">
    <div class="edit-title">
      {{ editingId ? '修改通讯录' : '添加通讯录' }}
      <span class="tag-mod" v-if="editingId">修改模式</span>
    </div>

    <!-- 命中已存在：标红提示 + 已载入现有数据 -->
    <div class="dupbanner" v-if="dup">
      ⚠ {{ dup.kind }}「{{ dup.value }}」已存在（联系人：{{ titleOf(dup.contact) }}），
      已自动载入其现有资料，<b>提交将覆盖修改</b>
    </div>

    <!-- 段1：编码（默认1个，+/− 增减） -->
    <section class="seg">
      <div class="seg-hd">编码</div>
      <div class="row" v-for="(v, i) in codes" :key="'c' + i">
        <label class="lb">编码{{ i + 1 }}</label>
        <input
          class="in"
          :class="{ red: isRed(v) }"
          v-model="codes[i]"
          type="text"
          :placeholder="'请输入编码' + (i + 1)"
        />
        <button class="op add" @click="addRow(codes)" title="增加一个编码">＋</button>
        <button v-if="codes.length > 1" class="op del" @click="delRow(codes, i)" title="减少">－</button>
      </div>
    </section>

    <!-- 段2：电话（默认1个，+/− 增减） -->
    <section class="seg">
      <div class="seg-hd">电话号码</div>
      <div class="row" v-for="(v, i) in phones" :key="'p' + i">
        <label class="lb">电话{{ i + 1 }}</label>
        <input
          class="in"
          :class="{ red: isRed(v), tel: true }"
          v-model="phones[i]"
          type="tel"
          :placeholder="'请输入电话号码' + (i + 1)"
        />
        <button class="op add" @click="addRow(phones)" title="增加一个电话">＋</button>
        <button v-if="phones.length > 1" class="op del" @click="delRow(phones, i)" title="减少">－</button>
      </div>
    </section>

    <!-- 段3：姓名（1个，可不填） -->
    <section class="seg">
      <div class="seg-hd">姓名 <span class="opt">（可不填）</span></div>
      <div class="row">
        <input class="in" v-model="name" type="text" placeholder="请输入姓名（可不填）" />
      </div>
    </section>

    <div class="btnbar">
      <button class="btn primary" @click="save">提交保存</button>
      <button class="btn ghost" @click="clearAll">清空</button>
      <button class="btn ghost" @click="$emit('done', null)">返回列表</button>
    </div>
    <div class="tip">规则：编码/电话默认各 1 个，点「＋」可增加输入框，2 个以上出现「－」可减少；输入已存在的编码或电话会自动载入该联系人资料，提交需二次确认。</div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { store, titleOf, findDup, addContact, updateContact } from '../db.js'
import { toast } from '../toast.js'

const props = defineProps({ editId: { type: String, default: null } })
const emit = defineEmits(['done'])

const codes = ref([''])
const phones = ref([''])
const name = ref('')
const editingId = ref(null)
const dup = ref(null)

let suppress = false   // 载入现有数据时抑制查重，避免自触发死循环
let timer = null

function loadFromContact(c, dupInfo) {
  suppress = true
  codes.value = (c.codes && c.codes.length) ? [...c.codes] : ['']
  phones.value = (c.phones && c.phones.length) ? [...c.phones] : ['']
  name.value = c.name || ''
  editingId.value = c.id
  dup.value = dupInfo || null
  setTimeout(() => { suppress = false }, 60)
}

function loadEdit(id) {
  dup.value = null
  if (!id) { clearAll(); return }
  const c = store.contacts.find(x => x.id === id)
  if (!c) { clearAll(); return }
  loadFromContact(c, null)
}

watch(() => props.editId, v => loadEdit(v), { immediate: true })

function addRow(arr) {
  arr.push('')
}

function delRow(arr, i) {
  const v = arr[i]
  const settled = arr.filter((x, k) => k !== i).some(x => String(x).trim())
  if (String(v || '').trim() && settled) {
    if (!window.confirm(`确认减少「${v}」这一项？保存后该项将不再属于该联系人。`)) return
  }
  arr.splice(i, 1)
  if (!arr.length) arr.push('')
}

function isRed(v) {
  const d = dup.value
  if (!d) return false
  return String(v || '').trim() === String(d.value).trim()
}

/** 输入即查重：命中已存在 → 直接载入现有数据到输入框 + 标红 + 提交需确认 */
watch([codes, phones], () => {
  if (suppress) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    const d = findDup(codes.value, phones.value, null)
    if (!d) { dup.value = null; return }
    if (d.contact.id === editingId.value) { dup.value = null; return }
    loadFromContact(d.contact, d)
    toast(`${d.kind}「${d.value}」已存在，已载入现有资料`, 'warn', 3200)
  }, 350)
}, { deep: true })

onBeforeUnmount(() => { if (timer) clearTimeout(timer) })

function clearAll() {
  suppress = true
  codes.value = ['']
  phones.value = ['']
  name.value = ''
  editingId.value = null
  dup.value = null
  setTimeout(() => { suppress = false }, 60)
}

function save() {
  const cs = codes.value.map(s => String(s || '').trim()).filter(Boolean)
  const ps = phones.value.map(s => String(s || '').trim()).filter(Boolean)
  const nm = String(name.value || '').trim()
  if (!cs.length && !ps.length) {
    window.alert('请至少填写一个编码或一个电话号码。')
    return
  }

  if (editingId.value) {
    const cur = store.contacts.find(x => x.id === editingId.value)
    const who = cur ? titleOf(cur) : '（已存在）'
    const why = dup.value
      ? `${dup.value.kind}「${dup.value.value}」已存在，`
      : '本次为修改已存在的联系人，'
    const msg = `${why}提交后【覆盖】联系人「${who}」的资料：\n\n编码：${cs.join('、') || '无'}\n电话：${ps.join('、') || '无'}\n姓名：${nm || '（空）'}\n\n确认提交？`
    if (!window.confirm(msg)) return
    updateContact(editingId.value, { codes: cs, phones: ps, name: nm })
    toast(`已覆盖修改「${nm || cs[0] || ps[0]}」`, 'ok')
  } else {
    // 兜底：提交瞬间再查一次，避免并发/漏检
    const d = findDup(cs, ps, null)
    if (d) {
      const msg = `${d.kind}「${d.value}」已存在（联系人：${titleOf(d.contact)}），提交将覆盖其资料，确认提交？`
      if (!window.confirm(msg)) return
      updateContact(d.contact.id, { codes: cs, phones: ps, name: nm })
      toast('已覆盖修改', 'ok')
    } else {
      addContact({ codes: cs, phones: ps, name: nm })
      toast('已添加', 'ok')
    }
  }
  emit('done', 'saved')
  clearAll()
}
</script>
