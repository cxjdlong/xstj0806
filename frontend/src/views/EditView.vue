<template>
  <div class="page edit">
    <div class="edit-title">
      {{ editingId ? '修改通讯录' : '添加通讯录' }}
      <span class="tag-mod" v-if="editingId">修改模式</span>
    </div>

    <!-- 光标移出输入框后判定到重复：载入现有资料 + 保留本次新输入的值，提交需确认覆盖 -->
    <div class="dupbanner" v-if="loaded">
      ⚠ {{ loaded.kind }}「{{ loaded.value }}」已存在（联系人：{{ titleOf(loaded.contact) }}），已载入其现有资料，<b>提交将覆盖修改</b>。
      <template v-if="freshCount">
        <br />本次新输入的 <b>{{ freshCount }}</b> 个值已<b>保留</b>（标「新增」）：
        <b>要新增</b>就留着直接提交（会写入该联系人）；<b>不要就点该行「－」删除</b>。
      </template>
      <button class="btn ghost sm" @click="detach">改为新增一条</button>
    </div>

    <!-- 段1：编码（默认1个，+/− 增减） -->
    <section class="seg">
      <div class="seg-hd">编码</div>
      <div class="row" v-for="(v, i) in codes" :key="'c' + i">
        <label class="lb">编码{{ i + 1 }}<span v-if="isFresh(v, addedCodes)" class="tag-new">新增</span></label>
        <input
          class="in"
          :class="{ red: isRed(v), fresh: isFresh(v, addedCodes) }"
          v-model="codes[i]"
          type="text"
          :placeholder="'请输入编码' + (i + 1)"
          @blur="onBlur"
        />
        <button class="op add" @click="addRow(codes)" title="增加一个编码">＋</button>
        <button v-if="codes.length > 1" class="op del" @click="delRow(codes, i)" title="删除这一项">－</button>
      </div>
    </section>

    <!-- 段2：电话（默认1个，+/− 增减） -->
    <section class="seg">
      <div class="seg-hd">电话号码</div>
      <div class="row" v-for="(v, i) in phones" :key="'p' + i">
        <label class="lb">电话{{ i + 1 }}<span v-if="isFresh(v, addedPhones)" class="tag-new">新增</span></label>
        <input
          class="in"
          :class="{ red: isRed(v), fresh: isFresh(v, addedPhones) }"
          v-model="phones[i]"
          type="tel"
          :placeholder="'请输入电话号码' + (i + 1)"
          @blur="onBlur"
        />
        <button class="op add" @click="addRow(phones)" title="增加一个电话">＋</button>
        <button v-if="phones.length > 1" class="op del" @click="delRow(phones, i)" title="删除这一项">－</button>
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
    <div class="tip">
      规则：编码/电话默认各 1 个，点「＋」可增加输入框，2 个以上出现「－」可减少；姓名可不填。<br />
      查重：<b>光标移出输入框时</b>才判定。命中已存在的编码/电话 → 载入该联系人现有资料，<b>同时保留你本次新输入的值</b>（标「新增」，可留着一起新增，也可点「－」删掉），提交时二次确认后覆盖。
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { store, titleOf, findDup, normList, addContact, updateContact } from '../db.js'
import { toast } from '../toast.js'

const props = defineProps({ editId: { type: String, default: null } })
const emit = defineEmits(['done'])

const codes = ref([''])
const phones = ref([''])
const name = ref('')
const editingId = ref(null)
const loaded = ref(null)      // 已载入的已存在记录（提交需确认覆盖）
const addedCodes = ref([])    // 本次新输入（相对已载入联系人属于新增）的编码
const addedPhones = ref([])

let suppress = false

const freshCount = computed(() => addedCodes.value.length + addedPhones.value.length)

/** 载入现有资料；extra* = 本次新输入的值，不属于该联系人的会被保留在表单里（标「新增」） */
function loadFromContact(c, info, extraCodes = [], extraPhones = []) {
  suppress = true
  const baseC = c.codes || []
  const baseP = c.phones || []
  const setC = new Set(baseC)
  const setP = new Set(baseP)
  const addC = normList(extraCodes).filter(v => !setC.has(v))
  const addP = normList(extraPhones).filter(v => !setP.has(v))
  const mergedC = [...baseC, ...addC]
  const mergedP = [...baseP, ...addP]
  codes.value = mergedC.length ? mergedC : ['']
  phones.value = mergedP.length ? mergedP : ['']
  name.value = c.name || ''
  editingId.value = c.id
  loaded.value = info || null
  addedCodes.value = addC
  addedPhones.value = addP
  setTimeout(() => { suppress = false }, 80)
}

function loadEdit(id) {
  loaded.value = null
  addedCodes.value = []
  addedPhones.value = []
  if (!id) { resetFields(); return }
  const c = store.contacts.find(x => x.id === id)
  if (!c) { resetFields(); return }
  loadFromContact(c, null)
}

watch(() => props.editId, v => loadEdit(v), { immediate: true })

function resetFields() {
  suppress = true
  codes.value = ['']
  phones.value = ['']
  name.value = ''
  editingId.value = null
  loaded.value = null
  addedCodes.value = []
  addedPhones.value = []
  setTimeout(() => { suppress = false }, 80)
}

function addRow(arr) {
  arr.push('')
}

function delRow(arr, i) {
  const v = String(arr[i] || '').trim()
  const rest = arr.filter((x, k) => k !== i).some(x => String(x).trim())
  if (v && rest) {
    const isNew = addedCodes.value.includes(v) || addedPhones.value.includes(v)
    const msg = isNew
      ? `「${v}」是本次新输入的值，确认删除（不加入该联系人）？`
      : `确认减少「${v}」这一项？保存后该项将不再属于该联系人。`
    if (!window.confirm(msg)) return
  }
  arr.splice(i, 1)
  if (!arr.length) arr.push('')
  // 同步「新增」标记：只保留仍在表单里的值
  addedCodes.value = addedCodes.value.filter(x => codes.value.includes(x))
  addedPhones.value = addedPhones.value.filter(x => phones.value.includes(x))
}

function isRed(v) {
  const d = loaded.value
  if (!d) return false
  return String(v || '').trim() === String(d.value).trim()
}

function isFresh(v, list) {
  const s = String(v || '').trim()
  return !!s && list.includes(s)
}

/**
 * 唯一判定时机：光标移出当前输入框（blur）。
 * 命中已存在 → 载入该联系人现有资料，并把本次新输入的编码/电话一起保留在表单里。
 */
function onBlur(e) {
  if (suppress) return
  const v = String(e && e.target && e.target.value ? e.target.value : '').trim()
  if (!v) return
  const d = findDup(codes.value, phones.value, editingId.value || null)
  if (!d) return
  const typedC = codes.value.slice()
  const typedP = phones.value.slice()
  loadFromContact(d.contact, d, typedC, typedP)
  const n = addedCodes.value.length + addedPhones.value.length
  toast(`${d.kind}「${d.value}」已存在，已载入现有资料${n ? `，保留新输入 ${n} 项` : ''}`, 'warn', 3400)
}

/** 脱离修改模式：当前填写当作一条新联系人 */
function detach() {
  editingId.value = null
  loaded.value = null
  addedCodes.value = []
  addedPhones.value = []
  toast('已改为新增：提交将新建一条联系人', 'info', 2600)
}

function clearAll() {
  resetFields()
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
    const why = loaded.value
      ? `${loaded.value.kind}「${loaded.value.value}」已存在，`
      : '本次为修改已存在的联系人，'
    const old = cur ? `原资料：编码 ${(cur.codes || []).join('、') || '无'} ／ 电话 ${(cur.phones || []).join('、') || '无'} ／ 姓名 ${cur.name || '（空）'}\n\n` : ''
    const keep = freshCount.value
      ? `其中包含本次新输入的 ${[...addedCodes.value, ...addedPhones.value].join('、')}（将新增进去）\n\n`
      : ''
    const msg = `${why}提交后【覆盖】联系人「${who}」的资料：\n\n${old}${keep}最终资料：编码 ${cs.join('、') || '无'} ／ 电话 ${ps.join('、') || '无'} ／ 姓名 ${nm || '（空）'}\n\n确认提交？`
    if (!window.confirm(msg)) return
    updateContact(editingId.value, { codes: cs, phones: ps, name: nm })
    toast(`已覆盖修改「${nm || cs[0] || ps[0]}」`, 'ok')
  } else {
    // 兜底：提交瞬间再查一次
    const d = findDup(cs, ps, null)
    if (d) {
      const c = d.contact
      const msg = `${d.kind}「${d.value}」已存在（联系人：${titleOf(c)}）\n\n原资料：编码 ${(c.codes || []).join('、') || '无'} ／ 电话 ${(c.phones || []).join('、') || '无'} ／ 姓名 ${c.name || '（空）'}\n\n提交将【覆盖】该联系人的资料，确认提交？`
      if (!window.confirm(msg)) return
      updateContact(c.id, { codes: cs, phones: ps, name: nm })
      toast('已覆盖修改', 'ok')
    } else {
      addContact({ codes: cs, phones: ps, name: nm })
      toast('已添加', 'ok')
    }
  }
  emit('done', 'saved')
  resetFields()
}
</script>
