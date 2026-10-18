package com.panpush.app.cred

import com.panpush.app.core.SiteId
import com.panpush.app.net.Http
import com.panpush.app.net.Js

/**
 * 凭据在线校验（对齐 CLI 的 check_cookie / validate）。
 *   baidu → GET /rest/2.0/xpan/nas?method=uinfo   （errno -6 = Invalid Bduss）
 *   123   → GET /b/api/user/info                  （code 0 = 有效）
 *   quark → 无稳定公开接口，只做字段级弱校验
 */
object CredentialValidator {

    data class Verdict(val valid: Boolean?, val message: String) {
        val label: String
            get() = when (valid) {
                true -> "✓ 有效"
                false -> "✗ 无效"
                else -> "? 未知"
            }
    }

    const val BAIDU_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 netdisk;"

    fun validate(site: SiteId, credential: String?): Verdict {
        if (credential.isNullOrBlank()) return Verdict(false, "未配置凭据")
        return when (site) {
            SiteId.BAIDU -> validateBaidu(credential)
            SiteId.PAN123 -> validate123(credential)
            SiteId.QUARK, SiteId.UC ->
                if (credential.contains("__puus")) {
                    Verdict(true, "包含 __puus（字段齐全）")
                } else {
                    Verdict(
                        false,
                        "只有可见 cookie（缺 __puus）→ 取直链会被拦（23018/31001）。" +
                            "请点「应用内登录」用桌面 UA 重新登一次（WebView 能读回 httpOnly 的 __puus）"
                    )
                }
            else -> Verdict(null, "该站点暂不支持在线校验")
        }
    }

    /**
     * 「关键令牌」是否齐全 —— 不联网、只看字段。
     *
     * ⚠️ 真机踩坑（2026-10-12 实测 `com.panpush.app/shared_prefs/panpush.xml`）：
     * 当时缓存的三个凭据**全是残缺的**：
     *   · `cred_quark = ctoken=…`             → 只有 ctoken，**缺 __puus** → 取直链 23018/31001
     *   · `cred_baidu = PANWEB=1; BAIDUID=…`  → 只有 PANWEB/BAIDUID，**缺 BDUSS** → 下载 31360
     *   · `cred_123   = token=b49683453c…`    → 64 位十六进制，**不是 JWT** → 接口 401
     * 这些正是「内置浏览器登录后能看到的**非 httpOnly** cookie」，
     * 而解析要的是 httpOnly / App 私有的那一个。所以界面必须能一眼看出「残缺」。
     *
     * ⚠️ 但**夸克不能靠 root 直读**去补（真机逐库核对：三个浏览器库 32+9+0 行，
     * 只有 `b./m.quark.cn`，没有 `pan.quark.cn`/`__puus`）。
     * 夸克要靠**应用内登录 + 桌面 UA**：`CookieManager.getCookie()` 是 Native 接口，
     * 不受 httpOnly 限制，能读回 `__puus`（见 `quark-puus/夸克__puus获取实现.md`）。
     */
    fun keyMissing(site: SiteId, credential: String?): String? {
        if (credential.isNullOrBlank()) return "未配置"
        return when (site) {
            SiteId.QUARK, SiteId.UC ->
                if (credential.contains("__puus")) null else "__puus"
            SiteId.BAIDU ->
                if (Regex("(^|;\\s*)BDUSS=").containsMatchIn(credential)) null else "BDUSS"
            SiteId.PAN123 -> {
                val m = Regex("token=([^;\\s]+)").find(credential)
                    ?: Regex("([A-Za-z0-9._\\-]{20,})").find(credential)
                val t = m?.groupValues?.get(1) ?: return "token"
                // 123 的 App token 是 JWT（eyJ…）；网页那串 64 位 hex 不是，接口会拒
                if (t.startsWith("eyJ")) null else "有效的 JWT（当前这个不是 JWT）"
            }
            else -> null
        }
    }

    /** 是否「就绪」：关键令牌齐全 */
    fun ready(site: SiteId, credential: String?): Boolean = keyMissing(site, credential) == null

    private fun validateBaidu(cookie: String): Verdict {
        val url = "https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo&clienttype=0&app_id=250528&web=1"
        val h = mapOf(
            "User-Agent" to BAIDU_UA,
            "Referer" to "https://pan.baidu.com/",
            "Cookie" to cookie
        )
        val r = Http.get(url, h, 20)
        val j = Js.obj(r.text) ?: return Verdict(null, "校验响应异常（HTTP ${r.status}）：${r.text.take(100)}")
        val errno = j.optInt("errno", -999)
        return when (errno) {
            0 -> {
                val name = j.optString("netdisk_name").ifBlank { j.optString("baidu_name") }.ifBlank { "?" }
                Verdict(true, "有效（账号：$name，VIP：${j.opt("vip_type")}）")
            }
            -6 -> Verdict(false, "已失效/未登录（errno -6 Invalid Bduss）→ 请重新登录百度网盘")
            else -> Verdict(false, "errno=$errno ${j.optString("errmsg")}")
        }
    }

    private fun validate123(credential: String): Verdict {
        val m = Regex("token=([^;\\s]+)").find(credential)
            ?: Regex("([A-Za-z0-9._\\-]{40,})").find(credential)
            ?: return Verdict(false, "未找到 token 字段")
        val token = m.groupValues[1]
        val h = mapOf(
            "Authorization" to "Bearer $token",
            "platform" to "web",
            "app-version" to "3",
            "Referer" to "https://www.123pan.com/",
            "Origin" to "https://www.123pan.com"
        )
        val r = Http.get("https://www.123pan.cn/b/api/user/info", h, 20)
        val j = Js.obj(r.text) ?: return Verdict(null, "校验响应异常（HTTP ${r.status}）：${r.text.take(100)}")
        return when (j.optInt("code", -999)) {
            0 -> {
                val d = j.optJSONObject("data")
                Verdict(true, "有效（账号：${d?.optString("Nickname")}，Vip：${d?.opt("Vip")}）")
            }
            401 -> Verdict(false, "已掉登录（401 cookie token is empty）→ 请重新登录 123 云盘 APP")
            else -> Verdict(false, "code=${j.opt("code")} ${j.optString("message")}")
        }
    }
}
