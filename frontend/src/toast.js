import { reactive } from 'vue'

export const toasts = reactive([])

export function toast(msg, kind = 'info', ms = 2600) {
  const id = Date.now() + Math.random()
  toasts.push({ id, msg: String(msg), kind })
  setTimeout(() => {
    const i = toasts.findIndex(t => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, ms)
}

export function alertBox(msg) {
  return new Promise(resolve => {
    if (typeof window !== 'undefined' && window.alert) {
      window.alert(msg)
      resolve(true)
    } else resolve(true)
  })
}
