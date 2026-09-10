<template>
  <div class="dl-banner" v-if="showBanner">
    <div class="dl-banner-txt">
      <b>数据存放位置</b>：现在数据存在浏览器里。可以把数据改成存在 <b>本 html 同目录</b> 的
      <code>{{ dfState.fileName }}</code>，这样文件夹拷到哪、数据就跟到哪。
      <span class="dl-muted">（首次需选一次 html 所在的文件夹，之后自动读写）</span>
    </div>
    <div class="dl-banner-ops">
      <button class="dl-btn primary" :disabled="dfState.busy" @click="connect">{{ dfState.busy ? '处理中…' : '选择 html 所在文件夹' }}</button>
      <button class="dl-btn ghost" @click="dismiss">暂不，继续用浏览器存储</button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { dfState, pickFolder, grantAndConnect } from '../dataFile.js'
import { toast } from '../toast.js'

const DISMISS_KEY = 'contacts_df_banner_dismissed'
const dismissed = ref(localStorage.getItem(DISMISS_KEY) === '1')

const showBanner = computed(() => dfState.supported && !dfState.connected && !dismissed.value)

async function connect() {
  if (dfState.needGrant) {
    const ok = await grantAndConnect()
    if (ok) { toast('已连接数据文件，数据将保存在 html 同目录', 'ok', 3600); return }
  }
  const ok = await pickFolder()
  if (ok) toast('已连接：数据保存在 ' + dfState.dirName + '/' + dfState.fileName, 'ok', 4200)
}

function dismiss() {
  dismissed.value = true
  localStorage.setItem(DISMISS_KEY, '1')
}
</script>
