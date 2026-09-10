<template>
  <div class="dl-banner warn" v-if="showBanner">
    <div class="dl-banner-txt">
      ⚠️ <b>还没连接数据文件夹</b>：现在数据只存在<b>这个浏览器</b>里 —— 换浏览器、清缓存、换电脑就看不到。
      连一次 <b>本 html 所在文件夹</b>，数据就会写进 <code>db/{{ dfState.fileName }}</code>，文件夹到哪数据到哪。
      <span class="dl-muted">（只需首次点一下选文件夹；浏览器的安全限制，网页不能自己读写磁盘）</span>
    </div>
    <div class="dl-banner-ops">
      <button class="dl-btn primary" :disabled="dfState.busy" @click="connect">{{ dfState.busy ? '处理中…' : '选择 html 所在文件夹' }}</button>
      <button class="dl-btn ghost" @click="dismiss">本次先不连</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { dfState, pickFolder, grantAndConnect } from '../dataFile.js'
import { toast } from '../toast.js'

const DISMISS_KEY = 'contacts_df_banner_hidden_once'
const dismissed = ref(localStorage.getItem(DISMISS_KEY) === '1')

const showBanner = computed(() => dfState.supported && !dfState.connected && !dismissed.value)

async function connect() {
  if (dfState.needGrant) {
    const ok = await grantAndConnect()
    if (ok) { toast('已连接数据库文件，数据将保存在 html 同目录', 'ok', 3600); return }
  }
  const ok = await pickFolder()
  if (ok) toast('已连接：数据保存在 ' + dfState.dirName + '/' + dfState.fileName, 'ok', 4200)
}

function dismiss() {
  dismissed.value = true
  localStorage.setItem(DISMISS_KEY, '1')
}
</script>
