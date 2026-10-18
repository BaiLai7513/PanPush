# 交接说明 · 给 harness

> 主题：修复 `com.panpush.app`（v1.2.1，原生 Android + WebView）的
> **「浏览器登录存不下登录态」** 与 **「root 读不到 cookie/token」** 两个问题。
> 附：可直接用的代码、验证标准、以及请勿踩的红线。
> 环境：OnePlus PKR110（APatch Root）· Termux Python 3.14 · 2026-10-11 真机实测。

---

## 一、先给结论（TL;DR）

| 问题 | 结论 |
|---|---|
| 登录态存不下 | **登录用了外部浏览器/CustomTabs → Cookie 在浏览器沙箱，App 永远拿不到。这是设计错误，必须改应用内 WebView。** |
| root 读不到 | **数据不在 `shared_prefs`/`databases`，而在 `app_webview/Default/`；且不能用 `run-as`（非 debuggable），要用 `su`；读前要 `flush()`/复制 DB+journal。** |
| 读自己 App 的登录态 | **不需要 root。** root 只用于读**别的 App**。 |

---

## 二、问题 1：浏览器登录存不下

### 判定
两种登录方式，病因完全不同：

**A. 用外部浏览器 / CustomTabs（`Intent(ACTION_VIEW)` / `CustomTabsIntent`）**
- **病因**：Cookie 存在**浏览器沙箱**，本 App 的 WebView 在**另一个沙箱**，读不到 → 登录"存不下"是必然。
- **修法**：改用**应用内 `WebView`**（见 `WebViewLoginActivity.kt`）。

**B. 用应用内 WebView 仍丢**
逐条对照修复（`WebViewLoginActivity.kt` 已内建，注释编号①~⑦）：

| # | 修复点 | 不做会怎样 |
|---|---|---|
| ① | `CookieManager.flush()`（onPageFinished / onPause / onDestroy） | Cookie 只在内存，进程被杀即丢 |
| ② | `setAcceptThirdPartyCookies(webView, true)` | 跨域 SSO（百度 `wappass→pan.baidu.com`、夸克 passport）丢 Cookie |
| ③ | 不 incognito / 不清 Cookie | 每次登录后自己把状态清掉 |
| ④ | `domStorageEnabled = true`（+ databaseEnabled） | 123pan 等存在 localStorage 的 token 存不下 |
| ⑤ | 保持 HTTPS、不伪装 UA、允许混合内容 | `SameSite=None; Secure` 被丢 |
| ⑥ | 全进程统一 `CookieManager.getInstance()` | 多 WebView 不共享 |
| ⑦ | 固定默认 `app_webview` profile | 换 profile / 清数据 = 丢登录态 |

### 自查判据
登录完成后，`CookieManager.getInstance().getCookie(登录域名)` 非空 **且** 已 `flush()` ⇒ 必能持久化；否则就是上表某条没做对。

---

## 三、问题 2：root 读不到 cookie/token

### 判定（实测证据）
- `cat /data/data/com.panpush.app/app_webview/Default/Cookies` → `SQLite format 3` ✅ root 能读。
- sqlite 读出 **18 条明文 cookie**（`value` 列有值，`encrypted_value` 为空）。

所以"读不到"不是权限问题，而是三条之一：

1. **找错目录**。该 App **没有 `databases/`**；`shared_prefs/` 只有 `WebViewChromiumPrefs.xml`、`AwOriginVisitLoggerPrefs.xml`，**不含 token**。登录态全在：
   ```
   <dataDir>/app_webview/Default/Cookies                     ← Cookie（SQLite，明文）
   <dataDir>/app_webview/Default/Local Storage/leveldb/      ← localStorage（如 123pan 的 LoginUuidA）
   <dataDir>/app_webview/Default/IndexedDB/                  ← 123pan / wappass.baidu.com
   <dataDir>/app_webview/Default/Session Storage/
   ```
   （`<dataDir>` = `/data/user/0/com.panpush.app`，即 `/data/data/…` 的真身。）

2. **用了 `run-as`**。该 App 非 debuggable：
   ```
   run-as com.panpush.app …  →  run-as: package not debuggable: com.panpush.app
   ```
   `run-as` 是调试权限、**不是 root**，必失败。**必须 `su`。**

3. **读了活库**。WebView 进程持锁 + journal 模式，新 Cookie 可能仅在内存。
   **正确姿势**：先 `flush()`（或杀进程）→ 复制 **Cookies + Cookies-journal** → sqlite 只读打开（让其自行 rollback）。

### 附：实测读到的内容（供对照）
| host | cookie |
|---|---|
| `pan.quark.cn` | `ctoken` |
| `.123pan.com` | `_c_WBKFRo` |
| `.baidu.com` / `.pan.baidu.com` | `BAIDUID`、`BAIDUID_BFESS`、`PANWEB`、`fuid` |
| `wappass.baidu.com` | `XFI`、`XFT` |
| `xlab.baidu.com` | `XFS` |
| `h5.sm.cn` | `b-user-id`、`sm_uuid` |

---

## 四、交付物清单

**目录：`/sdcard/Make/Mk/panpush/`**

| 路径 | 说明 |
|---|---|
| `android/WebViewLoginActivity.kt` | 应用内登录页（修 7 坑），含 `buildIntent()` |
| `android/CookieStore.kt` | Cookie 持久化 + 读取（含 `copyAndRead` 复制 DB+journal） |
| `android/README.md` | 上面两个类的接入说明 |
| `android-project/` | **可直接用 Android Studio 打开的工程骨架**（含 `MainActivity` 示例、`CookieInspector`） |
| `android-project/app/src/main/java/com/panpush/app/webview/CookieInspector.kt` | `webview_ck.py` 的 **Kotlin 等价物**（App 内，无需 root） |
| `docs/交给harness的交接说明.md` | 本文件 |
| `webview_ck.py` | **root 读任意 App** 的 WebView cookie/localStorage（Termux，零依赖） |

**打包**：`/sdcard/Make/panpush-xunlei-export.zip`

> 说明：`CookieInspector.kt`（Kotlin，读**自己**）与 `webview_ck.py`（Python，读**别人**、要 root）职责等价，只是运行环境不同。

---

## 五、验收标准（请逐条跑）

### A. 应用内登录（Kotlin）
1. 打开 `android-project`，运行 App。
2. 点 ① 登录 → 登录成功后应回到主界面并打印 Cookie 字符串。
3. 杀进程 → 重开 → 点 ② 读取 → **仍能读到非空 Cookie**（证明已持久化）。
4. 点 ③ 导出 JSON → 应含 `cookies` 与 `localStorage` 两段。

### B. root 读取（Termux）
```bash
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app --host quark
python3 /sdcard/Make/Mk/panpush/webview_ck.py com.panpush.app --json
```
- 期望：打印 `profile = /data/user/0/com.panpush.app/app_webview/Default`，列出 cookie；
  `--host quark` 命中 `pan.quark.cn ctoken`。

> 参考：本次实测 webview_ck.py 读出 **18 条 cookie**，`--host quark` 正确命中。

---

## 六、红线（请勿做的改动）

1. **不要**把登录改回外部浏览器 / CustomTabs —— 那是本 bug 的根因，不是"再补个读取逻辑"能救的。
2. **不要**在校验/启动时调用 `CookieManager.removeAllCookies()` / `clearCache()` / `clearHistory()`，会亲手清掉登录态。
3. **不要**用 `run-as` 读这个 App 的数据（非 debuggable，必失败）；用 `su`。
4. **不要**在 `shared_prefs` / `databases` 里找 token（本 App 没有）；只有 `app_webview/`。
5. **不要**读活的 Cookies 库当作准 —— 必须先 `flush()`/杀进程，或复制 DB+journal。
6. **不要**为读自己 App 的登录态去申请 root —— 没必要，且会引入授权失败风险。

---

## 七、设计建议（长期）

1. **统一入口**：所有登录都走 `WebViewLoginActivity`，别各页面各写一套 WebView（配置漂移必再犯坑②④）。
2. **登录成功判据可配置**：把 `checkHost` + `checkCookie` 做成每站点配置项（表见 `android-project/README.md`）。
3. **Cookie 读取分层**：运行期走 `CookieManager`；离线/兜底走 `CookieStore.copyAndRead`；跨 App 才用 `webview_ck.py`。
4. **与解析器联动**：拿到 Cookie 后，直接通过 `CookieStore.toRequestHeaders(host)` 注入下载器（如 Gopeed/OkHttp）请求头，避免二次拼接出错。
5. **埋点排障**：登录后打一行 `getCookie(host)` 的摘要日志，一劳永逸定位"存没存"。

---

## 八、事实附录

- 包名 `com.panpush.app`；版本 1.2.1（versionCode 4）；minSdk 26 / targetSdk 35；arm64-v8a；**非 debuggable**。
- `dataDir = /data/user/0/com.panpush.app`（`/data/data/…` 为符号链接）。
- WebView profile：`app_webview/Default`；Cookies 文件 `-rw------- u0_a623`，SELinux `app_data_file`。root（`su`）可读。
- Cookies 库 schema：`meta`、`cookies`；关键列 `host_key,name,value,encrypted_value,is_secure,is_httponly,expires_utc`。
- 本文所有命令均在真机执行并核对过输出。

---

## 九、我想直接对你说的话

1. **这个 bug 的根因是"架构选型"，不是"少写一行 flush"。** 先用 `adb`/日志确认登录页到底是外部浏览器还是应用内 WebView —— 这一步定错，后面全白修。
2. **"root 读不到"多半是自我误导。** 数据一直在、也能读，只是不在你以为的地方。请先按本文附录确认真实路径，再谈"读不到"。
3. **两个问题的表象不同，但都卡在同一个认知上**：把 WebView 当成"黑盒浏览器"。它其实是 App 自己的一块持久存储（`app_webview/`），用对了它就非常可控。
4. **先跑验收清单，再动代码。** 尤其是"杀进程后仍能读到 Cookie"这一条 —— 它是唯一能真正证明修复有效的判据。
5. 需要联调（例如把抽到的 Cookie 直接喂给解析器/Gopeed）时，`CookieStore.toRequestHeaders(host)` 和 `webview_ck.py --json` 就是你的两个对接点。

—— 以上如有疑问，按「事实附录」里的路径与命令复现即可得到同样结论。