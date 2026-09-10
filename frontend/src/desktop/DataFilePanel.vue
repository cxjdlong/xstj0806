<template>
  <div class="dl-card dl-panel" style="margin-bottom:16px">
    <div class="dl-card-hd">📁 数据文件（让数据跟着 html 走）</div>
    <div style="padding:14px 16px">
      <div class="dl-df-row">
        <span class="dl-df-dot" :class="dfState.connected ? 'on' : 'off'"></span>
        <template v-if="dfState.connected">
          已连接：<b>{{ dfState.dirName }}/{{ dfState.fileName }}</b>
          <span class="dl-muted" v-if="dfState.lastSaved">（最近写入 {{ fmtTime(dfState.lastSaved) }}）</span>
        </template>
        <template v-else-if="dfState.needGrant">
          上次的文件夹已记住（<b>{{ dfState.dirName }}</b>），但浏览器本次会话需要你再点一下授权。
        </template>
        <template v-else>
          未连接 —— 数据目前存在浏览器本地存储里
        </template>
      </div>
      <div class="dl-df-desc">
        连接后，每次改动都会自动写入 <code>{{ dfState.fileName }}</code>；下次双击 html 打开会自动读回。
        <b>把这个文件夹拷到别的电脑，数据一起带走。</b>（浏览器安全策略要求首次手选一次文件夹；Chrome/Edge 会记住位置。）
      </div>
      <div class="dl-df-ops">
        <button v-if="!dfState.connected && dfState.needGrant" class="dl-btn primary" :disabled="dfState.busy" @click="grant">授权并连接</button>
        <button class="dl-btn" :class="{ primary: !dfState.connected }" :disabled="dfState.busy" @click="pick">
          {{ dfState.connected ? '更换文件夹' : '选择 html 所在文件夹' }}
        </button>
        <button class="dl-btn" v-if="dfState.connected" @click="saveNow">立即写入</button>
        <button class="dl-btn ghost" v-if="dfState.connected" @click="off">断开（改回浏览器存储）</button>
      </div>
      <div class="dl-df-warn" v-if="!dfState.supported">
        ⚠ 当前浏览器不支持直接读写文件（需 Chrome / Edge）。数据会继续存在浏览器存储里。
      </div>
      <div class="dl-df-warn" v-if="dfState.lastError">⚠ 上次写入失败：{{ dfState.lastError }}</div>
    </div>
  </div>
</template>

<script setup>
import { dfState, pickFolder, grantAndConnect, writeNow, disconnect } from '../dataFile.js'
import { fmtTime } from '../db.js'
import { toast } from '../toast.js'

async function pick() {
  const ok = await pickFolder()
  if (ok) toast('已连接数据文件', 'ok')
}
async function grant() {
  const ok = await grantAndConnect()
  toast(ok ? '已连接数据文件' : '授权未完成', ok ? 'ok' : 'warn')
}
async function saveNow() {
  const ok = await writeNow(false)
  if (ok) toast('已写入数据文件', 'ok')
}
function off() {
  if (!window.confirm('确认断开？断开后数据只存在浏览器里（不会删除已生成的数据文件）。')) return
  disconnect()
  toast('已断开，改回浏览器存储', 'info')
}
</script>
