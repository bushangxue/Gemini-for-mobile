# Gemini Web Wrapper v1.1（无 GMS 安卓套壳应用）

专为无 GMS 架构安卓/鸿蒙手机设计的轻量级 Gemini 网页端套壳工程（纯 Kotlin + Android System WebView），零 Google Play 服务依赖。

## v1.1 新增功能
- **App 内独立 HTTP 代理（ProxyController）**：点击右下角半透明快捷按钮（或断网错误页的「代理设置」）即可单独填写电脑局域网 IP 和端口（如 `7890`），无需修改手机系统 Wi-Fi 代理，不影响微信等其他 App 正常联网。
- **长按保存图片到相册**：长按 Gemini 生成或展示的图片即可直接保存到手机 `Pictures/Gemini` 相册（兼容 HTTP 链接、DataURL 与 Blob 图片）。
- **文件下载支持**：支持通过系统 `DownloadManager` 与 Blob JS 桥接下载网页端导出的文档、表格等文件到 `Downloads` 目录。
- **快捷工具菜单**：右下角迷你悬浮按钮支持一键打开代理设置、刷新页面、回到首页、切换桌面版/手机版。

## 打包方法
- **GitHub Actions 云端自动打包**：将本目录全部文件覆盖上传至仓库根目录，等待 **Actions → Build Android APK** 变绿后，在底部的 **Artifacts** 下载 `GeminiWebWrapper-v1.1-debug-apk`。
- **本地编译**：用 Android Studio 打开根目录，点击 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
