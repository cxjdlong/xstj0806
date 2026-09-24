# music-add · 手机端加歌（Web + 安卓壳）

手机上用的小工具：浏览歌单 / 搜歌 → 页面内播放 → 下载进 NAS 曲库 → 加进飞牛音乐歌单。

本目录是**安卓壳 + CI**，Web 服务本体在 NAS 上运行（`/vol2/1000/docker/music-add`）。

```
music-add/
├── android/                      # 安卓壳（在线 WebView）
│   ├── app/src/main/java/com/musicadd/app/MainActivity.java
│   ├── app/src/main/assets/setup.html   # 内网地址设置页
│   ├── app/src/main/res/                # 图标（ico.ico 转出的各密度 PNG）
│   └── app/keystore.p12                 # 固定自签证书（云打包签名一致，可覆盖安装）
└── .github/workflows/build-apk.yml      # GitHub Actions 出 APK
```

## 安卓 App 说明

- **在线壳**：直接加载 `http://192.168.10.10:19018`（NAS 上的 music-add），页面自己做了手机自适应；壳层禁用缩放与回弹，不横向溢出。
- **首次启动**：显示地址设置页，填 NAS 地址即可（默认已填好）。
- **以后改地址**：连按返回键 3 次。
- **连不上**：自动显示设置页，可改地址重试（也支持 Lucky 反代域名，如 `yinyue.dx66.top:8888`）。
- **图标**：来自用户提供的 `ico.ico`（256×256），已生成 mdpi~xxxhdpi 全部密度 + 自适应图标前景。

## 云端编译 APK

推送到 GitHub 后自动触发（改了 `android/**` 也会触发）：

```
GitHub → Actions → Build APK → 跑完在 Artifacts 下载 music-add-apk.zip
解压得到 app-debug.apk  →  安装到手机
```

> ⚠️ Actions 产物是 **zip**，要先解压出 `app-debug.apk` 再装。

## 改哪些地方

| 想改什么 | 改哪里 |
|---|---|
| 默认服务器地址 | `MainActivity.java` 的 `DEFAULT_URL` |
| 应用名 | `app/src/main/res/values/strings.xml` |
| 图标 | 换 `res/mipmap-*/ic_launcher*.png` 和 `res/drawable/ic_launcher_foreground.png` |
| 版本号 | `app/build.gradle` 的 `versionCode/versionName` |
