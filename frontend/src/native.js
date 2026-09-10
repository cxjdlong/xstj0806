/**
 * 原生桥封装：安卓壳里 window.Contacts.saveXls / deleteFile 可用；
 * 浏览器/预览环境降级为 Blob 下载。
 */
export function isApp() {
  return typeof window !== 'undefined' && window.Contacts && typeof window.Contacts.saveXls === 'function'
}

/** 保存文件到手机下载目录，返回真实路径（失败返回 ''） */
export function saveFile(name, base64) {
  if (isApp()) {
    try {
      const p = window.Contacts.saveXls(name, base64)
      return p || ''
    } catch (e) {
      console.warn('saveXls failed', e)
      return ''
    }
  }
  // 浏览器降级：走 Blob 下载
  try {
    const bin = atob(base64)
    const arr = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i)
    const url = URL.createObjectURL(new Blob([arr], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
    return '（浏览器）下载目录/' + name
  } catch (e) {
    console.warn('blob download failed', e)
    return ''
  }
}

/** 删除手机上的备份文件 */
export function deleteFile(path) {
  if (!path || !isApp()) return false
  try {
    return !!window.Contacts.deleteFile(path)
  } catch (e) {
    return false
  }
}

/** 备份目录（展示用） */
export function exportDirLabel() {
  return isApp() ? '本机/Download/通讯录备份' : '电脑浏览器下载目录'
}

/** 保存目标（展示用）：手机 or 电脑 */
export function saveTargetLabel() {
  return isApp() ? '手机' : '电脑浏览器下载目录'
}

/** 直接拨打（安卓壳走原生 ACTION_DIAL；浏览器走 tel: 协议） */
export function dial(phone) {
  const num = String(phone || '').replace(/[^\d+*#-]/g, '')
  if (!num) return false
  if (isApp() && typeof window.Contacts.dial === 'function') {
    try {
      window.Contacts.dial(num)
      return true
    } catch (e) { /* 落回 tel: */ }
  }
  try {
    window.location.href = 'tel:' + num
    return true
  } catch (e) {
    return false
  }
}

