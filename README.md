# Gemini Web Wrapper（无 GMS 安卓套壳应用）

一款专为**无 GMS（Google Mobile Services）架构的安卓设备**设计的轻量级 Gemini 网页端套壳工程（纯 Kotlin + Android System WebView），打开即进入 `https://gemini.google.com/app`，提供接近原生 App 的沉浸式体验。

---

## 一、核心架构设计

| 模块 | 实现方案 | 解决的问题 |
| :--- | :--- | :--- |
| **零 GMS 依赖** | 仅引用 AndroidX Core / AppCompat / WebKit / Material3，移除全部 `com.google.android.gms` 与 Firebase 依赖 | 在缺失 GMS 的手机上直接安装运行，不闪退、不弹窗提示安装 Google Play 服务 |
| **登录拦截适配** | 动态清理默认 WebView User-Agent 中的 `; wv` 与 `Version/x.x` 标识，并开启持久化 Cookie 与第三方 Cookie | 应对 Google 对嵌入式 WebView 的 `403: disallowed_useragent` 登录限制（详见[说明](https://butleru.my.site.com/askbutler/s/article/Google-Sign-in-Error-disallowed-useragent)） |
| **多模态交互** | 实现 `onShowFileChooser`（支持多图/文档上传）与 `onPermissionRequest`（动态申请麦克风与相机权限） | 正常使用 Gemini 的图片/PDF 上传与语音输入功能 |
| **智能链接分流** | 白名单域名（`gemini.google.com`、`accounts.google.com` 等）在应用内加载，外部搜索引用链接交由系统浏览器打开 | 保证登录和对话连贯，点击参考资料不会覆盖当前聊天页 |
| **原生交互体验** | `enableEdgeToEdge()` 全面屏适配、自动跟随深色/浅色主题、软键盘 `adjustResize`、返回键历史回退 + 双击退出、断网错误页重试 | 贴近原生 App 手感，横竖屏切换不刷新丢失对话 |

---

## 二、如何打包为 APK 安装到手机

### 方式 A：本地使用 Android Studio 编译（推荐）
1. 解压本压缩包，在 **Android Studio** 中选择 **Open** 打开 `GeminiWebWrapper` 文件夹。
2. 等待 Gradle 自动同步依赖（需 JDK 17）。
3. 点击顶部菜单 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. 在 `app/build/outputs/apk/debug/app-debug.apk` 找到生成的安装包，传输到手机安装即可。

### 方式 B：免装环境，使用 GitHub Actions 云端自动打包
本工程已内置 `.github/workflows/build-apk.yml`：
1. 在 GitHub 新建一个仓库（可设为 Private 私有仓库），将本目录内全部文件上传推送至 `main` 分支。
2. 进入仓库的 **Actions** 页面，等待 **Build Android APK** 任务执行完成（约 2–3 分钟）。
3. 在任务底部的 **Artifacts** 中直接下载 `GeminiWebWrapper-debug-apk`，解压得到 APK 安装到手机。

---

## 三、无 GMS 设备的常见问题与备选方案

1. **登录仍提示 `403: disallowed_useragent` 怎么办？**
   Google 会不定期收紧对嵌入式 WebView 的风控检测（参考 [Stack Overflow 讨论](https://stackoverflow.com/questions/65353812/web-google-auth-workaround-for-403-disallowed-useragent-exists)）。若修改 UA 仍被拦截，可：
   - 方案 1（零代码、最稳定）：在手机上安装不依赖 GMS 的独立浏览器（如 **Firefox**、**Microsoft Edge** 或 **Via**），打开 `https://gemini.google.com`，点击浏览器菜单中的 **「添加到主屏幕」/「安装应用（PWA）」**，即可在桌面生成无地址栏的全屏独立图标，且完全走正规浏览器登录通道，不会被 403 拦截。
   - 方案 2（进阶自研）：将本工程的渲染引擎从 `android.webkit.WebView` 替换为 **Mozilla GeckoView**，彻底脱离系统 WebView 版本与风控限制（APK 体积会增加约 40MB）。

2. **打开页面白屏或样式错乱怎么办？**
   无 GMS 手机通常无法通过 Play 商店自动更新 **Android System WebView**，若系统自带内核过旧，可能无法渲染新版 Gemini 页面。可通过可信渠道更新手机的 Android System WebView 内核，或改用上述 Firefox / Edge 的 PWA 方式。
