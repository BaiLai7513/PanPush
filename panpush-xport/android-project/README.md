# PanPush WebView Kit（Android 工程骨架）

一个**可直接用 Android Studio 打开**的最小工程，用来复现/验证
「应用内 WebView 登录 + 读自己 Cookie（无需 root）」的正确写法。

## 打开方式

1. Android Studio → Open → 选择本目录 `android-project/`。
2. 首次会自动同步 Gradle（需要联网拉 AGP 8.5.2 / Kotlin 1.9.24）。
3. 运行 `app` 模块。

> 环境要求：JDK 17、Android SDK 34、Gradle 8.7+（Android Studio 会自动下载 wrapper 之外的组件）。
> 本骨架**未内置 gradle wrapper**；若需要，在 Android Studio 里会提示自动补全，或命令行 `gradle wrapper`。

## 工程结构

```
android-project/
├─ settings.gradle / build.gradle / gradle.properties
└─ app/
   ├─ build.gradle
   ├─ proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/panpush/app/
      │   ├─ MainActivity.kt                      ← 示例：按钮 ①登录 ②读Cookie ③导出JSON
      │   └─ webview/
      │       ├─ WebViewLoginActivity.kt          ← 应用内登录页（已修 7 个持久化坑）
      │       ├─ CookieStore.kt                   ← 持久化 + 读取（含复制 DB+journal）
      │       └─ CookieInspector.kt               ← webview_ck.py 的 Kotlin 等价物（App 内，无需 root）
      └─ res/
          ├─ layout/activity_main.xml
          └─ values/{strings.xml, themes.xml}
```

## 三个类的职责

| 类 | 职责 | 是否需要 root |
|---|---|---|
| `WebViewLoginActivity` | 应用内 WebView 登录，登录成功回传 Cookie | 否 |
| `CookieStore` | flush / 取 Cookie / 组装请求头 / 读自己 WebView 库 | 否 |
| `CookieInspector` | 列 profile、读 Cookie、dump localStorage、导出 JSON | 否 |

> 读**自己** App 的登录态**不需要 root**。
> 读**别的 App** 的登录态才需要 root —— 用配套的 `../webview_ck.py`（Termux）。

## 换站点只改三个值

```kotlin
WebViewLoginActivity.buildIntent(
    this,
    loginUrl    = "https://pan.quark.cn/",   // 站点首页（未登录会自动跳登录）
    checkHost   = "pan.quark.cn",            // 判定登录成功的域名
    checkCookie = "ctoken"                   // 该域名下出现此 cookie 即视为已登录
)
```

常见站点判定值（实测自 WebView 库）：

| 站点 | checkHost | checkCookie |
|---|---|---|
| 夸克 | `pan.quark.cn` | `ctoken` |
| 百度网盘 | `pan.baidu.com` | `PANWEB` |
| 123 云盘 | `www.123pan.com` | `_c_WBKFRo` |

> 123 云盘的登录 token 也可能在 localStorage 里，用 `CookieInspector.dumpLocalStorage()` 一并看。

## 已知边界

- 本骨架是**示例/验证工程**，未包含下载器主体逻辑。
- WebView 的 Cookie 明文存 `value` 列；`encrypted_value` 非空才加密（本场景通常为空）。
- 不同设备 WebView 版本可能影响第三方 Cookie 行为（已在代码中开启）。