<template>
  <div class="dl-view">
    <div class="dl-view-hd">
      <div>
        <h2>{{ editingId ? '修改联系人' : '新增联系人' }}<span v-if="editingId" class="dl-badge">修改模式</span></h2>
        <div class="dl-sub">
          编码/电话可多个（点「＋」增加，2 个以上出现「－」）；姓名可不填。<br />
          查重：<b>光标移出输入框</b>时才判定 —— 命中已存在就载入对方资料，你本次新输入的值会保留（标「新增」），提交前二次确认。
        </div>
      </div>
    </div>

    <div class="dl-alert danger" v-if="loaded">
      <div>
        ⚠ {{ loaded.kind }}「{{ loaded.value }}」已存在（联系人：{{ titleOf(loaded.contact) }}），已载入其现有资料，<b>提交将覆盖修改</b>。
        <template v-if="freshCount">
          <br />本次新输入的 <b>{{ freshCount }}</b> 个值已保留（标「新增」）：要新增就留着提交（会写入该联系人）；不要就点该行「－」删除。
        </template>
      </div>
      <button class="dl-btn xs" @click="detach">改为新增一条</button>
    </div>

    <div class="dl-card dl-form">
      <div class="dl-seg">
        <div class="dl-seg-hd">编码</div>
        <div class="dl-row" v-for="(v, i) in codes" :key="'c' + i">
          <label class="dl-label">编码{{ i + 1 }}<span v-if="isFresh(v, addedCodes)" class="dl-tag-new">新增</span></label>
          <input class="dl-input wide" :class="{ 'is-red': isRed(v), 'is-fresh': isFresh(v, addedCodes) }"
                 v-model="codes[i]" type="text" :placeholder="'请输入编码' + (i + 1)" @blur="onBlur" />
          <button class="dl-op add" @click="addRow(codes)" title="增加一个编码">＋</button>
          <button class="dl-op del" v-if="codes.length > 1" @click="delRow(codes, i)" title="删除这一项">－</button>
        </div>
      </div>

      <div class="dl-seg">
        <div class="dl-seg-hd">电话号码</div>
        <div class="dl-row" v-for="(v, i) in phones" :key="'p' + i">
          <label class="dl-label">电话{{ i + 1 }}<span v-if="isFresh(v, addedPhones)" class="dl-tag-new">新增</span></label>
          <input class="dl-input wide" :class="{ 'is-red': isRed(v), 'is-fresh': isFresh(v, addedPhones) }"
                 v-model="phones[i]" type="tel" :placeholder="'请输入电话号码' + (i + 1)" @blur="onBlur" />
          <button class="dl-op add" @click="addRow(phones)" title="增加一个电话">＋</button>
          <button class="dl-op del" v-if="phones.length > 1" @click="delRow(phones, i)" title="删除这一项">－</button>
        </div>
      </div>

      <div class="dl-seg">
        <div class="dl-seg-hd">姓名 <span class="dl-muted">（可不填）</span></div>
        <div class="dl-row">
          <label class="dl-label">姓名</label>
          <input class="dl-input wide" v-model="name" type="text" placeholder="请输入姓名（可不填）" />
        </div>
      </div>

      <div class="dl-form-foot">
        <button class="dl-btn primary" @click="save">提交保存</button>
        <button class="dl-btn ghost" @click="clearAll">清空</button>
        <button class="dl-btn ghost" @click="$emit('done', null)">返回列表</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { titleOf } from '../db.js'
import { useContactForm } from '../useContactForm.js'

const props = defineProps({ editId: { type: String, default: null } })
const emit = defineEmits(['done'])

const {
  codes, phones, name, editingId, loaded, addedCodes, addedPhones, freshCount,
  isRed, isFresh, onBlur, addRow, delRow, detach, clearAll, save,
} = useContactForm(() => props.editId, () => emit('done', 'saved'))
</script>
