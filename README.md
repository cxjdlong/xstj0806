# 手机维修（安卓 App）

「多门店销售统计系统」的安卓客户端 —— 全屏 WebView 壳，加载手机版界面 `https://xs.dx66.top:8888/#/m/home`。

## 特性
- **软件名**：手机维修
- **图标**：取自项目 `logo.ico`（与电脑 exe 版同一图标），已生成各密度 PNG + 自适应图标（白底）
- 登录态持久化（cookie / localStorage），不用每次登录
- 下拉刷新、返回键回退、加载进度条、打不开时给「重新加载」按钮
- **支持网页里的传照片**：`<input type="file">` 可选相册多张，也可直接调相机拍照（维修留痕传接机照/完工照必需）
- 站外链接（tel: 等）交给系统处理

## 编译（本机无 Android SDK，走 GitHub Actions）
推送到本仓库的 `repair-app` 分支即自动构建（`.github/workflows/build-apk.yml`，`assembleDebug` 自带 debug 签名，可直接安装）。

下载路径：GitHub 仓库 → **Actions** → 最新一次成功的 run → 底部 **Artifacts** → `phone-repair-debug-apk`。
⚠️ 下载到的是 **zip**，先解压出 `app-debug.apk` 再传到手机安装（直接把 zip 当 apk 装会报「解析包错误」）。

## 本地编译（可选）
用 Android Studio 打开本目录即可；或命令行 `gradle assembleDebug`（需自备 Android SDK + JDK 17 + Gradle 8.9）。

## 改后端地址
`app/src/main/java/com/xs/repair/MainActivity.kt` 里的 `APP_URL`：
- 外网（默认）：`https://xs.dx66.top:8888/#/m/home`
- 家里内网：`http://192.168.10.10:19117/#/m/home`
