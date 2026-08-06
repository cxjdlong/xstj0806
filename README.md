# 门店管理系统 · 安卓 App

基于 **Kotlin + Jetpack Compose** 的安卓客户端，对接你的「多门店销售系统」后端（Flask）。

## 功能

1. **顶部**：标题「门店管理系统」+ 销售概览卡片（今日/本周/本月/当年 收入与毛利）
2. **中部**：可编辑文本框（语音识别结果展示 + 手动修改）
3. **底部**：
   - 左「按住说话」—— 按住开始录音识别（**本地**，不传云端），松开结束，识别文字自动填入文本框
   - 右「录入」—— 调 DeepSeek 把自然语言结构化 → 调后端 `POST /api/sales` 录入 → 刷新概览
4. **设置页**（右上角齿轮）：后端地址、登录（用户名/密码）、DeepSeek API Key
5. **离线草稿**（右上角📦图标）：断网时录入自动存本地草稿，联网后自动补录

## 语音识别：本地，不传云端

语音转文字用 **Android 系统 `SpeechRecognizer`**，在**手机本机**完成，**音频不离开设备**。
DeepSeek 只收到识别好的**文字**（用于结构化分析），全程无语音上传。

### 指定输入法引擎（客户可选）

设置页新增「**语音识别引擎**」区块，会自动枚举手机上所有已注册的语音识别服务（通常即各输入法引擎，如搜狗/百度/讯飞等）：

- **不选**（默认）→ 用系统当前输入法的引擎
- **选一个输入法** → App 的「按住说话」按钮会**直接调用该输入法引擎**识别

> 指定调用依赖 `SpeechRecognizer.createSpeechRecognizer(context, component)`，需 **Android 13 / API 33+**；低版本自动回退系统默认，不影响使用。

## 离线草稿机制

- **断网时点「录入」**：文字存入本地草稿（SharedPreferences，每条独立一条），顶部出现📦角标提示待补录数量
- **联网后自动补录**：检测到网络恢复（或 App 启动时），后台逐个把草稿交给 DeepSeek 格式化 → `POST /api/sales` 录入 → 标记「已录入」
- **草稿列表**：点📦可查看每条状态（待补录/已录入/失败），失败的可以**重试/删除**
- 多条记录 = 多条独立草稿；录入成功后才标记完成，避免崩溃丢数据

## AI 录入流程

```
语音(本地识别) ──▶ 文字入框 ──▶ 用户核对 ──▶ [录入]
   ├─ 有网 ──▶ DeepSeek 结构化分析 ──▶ POST /api/sales ──▶ 概览刷新
   └─ 断网 ──▶ 存本地草稿 ──▶ 联网后自动补录（同上）
```
DeepSeek API Key 用 **AES-GCM 加密**仅存在本机 `SharedPreferences`，只发送给 DeepSeek，**不上传你的服务器**。

## 目录结构

```
android-app/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                  # 图标/主题/字符串
│       └── java/com/xs/storemanager/
│           ├── MainActivity.kt   # 主界面(标题/概览/文本框/两按钮/权限/草稿弹窗)
│           ├── ui/SettingsDialog.kt  # 设置(登录/后端/DeepSeek Key)
│           ├── data/ApiClient.kt     # 后端客户端(login/dashboard/createSale)
│           ├── data/DeepSeekClient.kt # DeepSeek 结构化分析
│           ├── data/Models.kt
│           ├── data/SecurePrefs.kt   # AES 加密本地存储
│           ├── data/DraftsRepository.kt # 离线草稿本地持久化
│           ├── data/NetworkMonitor.kt  # 断网检测+联网回调(自动补录)
│           └── speech/VoiceRecognizer.kt # 本地语音识别(按住说话)
```

## 编译（二选一）

> 本仓库不含 `gradle-wrapper.jar`，需用 Android Studio 或 `gradle wrapper` 生成后编译。

**方式 A：Android Studio（推荐）**
1. 安装 Android Studio + JDK 17
2. `Open` → 选择 `android-app/` 目录
3. 等待 Gradle 同步
4. `Build → Build APK(s)`，产物在 `app/build/outputs/apk/`

**方式 B：命令行**
```bash
# 需要已安装 Android SDK + JDK 17，设置 ANDROID_HOME
cd android-app
gradle wrapper
./gradlew assembleDebug
```

## 配置（装到手机后）

| 配置项 | 默认值 / 说明 |
|--------|--------------|
| 后端地址 | `http://192.168.10.10:19117`（测试实例，可改 19017 正式）|
| 用户名 / 密码 | 你销售系统里的账号 |
| DeepSeek API Key | 在 [platform.deepseek.com](https://platform.deepseek.com) 申请 |

**注意**：
- 后端地址需手机**能访问到**。局域网内用 NAS 内网 IP；外网需先配好反代域名（如 `xs.dx66.top:8888`）。
- 若后端走 **HTTPS 域名**，可去掉 `usesCleartextTraffic` 和 network_security_config（现为开发方便已允许明文 HTTP）。
- 语音识别依赖**系统语音服务**（Google / 讯飞 / 百度输入法等）。国产 ROM 无 Google 服务时，装上讯飞/百度输入法即可提供。

## 后端接口依赖（已存在，无需改后端）

- `POST /api/auth/login` → 登录拿 token
- `GET  /api/stats/dashboard` → 概览数据（today_/week_/month_/year_ 前缀字段）
- `POST /api/sales` → 录入销售（`product_name/quantity/cost_price/sale_price/payment_method/remark`）

---

*项目位置：`/opt/projects/xs-app/`。需求文档：`手机app.txt`。*
