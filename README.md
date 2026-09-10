# 通讯录 App（离线 · 安卓）

本地离线通讯录：数据只存本机（localStorage，安卓壳用 WebViewAssetLoader 内置 assets，**断网可用**）。
支持多编码 / 多电话 / 姓名、编码或电话模糊查询、Excel 导出导入、备份列表（可恢复/删除）、点电话直接拨打。

## 三个栏目

### 一、通讯录
- **查询**：输入框 + 「查询」按钮，**编码 / 电话 / 姓名 / 省份只要「包含」关键字**即命中，结果直接在本页展示（每页 7 条，超过 7 条自动分页），点某条可修改。
- **列表**：每页 **7 条**分页（上一页/下一页，显示 页码/总页数/总条数）；行高紧凑（单条约 59px）。
- **点某条 → 修改**；每条带「删除」按钮，**删除需二次确认**。
- **点绿色电话号 → 直接拨打**（安卓走系统拨号盘 ACTION_DIAL，拨号前二次确认）。
- 底部中间固定「＋ 添加」按钮（位置固定不变，列表留出底部空间不会被遮挡）。

### 二、添加
- **段1 编码**：编码1、编码2、编码3…，每行输入框后面有「＋」增加一个；**多于 1 个时出现「－」**可减少。
- **段2 电话号码**：同编码规则。
- **段3 姓名**：只有 1 个，**可以不填**。
- **段4 省份**：下拉选择（34 个省市自治区/特别行政区），**可以不填**；查询也支持按省份。
- **重复处理（关键）**：**只在光标移出输入框（失焦）时判定**，打字过程中完全不判定、不改动输入 ——
  - 移出时若编码/电话已存在，自动载入该联系人的现有资料（进入修改模式），命中项标红；
  - **本次新输入的编码/电话会被保留在表单里并标「新增」**：要新增就留着直接提交（会写入该联系人），不要就点该行「－」删除（有确认提示）；
  - **提交时二次确认覆盖**（确认框里显示原资料 + 将新增进去的新输入项 + 最终资料）；
  - 「改为新增一条」可脱离修改模式，把当前填写整体当作新联系人提交；
  - 过短的值不参与判定（编码 <3 位、电话 <5 位忽略），避免误命中。

### 三、备份
- **全部备份**：把全部联系人备份成 Excel 存到手机（`本机/Download/通讯录备份/`），**弹窗显示真实路径**，同时记入下面的备份列表。
- **完全导入**：选 Excel 导入，**导入前自动先备份一次**当前数据；按编码/电话完全一致匹配 → 已存在则更新，否则新增，完成后报告 新增/更新 条数。
- **备份列表**：**每页 10 条**分页，可**恢复**、**删除**（删除同时删掉手机上的文件，需确认）。

Excel 表头：`姓名 | 省份 | 编码1 | 编码2 … | 电话1 | 电话2 … | 更新时间`（导入按表头识别，与本 App 导出的模板互通）。


## 数据存放与性能（万级数据）

### 存储架构
| 层 | 位置 | 说明 |
|---|---|---|
| **主存储（运行时）** | 浏览器 **IndexedDB**（`contacts_db`） | 异步、无 5MB 限制、不做 JSON 序列化；手机 App 用同一套（WebView 内） |
| **数据库文件（可选）** | `<html 所在文件夹>/db/通讯录数据.db` | **真正的 SQLite 数据库文件**（sql.js 在浏览器里跑 SQLite 生成/读取），可用 DB Browser for SQLite 等工具直接打开；文件夹拷到哪、数据跟到哪 |
| **备份文件** | `<html 所在文件夹>/backup/通讯录_全部备份_*.xlsx` | 「全部备份」导出的 Excel 存这里（含「导入前自动备份」） |
| 旧版 localStorage | `contacts_db_v1` | 首次启动自动迁移进 IndexedDB 后清除 |
| 旧版 JSON 数据文件 | `通讯录数据.json` | 选文件夹时若发现会自动提示迁移成 `.db` |

**目录结构（网页版）**
```
任意文件夹/
├── 通讯录.html          ← 双击即用（单文件，图标/SQLite引擎全内联）
├── db/
│   └── 通讯录数据.db     ← SQLite 数据库
└── backup/
    └── 通讯录_全部备份_20260910_084711.xlsx
```
> 首次点一次「选择 html 所在文件夹」授权（浏览器安全要求），`db/`、`backup/` 会自动创建；之后每次改动自动写库（防抖 1s），全部备份自动落到 `backup/`。

### SQLite 库结构
```sql
contacts(id TEXT PRIMARY KEY, name TEXT, province TEXT, codes TEXT, phones TEXT, created_at INTEGER, updated_at INTEGER)  -- codes/phones 为 JSON 数组文本
backups (id TEXT PRIMARY KEY, ts INTEGER, label TEXT, file_name TEXT, path TEXT, count INTEGER)
meta    (k TEXT PRIMARY KEY, v TEXT)
```

### 万级数据实测（1 万条联系人，Chrome）
| 操作 | 耗时 |
|---|---|
| 页面加载（IndexedDB→内存水合 10k 条） | 0.55~1.5s（水合约 0.2~0.3s） |
| 模糊查询「联系人1」（命中 1112 条） | 56ms |
| 精确查询（编码/电话） | 39~41ms |
| 翻到第 50 页 | 7ms |
| 新增/编辑一条（含落盘） | 50~180ms（1.6MB 全量写库，带防抖） |
| 导入 Excel 10000 行 | 0.73s |
| 生成 1 万条 Excel 备份 | 约 1~2s（按钮显示「处理中」） |
| 写 SQLite 库（1 万条, 1.4MB） | 155ms |
| 读 SQLite 库（1 万条） | 138ms 解析 + 39ms 入内存 |

### 为大数据做的优化
- **不再每次改动都全量 JSON 序列化**：改用 IndexedDB 结构化克隆 + 500ms 防抖落盘（批量导入时用 `pauseSave/resumeSave` 暂停、结束一次写入）。
- **列表只渲染当前页**（手机 7 条 / 桌面 8 条），不整表渲染。
- **联系人对象用 `markRaw`**：列表只读渲染，不为每条数据建深层响应式代理（加载提速约 3 倍）；修改后由代码显式触发落盘。
- **导入建索引 O(1) 匹配**：先建「编码/电话 → 联系人」索引，再批量插入，避免逐条 O(n) 扫描。
- **备份上限 30 条**；数据量 > 5000 时只保留最近 5 条完整快照，其余只留记录（提示用 Excel 恢复）。


## 本地版（推荐：双击就用，数据在 db 文件夹，零授权点击）
纯 html 受浏览器安全限制无法自己读写磁盘，所以想做到「双击就用 + 数据固定存在 db/contacts.db + 换浏览器不影响」就需要一个**本地小程序**：

```
通讯录本地版/
├── 启动.bat            ← 双击启动服务并自动打开浏览器（需要 Python 3）
├── 打包exe.bat         ← 双击一键打包成 contacts.exe（以后无需 Python）
├── contacts_local.py   ← 本地服务（纯 Python 标准库：静态页 + REST + SQLite）
├── 通讯录.html          ← 界面（与 index.html 同一份）
├── index.html
├── 使用说明.txt
├── db/contacts.db      ← 数据（首次启动自动创建）
└── backup/             ← 全部备份的 xlsx + 每天一份 .db 快照
```

- 打开地址 `http://127.0.0.1:19118`（端口被占用自动换）；浏览器里显示「🌐 本地版 · db/contacts.db」。
- 前端自动识别：http 打开且 `/api/health` 有响应 → 走本地服务模式（无授权、无引导）；`file://` 双击 html → 走原来的浏览器存储 + 可选文件夹模式。
- 接口：`GET /api/health`、`GET /api/db`、`PUT /api/db`、`GET /api/info`、`POST /api/backup-xlsx`、`GET /api/backup-files`、`DELETE /api/backup-file?name=`。
- 打包好的压缩包：`/vol2/1000/ai-projects/drop/通讯录本地版.zip`

## 电脑网页版（桌面程序界面 · 单文件 HTML）
电脑版**不是手机界面照搬**，而是独立的桌面网页应用：顶栏 + 左侧导航（通讯录 / 添加·修改 / 备份·导入）+ 表格化内容区，不做手机适配。

- 入口：`frontend/web.html` → `frontend/src/main-desktop.js` → `src/desktop/*.vue` + `src/desktop.css`（手机壳仍走 `index.html` → `main.js` → `src/views/*`）。
- 产出**单个 HTML**（JS/CSS **以及网页图标**全部内联，≈520KB），**双击即用，无需服务器/nginx**：`web/通讯录.html`；浏览器标签页会显示 App 图标（图标为 64×64 PNG 的 base64 data URI，由 `icon/icon.png.png` 生成）。
- 界面要点：联系人表（序号/姓名/编码/电话/更新时间/操作，每页 8 条，双击行或点姓名即编辑）、姓名与电话可直接点击操作、备份表（时间/类型/条数/文件/路径，每页 10 条）。
- 表单逻辑与手机端**共用** `src/useContactForm.js`（查重时机、保留新输入、二次确认等规则完全一致，避免两套行为漂移）。
- 数据存浏览器本地（localStorage），与手机 App **互不相通**；用「全部备份」导出 Excel → 另一端「完全导入」即可互通。
- 重新生成：
  ```bash
  cd frontend && npm run build:web       # 产出 frontend/dist-web/web.html
  cp dist-web/web.html "../web/通讯录.html"
  ```
- NAS 上已有成品：`/vol2/1000/ai-projects/contacts/web/通讯录.html`（另放一份在 `/vol2/1000/ai-projects/drop/通讯录.html`）。

## 目录结构
```
contacts/
├── frontend/                     # Vue3 + Vite（离线打包进 APK）
│   ├── src/db.js                 # 本地数据层（contacts / backups，localStorage）
│   ├── src/excel.js              # SheetJS 读写 Excel（已打进包，不联网）
│   ├── src/native.js             # 原生桥封装（保存文件/删除/拨号，浏览器自动降级）
│   ├── src/views/ContactsView.vue   # 栏目一：通讯录（查询/7条分页/拨打/删除）
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
