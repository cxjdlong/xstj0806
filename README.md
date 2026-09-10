# 通讯录 App（离线 · 安卓）

本地离线通讯录：数据只存本机（localStorage，安卓壳用 WebViewAssetLoader 内置 assets，**断网可用**）。
支持多编码 / 多电话 / 姓名、编码或电话模糊查询、Excel 导出导入、备份列表（可恢复/删除）、点电话直接拨打。

## 三个栏目

### 一、通讯录
- **查询**：输入框 + 「查询」按钮，**编码或电话只要「包含」关键字**即命中，结果直接在本页展示，点某条可修改。
- **列表**：每页 **15 条**分页（上一页/下一页，显示 页码/总页数/总条数）。
- **点某条 → 修改**；每条带「删除」按钮，**删除需二次确认**。
- **点绿色电话号 → 直接拨打**（安卓走系统拨号盘 ACTION_DIAL，拨号前二次确认）。
- 底部中间固定「＋ 添加」按钮（位置固定不变，列表留出底部空间不会被遮挡）。

### 二、添加
- **段1 编码**：编码1、编码2、编码3…，每行输入框后面有「＋」增加一个；**多于 1 个时出现「－」**可减少。
- **段2 电话号码**：同编码规则。
- **段3 姓名**：只有 1 个，**可以不填**。
- **重复处理（关键）**：**只在光标移出输入框（失焦）时判定**，打字过程中完全不判定、不改动输入 ——
  - 移出时若编码/电话已存在，自动载入该联系人的现有资料（进入修改模式），命中项标红，**提交时二次确认覆盖**（确认框里显示将被覆盖的原资料）；
  - 修改模式下可点「改为新增」脱离，把当前填写当新增提交；
  - 过短的值不参与判定（编码 <3 位、电话 <5 位忽略），避免误命中。

### 三、备份
- **全部备份**：把全部联系人备份成 Excel 存到手机（`本机/Download/通讯录备份/`），**弹窗显示真实路径**，同时记入下面的备份列表。
- **完全导入**：选 Excel 导入，**导入前自动先备份一次**当前数据；按编码/电话完全一致匹配 → 已存在则更新，否则新增，完成后报告 新增/更新 条数。
- **备份列表**：**每页 10 条**分页，可**恢复**、**删除**（删除同时删掉手机上的文件，需确认）。

Excel 表头：`姓名 | 编码1 | 编码2 … | 电话1 | 电话2 … | 更新时间`（导入按表头识别，与本 App 导出的模板互通）。

## 目录结构
```
contacts/
├── frontend/                     # Vue3 + Vite（离线打包进 APK）
│   ├── src/db.js                 # 本地数据层（contacts / backups，localStorage）
│   ├── src/excel.js              # SheetJS 读写 Excel（已打进包，不联网）
│   ├── src/native.js             # 原生桥封装（保存文件/删除/拨号，浏览器自动降级）
│   ├── src/views/ContactsView.vue   # 栏目一：通讯录（查询/分页/拨打/删除）
│   ├── src/views/EditView.vue       # 栏目二：添加（多编码/多电话/查重载入+标红+确认）
│   ├── src/views/BackupView.vue     # 栏目三：备份（导出/导入/备份列表 10条分页）
│   └── test_logic.mjs            # node 逻辑自测（查重 + Excel 往返）
├── android/                      # 安卓离线壳（WebViewAssetLoader 内置 assets）
│   ├── app/src/main/java/com/contacts/app/MainActivity.java    # 壳 + tel: 拨号 + 文件选择
│   ├── app/src/main/java/com/contacts/app/ContactsBridge.java  # 保存Excel/删除文件/拨号
│   ├── app/keystore.p12          # 固定自签(alias contacts / contacts123) → 可覆盖安装保留数据
│   └── app/src/main/assets/      # 前端 dist（每次改完前端重新拷贝）
└── .github/workflows/build-apk.yml  # 云打包：push 到 android/** 自动出 APK
```

## 开发与打包
```bash
# 前端
cd frontend && npm install && npm run build
node test_logic.mjs                      # 逻辑自测

# 把前端产物内置进安卓壳
cp -r frontend/dist/* android/app/src/main/assets/

# 提交到 android/** 即触发云端打包，APK 在 Actions run 页面的 Artifacts 下载
git add -A && git commit -m "..." && git push
```

## 说明
- 数据完全本地：卸载 App 或清除应用数据会丢联系人，**重要数据请定期「全部备份」并留存 Excel**。
- 备份文件位置：手机 `Download/通讯录备份/`（App 内导出后会显示完整路径）。
