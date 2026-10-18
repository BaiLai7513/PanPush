# panpush WebView 登录 / Cookie 修复代码包

针对 `com.panpush.app`（v1.2.1，原生 Android + WebView，非 debuggable）的问题修复代码。

## 文件

| 文件 | 用途 | 运行环境 |
|---|---|---|
| `WebViewLoginActivity.kt` | **应用内 WebView 登录页**（已修 7 个持久化坑） | App 内（Kotlin） |
| `CookieStore.kt` | Cookie 持久化 + 读取（含无需 root 读自己 WebView 库） | App 内（Kotlin） |
| `../webview_ck.py` | **root 读取「任意 App」的 WebView cookie/localStorage** | Termux（Python，零依赖） |

## 一、修的是什么

### 1)「浏览器登录存不状态」→ 必须改应用内 WebView

- ❌ 外部浏览器 / CustomTabs：Cookie 在**浏览器沙箱**，App 永远读不到 → 设计错误。
- ✅ 用 `WebViewLoginActivity`（应用内 WebView）。

`WebViewLoginActivity` 已内置 7 个修复：

| # | 修复点 | 位置 |
|---|---|---|
| 1 | `CookieManager.flush()`（onPageFinished / onPause / onDestroy） | 内存 Cookie 落盘 |
| 2 | `setAcceptThirdPartyCookies(webView, true)` | 跨域 SSO（百度 `wappass→pan.baidu.com`、夸克 passport） |
| 3 | 不 incognito / 不清 Cookie | 保持持久 profile |
| 4 | `domStorageEnabled = true`（+ databaseEnabled） | 123pan 等的 token 存 localStorage |
| 5 | 保持 HTTPS、不伪装 UA、允许混合内容 | `SameSite=None; Secure` 不被丢 |
| 6 | 全进程统一 `CookieManager.getInstance()` | 多 WebView 共享 |
| 7 | 固定默认 `app_webview` profile | 别换 profile / 别清数据 |

### 2)「root 读不到登录态」→ 三个真实原因

1. **找错目录**：本 App 没有 `databases/`，`shared_prefs/` 只有 WebView 配置、**无 token**；登录态全在 `app_webview/Default/{Cookies, Local Storage/leveldb, IndexedDB}`。
2. **用了 `run-as`**：本 App 非 debuggable，`run-as` 必失败；**要用 `su`**。
3. **读了活库**：WebView 持锁 + journal 模式，新 Cookie 可能未落盘 → 先 `flush()`/杀进程，**复制 DB + journal** 再读。

> 注：**读自己 App 的登录态不需要 root**（`CookieStore` 通道 A/B 直接可读）。root 仅用于读**别的 App**（用 `webview_ck.py`）。

## 二、接入方式（Kotlin）

Gradle（一般模板已含）：

```gradle
implementation "androidx.appcompat:appcompat:1.6.1"
```

Manifest：

```xml
<activity
    android:name=".webview.WebViewLoginActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:exported="false" />
<uses-permission android:name="android.permission.INTERNET" />
```

调用登录：

```kotlin
val it = Intent(this, WebViewLoginActivity::class.java).apply {
    putExtra(WebViewLoginActivity.EXTRA_LOGIN_URL, "https://pan.quark.cn/")  // 站点首页，未登录会跳登录
    putExtra(WebViewLoginActivity.EXTRA_CHECK_HOST, "pan.quark.cn")        // 判定登录成功的域名
    putExtra(WebViewLoginActivity.EXTRA_CHECK_COOKIE, "ctoken")            // 出现该 cookie 即视为已登录
}
loginLauncher.launch(it)

// 回调：
val cookie = result.data?.getStringExtra(WebViewLoginActivity.EXTRA_RESULT_COOKIE)
```

读 Cookie（运行期，推荐）：

```kotlin
CookieStore.flush()
val ck = CookieStore.cookieHeader("pan.quark.cn")          // "a=1; b=2"
val token = CookieStore.cookieValue("pan.quark.cn", "ctoken")
```

读 Cookie（离线/兜底，无需 root）：

```kotlin
val rows = CookieStore.copyAndRead(context, hostFilter = "quark")
```

## 三、root 读取别的 App（Termux）

```bash
# 全量
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app
# 只看某站点
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app --host quark
# JSON（供上级管道/归档）
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app --json
# 附带 localStorage
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app --localstorage
```

## 四、验证记录（真机）

- `webview_ck.py` 对 `com.panpush.app` 实读 **18 条 cookie**（明文），过滤 `--host quark` 正确命中 `pan.quark.cn ctoken`。
- `webview_ck.py` `py_compile` 通过。
- 定位到：`profile = /data/user/0/com.panpush.app/app_webview/Default`，cookie 文件 `['Cookies']`。