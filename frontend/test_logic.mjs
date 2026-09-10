/**
 * 逻辑自测(node)：数据层查重 + Excel 导出/导入往返。
 * 运行：cd frontend && node test_logic.mjs
 */
import { store, addContact, updateContact, findDup, normList, snapshot } from './src/db.js'
import { sheetToBase64, parseWorkbook } from './src/excel.js'

let pass = 0, fail = 0
function ok(name, cond) {
  if (cond) { pass++; console.log('  ✓ ' + name) }
  else { fail++; console.log('  ✗ ' + name) }
}

// 清空
store.contacts.splice(0, store.contacts.length)

console.log('【1】数据层')
const a = addContact({ codes: ['A001', 'A002'], phones: ['13800000001', '13900000002'], name: '张三', province: '广东' })
const b = addContact({ codes: ['B001'], phones: ['13800000009'], name: '' })
ok('新增两条', store.contacts.length === 2)
ok('编码去重(空值丢弃)', normList(['A001', 'A001', '  ', '  A002 ']).join(',') === 'A001,A002')

console.log('【2】查重(添加时命中已存在)')
let d = findDup(['B001'], ['1'], null)
ok('按编码命中B', d && d.kind === '编码' && d.value === 'B001' && d.contact.id === b.id)
d = findDup(['X'], ['13900000002'], null)
ok('按电话命中A', d && d.kind === '电话' && d.value === '13900000002' && d.contact.id === a.id)
d = findDup(['A001'], [], a.id)
ok('排除自身后不命中', d === null)
d = findDup(['ZZZ'], ['123'], null)
ok('全不命中', d === null)
ok('部分匹配不算命中(必须完全一致)', findDup(['A00'], ['1380000000'], null) === null)

console.log('【3】修改覆盖')
updateContact(b.id, { codes: ['B001', 'B002'], phones: ['13800000009'], name: '李四' })
const b2 = store.contacts.find(x => x.id === b.id)
ok('编码追加生效', b2.codes.join(',') === 'B001,B002')
ok('姓名写入', b2.name === '李四')

console.log('【4】Excel 往返')
const base64 = sheetToBase64(snapshot(), '通讯录')
ok('导出 base64 非空', typeof base64 === 'string' && base64.length > 100)
const buf = Buffer.from(base64, 'base64')
const rows = parseWorkbook(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength))
ok('解析行数=2', rows.length === 2)
const zhang = rows.find(r => r.name === '张三')
ok('张三编码/电话还原', zhang && zhang.codes.join(',') === 'A001,A002' && zhang.phones.join(',') === '13800000001,13900000002')
ok('省份导出还原', zhang && zhang.province === '广东')
ok('省份可按包含匹配', ['广东'].some(v => v.includes('广')))
const li = rows.find(r => r.name === '李四')
ok('李四还原', li && li.codes.join(',') === 'B001,B002' && li.phones.join(',') === '13800000009')
ok('数字型电话不丢精度', !rows.some(r => /e\+|E\+/.test(r.phones.join(','))))

console.log('【5】导入匹配规则(编码/电话 完全一致→更新, 否则新增)')
const incoming = [
  { codes: ['A001'], phones: [], name: '张三改' },
  { codes: [], phones: ['13800000009'], name: '' },
  { codes: ['C001'], phones: ['13700000007'], name: '王五' },
]
let add = 0, upd = 0
for (const r of incoming) {
  const keys = [...r.codes, ...r.phones]
  const hit = store.contacts.find(c => [...c.codes, ...c.phones].some(v => keys.includes(v)))
  if (hit) upd++; else add++
}
ok('更新2条/新增1条', add === 1 && upd === 2)

console.log('【6】打字防误判：过短的值不触发查重')
store.contacts.splice(0, store.contacts.length)
addContact({ codes: ['12'], phones: ['138'], name: '短值' })
addContact({ codes: ['C100'], phones: ['13911112222'], name: '正常' })
ok('短编码(2位)不命中', findDup(['12'], [''], null) === null)
ok('短电话(3位)不命中', findDup([''], ['138'], null) === null)
ok('等长编码命中', findDup(['C100'], [''], null) !== null)
ok('前缀不命中(必须完整)', findDup(['C10'], [''], null) === null)
ok('打字到一半不命中、补全后命中', findDup(['1391'], [''], null) === null && findDup([''], ['13911112222'], null) !== null)

console.log(`\n结果：通过 ${pass}，失败 ${fail}`)
process.exit(fail ? 1 : 0)
