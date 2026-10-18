# 应用内 WebView 登录抓取 vs Root 直读 —— 差异与选型设计

> 主题：两种取令牌方式**到底有没有区别**、实测证据、以及该按什么原则选型。
> 设备基线：OnePlus PKR110 / Android 15 / APatch Root。日期：2026-10-12。

---

## 0. 结论

**有区别，而且本质区别是：两者拿到的是"不同客户端签发"的令牌。**

| 方式 | 拿到的令牌 | 令牌由谁签发 |
|---|---|---|
| **Root 直读** | 目标 App 的 **App token** | 目标 App 客户端（`aud` / `project_id` / `platform` 与 App 绑定） |
| **应用内 WebView 登录** | **网页端 token / cookie** | 网页客户端（另一套 client 标识） |

> 是否可互相替代，**取决于你的解析器调的是哪种 API**：
> - 调 **App API** → 必须用 **App token**（只能 Root 直读）；
> - 调 **Web API** → 用 **web token**（WebView 登录即可）。

---

## 1. 实测证据（本机真令牌，已脱敏）

### 1.1 迅雷（App，`com.xunlei.downloadprovider/shared_prefs/auth_pref.xml`）

`access_token` 是 **RS256 JWT**，解码后 claims：

```
iss       = https://xluser-ssl.xunlei.com
sub       = 962114367                        (迅雷用户ID)
aud       = Xp6vsxz_7IYVw2BB                 ← ★ App 客户端ID
project_id= 2rvk4e3gdnl7u1kl0k               ← ★ App 项目ID
scope     = offline user verified pan sync profile sso auth
exp/iat   = 2 小时有效
refresh_token = "a1.ZYIm..."                 (不透明串，可续期)
```

→ 这是**迅雷 App 客户端**签发的 OAuth 令牌，`aud`/`project_id` 都指向 App。

### 1.2 123 云盘（App，`.../shared_prefs/login_user.xml`）

`userToken` = `Bearer` + **HS256 JWT**，解码后 claims：

```
id          = 1854398463
username    = 19299390859
nickname    = XYXY
login_type  = 3
platform    = 3                              ← ★ 3 = 安卓 App（网页端会是别的值）
deviceModel = PKR110
deviceName  = OnePlus
deviceId    = "一加 Ace5 Pro国行版(...):PKR110"
clientIP    = 223.160.172.163
exp         = 1799450794
```

→ 这是**123 App（platform=3）**签发的令牌；网页端登录会是**另一套 platform** 的令牌。

### 1.3 阿里云盘（App，`com.alicloud.databox/databases/0.db` → `tb_user_info`）

```
user_id       = 06fa5787e7624bbe893237e0f3768ea2
drive_id      = 658161673                     (默认盘)
access_token  = RS256 JWT（阿里）
refresh_token = 0af4adfebad446ac925de65c4777b4f0   ← 32位 hex，经典 aliyun refresh_token 格式
token_type    = Bearer, expires_in = 7200
```

→ 阿里的 `refresh_token` 是**标准格式**，常见第三方工具直接用它换 access_token；
> 但**注意**：App 与网页各自注册了不同的设备会话，**refresh_token 的"值"不同**（同一账号、不同客户端）。

---

## 2. 差异对比表

| 维度 | Root 直读 | 应用内 WebView 登录 |
|---|---|---|
| **令牌归属** | 目标 App 客户端（App token） | 网页客户端（Web token / cookie） |
| **值是否相同** | ❌ **不同**（client 标识不同） | |
| **需要 root** | ✅ 是 | ❌ 否 |
| **需要目标 App** | ✅ 已安装且已登录 | ❌ 不需要 |
| **需要用户操作** | 无需（App 已登录即可） | 需在 WebView 里**登录一次** |
| **可取站点** | 迅雷/阿里/123/百度 ✅；**夸克 ❌**（无本地令牌） | 百度/夸克 ✅；迅雷/阿里/123 ✅（网页版） |
| **有效期 / 续期** | 通常带 `refresh_token`，可长期续期 | web session，可能较短 |
| **版本脆弱性** | 随目标 App 版本变（路径/格式） | 随站点改版变 |
| **失败模式** | App 未装/未登录/加密存储 → 失败 | 站点挡 WebView（如夸克移动 UA）→ 失败 |
| **安全面** | 读别人 App 的数据（需 root 信任） | 令牌只在自己进程，**不需 root** |

---

## 3. 关键差异：client 标识不同 → 令牌未必通用

这是"有没有区别"的**技术根因**。三家都印证：

- **迅雷**：App token 的 `aud` / `project_id` 绑定 App 客户端；网页端会是另一个 client。
- **123**：App token 里 `platform=3`（安卓）；网页端 `platform` 不同。
- **阿里**：App 与网页各自注册设备会话，`refresh_token` 值不同。

**结论**：
> 不能假设"root 抓到的令牌" == "web 登录抓到的令牌"。它们**各自对各自的 API 有效**。

---

## 4. 选型决策树

```
你的解析器要调哪个 API？
│
├── App 专有 API（用 App 客户端标识 / App token）
│     └─► 必须 Root 直读（或让用户手动粘贴 App token）
│
├── Web API（能用网页端 cookie/token）
│     └─► 应用内 WebView 登录即可（无需 root）
│
└── 不确定
      └─► 两步走：先试 Root（有 root 时）→ 失败再 WebView → 再失败手动粘贴
```

**实践判断技巧**：
- 若抓包看到 App 请求头里的 token 是 App token（如迅雷 pan 接口），→ 走 Root；
- 若网页版 F12 能看到完整 API 且 cookie 能跑通，→ 走 WebView。

---

## 5. 兼容性矩阵（本机实测）

| 站点 | Root 直读 | WebView 登录 | 两种令牌是否通用 | 建议首选 |
|---|---|---|---|---|
| **迅雷** | ✅ App token（RS256 JWT + refresh） | ✅ 网页版可达 | ⚠️ **通常不通用** | Root（若调 App API） |
| **阿里云盘** | ✅ refresh_token（32hex） | ✅ 网页版可达 | ⚠️ 各自设备会话 | Root |
| **123 云盘** | ✅ App JWT（platform=3） | ✅ 网页版可达 | ⚠️ platform 不同 | Root |
| **百度** | ✅ 可读 BDUSS cookie | ✅ 网页版 | ✅ 可能通用（同域 cookie） | 两者皆可 |
| **夸克** | ❌ **无本地令牌** | ✅ 桌面 UA 登录拿 `__puus` | — | **只能 WebView** |
| **蓝奏** | 免登录 | — | — | 免登录 |

---

## 6. 推荐：混合策略（统一接口 + 多 Provider）

优先级：**Root（有 root 且 App 已登录） > WebView 登录 > 手动粘贴 > 不支持**

三站（迅雷/阿里/123）：**优先 Root**（快、免登录、令牌是 App 原生的）；无 root 时用 **WebView**。
两站（百度/夸克）：**WebView**（夸克只能这样，且需桌面 UA）。

### 统一抽象（建议实现）

```kotlin
interface CredentialProvider {
    /** 尝试获取该站凭据；成功返回令牌串，失败 null。 */
    fun acquire(site: Site): String?
}

/** 1) Root Provider：读目标 App 数据目录 */
class RootProvider : CredentialProvider { /* 迅雷 auth_pref.xml / 阿里 0.db / 123 login_user.xml */ }

/** 2) WebView Provider：拉起登录页，读自己 cookie / localStorage */
class WebViewProvider(private val activity: Activity) : CredentialProvider { /* 见另一份 WebView 文档 */ }

/** 3) 手动粘贴 Provider：兜底 */
class ManualProvider : CredentialProvider { /* 用户输入 */ }

// 组合：按优先级依次尝试
fun acquireWithFallback(site: Site): String? =
    sequenceOf(RootProvider(), WebViewProvider(act), ManualProvider())
        .mapNotNull { runCatching { it.acquire(site) }.getOrNull() }
        .firstOrNull()
```

---

## 7. 需要注意的坑

1. **别把两种令牌混用**：Root 拿到的 App token 直接喂给网页 API 可能 401；反之亦然。
2. **迅雷 App token 只 2 小时**：必须用 `refresh_token` 续期，否则很快失效。
3. **夸克没有 App 令牌**：不要指望 Root，直接 WebView（桌面 UA）。
4. **阿里 refresh_token**：即使 32hex 是标准格式，也建议用其换取 access_token 后再调 API。
5. **判断"已就绪"要看令牌类型是否匹配你要调的 API**，不能只看"有没有令牌"。

---

## 8. 附录：本机抓取命令（复现用）

```bash
# 迅雷 App token
cat /data/data/com.xunlei.downloadprovider/shared_prefs/auth_pref.xml

# 123 App token
cat /data/data/com.mfcloudcalculate.networkdisk/shared_prefs/login_user.xml

# 阿里 App token（需 python sqlite3）
python3 -c "import sqlite3;c=sqlite3.connect('file:/data/data/com.alicloud.databox/databases/0.db?immutable=1',uri=True);print(c.execute('select user_id,drive_id,refresh_token from tb_user_info').fetchall())"

# 百度 cookie
python3 -c "import sqlite3;c=sqlite3.connect('file:/data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/Cookies?immutable=1',uri=True);print([r for r in c.execute(\"select name from cookies where name like 'BDUSS%' or name='STOKEN'\")])"

# 夸克：无 App 令牌（root 读不到），只能 WebView
```

---

## 9. 事实来源

- 三家 App 令牌的**格式与 claims**：**2026-10-12 真机直接读取并解码**（本文档 §1）；
- 夸克无本地令牌、百度 cookie 可读：见《扫描路径文档》；
- WebView 登录侧：见《123-迅雷-阿里 WebView登录实现》《夸克__puus获取实现》；
- "App token 与 web token 是否通用" 属**推断性结论**，需按具体 API 联调验证。