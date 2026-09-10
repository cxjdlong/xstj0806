/**
 * Excel 读写（SheetJS，已打进离线包，不联网）。
 * 表头：姓名 | 编码1..N | 电话1..N | 更新时间
 */
import * as XLSX from 'xlsx'
import { normList } from './db.js'

export function maxSlots(contacts, field) {
  let n = 1
  for (const c of contacts) n = Math.max(n, (c[field] || []).length)
  return n
}

export function buildSheet(contacts) {
  const nc = maxSlots(contacts, 'codes')
  const np = maxSlots(contacts, 'phones')
  const header = ['姓名', '省份']
  for (let i = 1; i <= nc; i++) header.push('编码' + i)
  for (let i = 1; i <= np; i++) header.push('电话' + i)
  header.push('更新时间')
  const rows = [header]
  for (const c of contacts) {
    const r = [c.name || '', c.province || '']
    for (let i = 0; i < nc; i++) r.push((c.codes || [])[i] || '')
    for (let i = 0; i < np; i++) r.push((c.phones || [])[i] || '')
    r.push(c.updatedAt ? new Date(c.updatedAt).toLocaleString('zh-CN') : '')
    rows.push(r)
  }
  return XLSX.utils.aoa_to_sheet(rows)
}

/** 生成 .xlsx 的 base64 */
export function sheetToBase64(contacts, sheetName) {
  const ws = buildSheet(contacts)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, (sheetName || '通讯录').slice(0, 30))
  return XLSX.write(wb, { bookType: 'xlsx', type: 'base64' })
}

export function xlsxName(prefix, stamp) {
  return `${prefix}_${stamp}.xlsx`
}

/**
 * 解析导入的 Excel：识别 姓名 / 编码N / 电话N 列。
 * 返回 [{ codes:[], phones:[], name }]
 */
export function parseWorkbook(arrayBuffer) {
  const wb = XLSX.read(arrayBuffer, { type: 'array' })
  const out = []
  for (const name of wb.SheetNames) {
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[name], { header: 1, blankrows: false, defval: '' })
    if (!rows.length) continue
    const header = rows[0].map(v => String(v == null ? '' : v).trim())
    const idx = { name: -1, province: -1, codes: [], phones: [] }
    header.forEach((h, i) => {
      if (!h) return
      if (h === '姓名' || h === '名字' || h === '名称') idx.name = i
      else if (h === '省份' || h === '省' || h === '地区') idx.province = i
      else if (/^编码/.test(h)) idx.codes.push(i)
      else if (/^(电话|手机|号码)/.test(h)) idx.phones.push(i)
    })
    if (idx.name < 0 && idx.province < 0 && !idx.codes.length && !idx.phones.length) continue // 表头不认识，跳过
    for (let r = 1; r < rows.length; r++) {
      const row = rows[r]
      const codes = normList(idx.codes.map(i => cell(row[i])))
      const phones = normList(idx.phones.map(i => cell(row[i])))
      const nm = idx.name >= 0 ? String(cell(row[idx.name]) || '').trim() : ''
      const pv = idx.province >= 0 ? String(cell(row[idx.province]) || '').trim() : ''
      if (!codes.length && !phones.length && !nm && !pv) continue
      out.push({ codes, phones, name: nm, province: pv })
    }
  }
  return out
}

function cell(v) {
  if (v == null) return ''
  if (typeof v === 'number') {
    // 电话/编码被 Excel 当数字时，避免科学计数法
    return Number.isInteger(v) && String(v).length < 20 ? String(v) : String(v)
  }
  return String(v).trim()
}
