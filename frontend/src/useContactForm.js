/**
 * 添加/修改表单的共用逻辑（手机壳 EditView 与 桌面网页版 DEdit 共用，避免行为漂移）。
 * 核心规则：
 *  - 查重只在「光标移出输入框(blur)」时判定，打字期间绝不动表单；
 *  - 命中已存在 → 载入其现有资料 + 命中项标红；
 *  - 本次新输入的值保留在表单里（标「新增」），可留着一起写入，也可删除；
 *  - 提交时二次确认（列出原资料 / 新输入项 / 最终资料）；
 *  - 编码 <3 位、电话 <5 位不参与查重。
 */
import { ref, computed, watch } from 'vue'
import { store, findDup, normList, addContact, updateContact, titleOf } from './db.js'
import { toast } from './toast.js'

export function useContactForm(getEditId, onDone) {
  const codes = ref([''])
  const phones = ref([''])
  const name = ref('')
  const province = ref('')
  const editingId = ref(null)
  const loaded = ref(null)      // 已载入的已存在记录（提交需确认覆盖）
  const addedCodes = ref([])    // 本次新输入的编码（相对已载入联系人）
  const addedPhones = ref([])

  let suppress = false

  const freshCount = computed(() => addedCodes.value.length + addedPhones.value.length)

  function snapshotTyped() {
    return { c: codes.value.slice(), p: phones.value.slice() }
  }

  /** 载入现有资料；extra = 本次新输入的值，不属于该联系人的会被保留（标「新增」） */
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
    province.value = c.province || ''
    editingId.value = c.id
    loaded.value = info || null
    addedCodes.value = addC
    addedPhones.value = addP
    setTimeout(() => { suppress = false }, 80)
  }

  function resetFields() {
    suppress = true
    codes.value = ['']
    phones.value = ['']
    name.value = ''
    province.value = ''
    editingId.value = null
    loaded.value = null
    addedCodes.value = []
    addedPhones.value = []
    setTimeout(() => { suppress = false }, 80)
  }

  function loadEdit(id) {
    addedCodes.value = []
    addedPhones.value = []
    loaded.value = null
    if (!id) { resetFields(); return }
    const c = store.contacts.find(x => x.id === id)
    if (!c) { resetFields(); return }
    loadFromContact(c, null)
  }

  watch(() => getEditId(), v => loadEdit(v), { immediate: true })

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

  /** 唯一判定时机：光标移出当前输入框（blur） */
  function onBlur(e) {
    if (suppress) return
    const v = String(e && e.target && e.target.value ? e.target.value : '').trim()
    if (!v) return
    const d = findDup(codes.value, phones.value, editingId.value || null)
    if (!d) return
    const t = snapshotTyped()
    loadFromContact(d.contact, d, t.c, t.p)
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
    const pv = String(province.value || '').trim()
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
      const msg = `${why}提交后【覆盖】联系人「${who}」的资料：\n\n${old}${keep}最终资料：编码 ${cs.join('、') || '无'} ／ 电话 ${ps.join('、') || '无'} ／ 姓名 ${nm || '（空）'} ／ 省份 ${pv || '（空）'}\n\n确认提交？`
      if (!window.confirm(msg)) return
      updateContact(editingId.value, { codes: cs, phones: ps, name: nm, province: pv })
      toast(`已覆盖修改「${nm || cs[0] || ps[0]}」`, 'ok')
    } else {
      const d = findDup(cs, ps, null)
      if (d) {
        const c = d.contact
        const msg = `${d.kind}「${d.value}」已存在（联系人：${titleOf(c)}）\n\n原资料：编码 ${(c.codes || []).join('、') || '无'} ／ 电话 ${(c.phones || []).join('、') || '无'} ／ 姓名 ${c.name || '（空）'}\n\n提交将【覆盖】该联系人的资料，确认提交？`
        if (!window.confirm(msg)) return
        updateContact(c.id, { codes: cs, phones: ps, name: nm, province: pv })
        toast('已覆盖修改', 'ok')
      } else {
        addContact({ codes: cs, phones: ps, name: nm, province: pv })
        toast('已添加', 'ok')
      }
    }
    if (onDone) onDone()
    resetFields()
  }

  return {
    codes, phones, name, province, editingId, loaded, addedCodes, addedPhones, freshCount,
    isRed, isFresh, onBlur, addRow, delRow, detach, clearAll, save,
  }
}
