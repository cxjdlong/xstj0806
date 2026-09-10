<template>
  <div class="page edit">
    <div class="edit-title">
      {{ editingId ? '修改通讯录' : '添加通讯录' }}
      <span class="tag-mod" v-if="editingId">修改模式</span>
    </div>

    <div class="dupbanner" v-if="loaded">
      ⚠ {{ loaded.kind }}「{{ loaded.value }}」已存在（联系人：{{ titleOf(loaded.contact) }}），已载入其现有资料，<b>提交将覆盖修改</b>。
      <template v-if="freshCount">
        <br />本次新输入的 <b>{{ freshCount }}</b> 个值已<b>保留</b>（标「新增」）：
        <b>要新增</b>就留着直接提交（会写入该联系人）；<b>不要就点该行「－」删除</b>。
      </template>
      <button class="btn ghost sm" @click="detach">改为新增一条</button>
    </div>

    <!-- 段1：编码 -->
    <section class="seg">
      <div class="seg-hd">编码</div>
      <div class="row" v-for="(v, i) in codes" :key="'c' + i">
        <label class="lb">编码{{ i + 1 }}<span v-if="isFresh(v, addedCodes)" class="tag-new">新增</span></label>
        <input class="in" :class="{ red: isRed(v), fresh: isFresh(v, addedCodes) }" v-model="codes[i]"
               type="text" :placeholder="'请输入编码' + (i + 1)" @blur="onBlur" />
        <button class="op add" @click="addRow(codes)" title="增加一个编码">＋</button>
        <button v-if="codes.length > 1" class="op del" @click="delRow(codes, i)" title="删除这一项">－</button>
      </div>
    </section>

    <!-- 段2：电话 -->
    <section class="seg">
      <div class="seg-hd">电话号码</div>
      <div class="row" v-for="(v, i) in phones" :key="'p' + i">
        <label class="lb">电话{{ i + 1 }}<span v-if="isFresh(v, addedPhones)" class="tag-new">新增</span></label>
        <input class="in" :class="{ red: isRed(v), fresh: isFresh(v, addedPhones) }" v-model="phones[i]"
               type="tel" :placeholder="'请输入电话号码' + (i + 1)" @blur="onBlur" />
        <button class="op add" @click="addRow(phones)" title="增加一个电话">＋</button>
        <button v-if="phones.length > 1" class="op del" @click="delRow(phones, i)" title="删除这一项">－</button>
      </div>
    </section>

    <!-- 段3：姓名 -->
    <section class="seg">
      <div class="seg-hd">姓名 <span class="opt">（可不填）</span></div>
      <div class="row">
        <input class="in" v-model="name" type="text" placeholder="请输入姓名（可不填）" />
      </div>
    </section>

    <!-- 段4：省份（可不填，查询也支持按省份） -->
    <section class="seg">
      <div class="seg-hd">省份 <span class="opt">（可不填）</span></div>
      <div class="row">
        <select class="in" v-model="province">
          <option value="">（不填）</option>
          <option v-for="p in PROVINCES" :key="p" :value="p">{{ p }}</option>
        </select>
      </div>
    </section>

    <div class="btnbar">
      <button class="btn primary" @click="save">提交保存</button>
      <button class="btn ghost" @click="clearAll">清空</button>
      <button class="btn ghost" @click="$emit('done', null)">返回列表</button>
    </div>
    <div class="tip">
      规则：编码/电话默认各 1 个，点「＋」可增加输入框，2 个以上出现「－」可减少；姓名可不填。<br />
      省份可不填；查询支持 编码/电话/姓名/省份。<br />查重：<b>光标移出输入框时</b>才判定。命中已存在的编码/电话 → 载入该联系人现有资料，<b>同时保留你本次新输入的值</b>（标「新增」，可留着一起新增，也可点「－」删掉），提交时二次确认后覆盖。
    </div>
  </div>
</template>

<script setup>
import { titleOf } from '../db.js'
import { useContactForm } from '../useContactForm.js'
import { PROVINCES } from '../provinces.js'

const props = defineProps({ editId: { type: String, default: null } })
const emit = defineEmits(['done'])

const {
  codes, phones, name, province, editingId, loaded, addedCodes, addedPhones, freshCount,
  isRed, isFresh, onBlur, addRow, delRow, detach, clearAll, save,
} = useContactForm(() => props.editId, () => emit('done', 'saved'))
</script>
