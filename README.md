# 手机维修（安卓 App）

「多门店销售统计系统」的安卓客户端 —— 全屏 WebView 壳，加载手机版界面 `https://xs.dx66.top:8888/#/m/home`。

## 特性
- **软件名**：手机维修
- **服务器地址不内置**：第一次打开让用户填，填过就记住；要换服务器从错误页点「修改服务器地址」
- **图标**：取自项目 `logo.ico`（与电脑 exe 版同一图标），已生成各密度 PNG + 自适应图标（白底）
- 登录态持久化（cookie / localStorage），不用每次登录
- 下拉刷新、返回键回退、加载进度条、打不开时给「重新加载 / 修改服务器地址」
- **支持网页里的传照片**：`<input type="file">` 可选相册多张，也可直接调相机拍照（点「📷 拍照」会直接开相机）
- 站外链接（tel: 等）交给系统处理

## 编译（本机无 Android SDK，走 GitHub Actions）
推送到本仓库的 `repair-app` 分支即自动构建（`.github/workflows/build-apk.yml`，`assembleDebug` 自带 debug 签名，可直接安装）。

下载路径：GitHub 仓库 → **Actions** → 最新一次成功的 run → 底部 **Artifacts** → `phone-repair-debug-apk`。
⚠️ 下载到的是 **zip**，先解压出 `app-debug.apk` 再传到手机安装（直接把 zip 当 apk 装会报「解析包错误」）。

## 本地编译（可选）
用 Android Studio 打开本目录即可；或命令行 `gradle assembleDebug`（需自备 Android SDK + JDK 17 + Gradle 8.9）。

## 改服务器地址
两种方式：
1. App 里打不开时，点错误页的「修改服务器地址」重新填；
2. 或改这里的内置默认值（现在默认是空的，不内置任何地址）。

`app/src/main/java/com/xs/repair/MainActivity.kt` 里的 `HOME_PATH` 决定打开后落在哪一页（默认手机版 `/#/m/home`）。
常用地址：
- 家里/店里内网：`192.168.10.10:19117`
- 外网：`xs.dx66.top:8888`（该域名目前只有 IPv6 解析，需要手机支持 IPv6）
