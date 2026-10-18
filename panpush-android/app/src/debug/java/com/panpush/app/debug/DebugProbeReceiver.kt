package com.panpush.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.panpush.app.PanPushApp
import com.panpush.app.core.SiteId
import com.panpush.app.cred.CredentialValidator
import com.panpush.app.cred.RootShell

/**
 * ## 仅在 debug 构建里存在的测试入口（生产包完全不含此类）
 *
 * 为什么需要它：验收标准要求「用真机 adb 验证到『已就绪』」。
 * 凭据读取是 App 内部逻辑，**adb 无法直接触发界面按钮** —— 但这个 receiver 可以：
 *
 * ```bash
 * adb shell am broadcast -a com.panpush.app.DEBUG_EXTRACT -p com.panpush.app
 * adb logcat -s PanPushDebug:I
 * ```
 *
 * 它会跑**与界面按钮完全相同**的那几条代码路径：
 *  - `extractor.extract(site)`（百度/123 的 cookie 库明文读）
 *  - `aliyunCreds.readAppCreds()`（阿里 `0.db` + WAL）
 *  - `xunleiCreds.load()`（迅雷三件套）
 *  - `CredentialValidator.keyMissing()` → 逐站点「已就绪 / 未就绪」
 *
 * ⚠️ 它只读不写业务数据（不覆盖已保存的凭据），也不联网。
 * 只有 `src/debug/` 下的这份源集会打进 debug APK，release 包里没有这个类。
 *
 * 另外它**不是 exported 的敏感入口**：只有 `-p com.panpush.app` 指定包名（或同 uid）才能投递，
 * 且只在 debug 包存在。
 */
class DebugProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            // ★ 从 App 内部拉起内置登录页（该 Activity 不 exported，adb 直接 am start 会被拒）。
            //   用来验证指纹伪装是否真的生效：日志 tag = PanPushStealth
            //   adb shell am broadcast -a com.panpush.app.DEBUG_WEBVIEW -p com.panpush.app --es site ALI
            ACTION_WEBVIEW -> {
                val which = intent.getStringExtra("site")?.lowercase() ?: "aliyun"
                val s = SiteId.fromKey(which)
                val i = com.panpush.app.webview.WebViewLoginActivity.buildIntent(context, s).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(i)
                    android.util.Log.i(TAG, "已拉起内置登录页：${s.label}（指纹日志看 PanPushStealth）")
                } catch (e: Exception) {
                    android.util.Log.i(TAG, "拉起失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }
            // ★ 查看「网页登录到底抓到了什么」—— 用来判断能不能续期
            //   adb shell am broadcast -a com.panpush.app.DEBUG_WEBCRED -p com.panpush.app
            ACTION_WEBCRED -> {
                Thread {
                    val sb = StringBuilder()
                    sb.append("==== 网页登录凭据落地情况 ====\n")
                    val ctx = context
                    // 阿里的手动/网页凭据
                    try {
                        val a = com.panpush.app.cred.AliyunCredStore(ctx).readManualCreds()
                        if (a == null) {
                            sb.append("阿里(网页) = 无\n")
                        } else {
                            sb.append("阿里(网页) source=${a.source} client=${a.clientId.ifBlank { "(App)" }}\n")
                            sb.append("   access=${a.accessToken.length} refresh=${a.refreshToken.length}")
                            sb.append(" expire=${a.expireTime.ifBlank { "(空!)" }} 剩余=${a.remainingSec()}s\n")
                            // ★ debug 自测：**实测阿里的网页续期通道**（非 root 续有效期的核心）
                            if (a.refreshToken.isNotBlank()) {
                                val store = com.panpush.app.cred.AliyunCredStore(ctx)
                                val n = store.refresh(a.refreshToken, a.driveId, a.clientId)
                                sb.append(
                                    if (n == null) {
                                        "   [自测] 续期失败：" + store.lastRefreshError + "\n"
                                    } else {
                                        "   [自测] ✓ 续期成功 access=${n.accessToken.length} " +
                                            "refresh=${n.refreshToken.length} expire=${n.expireTime}\n"
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        sb.append("阿里 读取异常 ${e.message}\n")
                    }
                    // 迅雷的网页令牌
                    try {
                        val x = com.panpush.app.cred.XunleiCredStore(ctx)
                        val w = x.readWebToken()
                        if (w == null) {
                            sb.append("迅雷(网页) = 无\n")
                        } else {
                            sb.append("迅雷(网页) source=${w.source} isWeb=${w.isWebToken}\n")
                            sb.append("   access=${w.authorization.length}")
                            sb.append(" refresh=${w.refreshToken.length} 可续=${x.canRefresh(w)}\n")
                            sb.append("   accessExpired=${w.accessExpired()}\n")
                        }
                        val app = x.readAppCreds()
                        sb.append("迅雷(App/root) = ").append(if (app == null) "无" else "有")
                            .append('\n')
                    } catch (e: Exception) {
                        sb.append("迅雷 读取异常 ${e.message}\n")
                    }
                    // 迅雷网页 cookie 的**原始内容**（判断 refresh_token 是否真的存在）
                    try {
                        val rows = com.panpush.app.webview.WebViewInspector.readCookies(ctx, "xunlei")
                        sb.append("迅雷 cookie 条数=").append(rows.size).append('\n')
                        rows.forEach { r ->
                            val v = r.value ?: ""
                            val masked = if (v.length > 24) v.take(24) + "…" else v
                            val hasRt = v.contains("refresh_token") || v.contains("refreshToken")
                            sb.append("   [").append(r.host).append("] ").append(r.name)
                                .append(" len=").append(v.length)
                                .append(if (hasRt) "  ★含refresh_token" else "")
                                .append('\n')
                            if (r.name.startsWith("credentials_") || hasRt) {
                                sb.append("      值=").append(masked).append('\n')
                            }
                        }
                    } catch (e: Exception) {
                        sb.append("迅雷 cookie 读取异常 ${e.message}\n")
                    }
                    sb.append("==== 结束 ====")
                    sb.toString().lines().forEach { android.util.Log.i(TAG, it) }
                }.start()
            }
            // ★ 直接用一条链接跑解析，看**文件列表 / 大小**对不对
            //   adb shell am broadcast -a com.panpush.app.DEBUG_LANZOU -p com.panpush.app \
            //       --es url "https://wwbsm.lanzoub.com/xxxx"
            ACTION_LANZOU -> {
                val link = intent.getStringExtra("url")?.trim().orEmpty()
                Thread {
                    val sb = StringBuilder()
                    sb.append("==== 蓝奏解析验证 ====\n")
                    sb.append("链接=").append(link).append('\n')
                    if (link.isBlank()) {
                        sb.append("没给 url\n")
                    } else {
                        try {
                            val p = com.panpush.app.share.LanZouParser(
                                link,
                                intent.getStringExtra("pwd")?.trim().orEmpty()
                            )
                            val files = p.list()
                            sb.append("文件数=").append(files.size).append('\n')
                            files.take(20).forEach { f ->
                                sb.append("  [").append(f.fid).append("] ").append(f.name)
                                sb.append("  size=").append(f.size)
                                if (f.size > 0) {
                                    sb.append(" (").append(f.size / 1024).append(" KB)")
                                } else {
                                    sb.append("  ← 0！大小没解析到")
                                }
                                sb.append('\n')
                            }
                            // 再跑一次**取直链**，把真实错误也暴露出来（缺提取码？端点变了？）
                            sb.append("-- 取直链 --\n")
                            var items: List<com.panpush.app.core.DownloadItem> = emptyList()
                            try {
                                items = p.resolve(files)
                                items.forEach { d ->
                                    sb.append("  ✓ ").append(d.name)
                                        .append("  size=").append(d.size).append('\n')
                                    sb.append("     url=").append(d.url).append('\n')
                                }
                            } catch (e: Exception) {
                                sb.append("  取直链失败 ").append(e.javaClass.simpleName)
                                    .append(": ").append(e.message).append('\n')
                            }
                            // ★ 端到端**真推送**：模拟修复后的顺序
                            //   ① 先拉起 Gopeed 到前台（解冻）→ ② 立刻推
                            if (intent.getBooleanExtra("push", false)) {
                                sb.append("-- 推送 Gopeed --\n")
                                try {
                                    val c = (context.applicationContext as PanPushApp).container
                                    sb.append("  推送前 ping=").append(c.gopeed().ping(1)).append('\n')
                                    // ① 拉起 Gopeed
                                    val li = context.packageManager
                                        .getLaunchIntentForPackage("com.gopeed.gopeed")
                                    if (li != null) {
                                        li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(li)
                                        sb.append("  已拉起 Gopeed，轮询解冻…\n")
                                    } else {
                                        sb.append("  拉不起 Gopeed（拿不到启动 Intent）\n")
                                    }
                                    var alive = false
                                    for (i in 1..12) {
                                        Thread.sleep(1000)
                                        if (c.gopeed().ping(1)) {
                                            alive = true
                                            sb.append("  第 ").append(i).append(" 秒探到响应\n")
                                            break
                                        }
                                    }
                                    if (!alive) sb.append("  12 秒内仍无响应\n")
                                    // ② 立刻推（间隔最短）
                                    items.take(1).forEach { d ->
                                        val r = c.gopeed().addTask(d, d.name, null)
                                        sb.append("  addTask ok=").append(r.ok)
                                            .append(" msg=").append(r.message).append('\n')
                                    }
                                } catch (e: Exception) {
                                    sb.append("  推送异常 ").append(e.javaClass.simpleName)
                                        .append(": ").append(e.message).append('\n')
                                }
                            }
                        } catch (e: Exception) {
                            sb.append("解析异常 ").append(e.javaClass.simpleName).append(": ")
                                .append(e.message).append('\n')
                        }
                    }
                    sb.append("==== 结束 ====")
                    sb.toString().lines().forEach { android.util.Log.i(TAG, it) }
                }.start()
            }
            // ★ 迅雷令牌**能不能用**的直接验证（区分「令牌坏了」和「解析器 bug」）
            //   adb shell am broadcast -a com.panpush.app.DEBUG_XLCHECK -p com.panpush.app
            ACTION_XLCHECK -> {
                Thread {
                    val sb = StringBuilder()
                    sb.append("==== 迅雷令牌可用性验证 ====\n")
                    try {
                        val store = com.panpush.app.cred.XunleiCredStore(context)
                        val c = store.loadReady()
                        if (c == null) {
                            sb.append("没有迅雷凭据\n")
                        } else {
                            val web = c.isWebToken
                            sb.append("模式=").append(if (web) "网页令牌" else "App 三件套")
                                .append(" access长度=").append(c.authorization.length)
                                .append(" refresh长度=").append(c.refreshToken.length)
                                .append(" device长度=").append(c.deviceId.length)
                                .append(" captcha长度=").append(c.captchaToken.length)
                                .append('\n')
                            // 按模式拼头（与解析器一致）
                            val h = mutableMapOf(
                                "Authorization" to c.authorization,
                                "Accept" to "application/json, text/plain, */*",
                                "Referer" to "https://pan.xunlei.com/"
                            )
                            if (web) {
                                h["User-Agent"] =
                                    com.panpush.app.share.XunleiParser.UA_WEB
                                h["X-Client-Id"] = com.panpush.app.share.XunleiParser.CLIENT_ID
                                h["X-Client-Version"] =
                                    com.panpush.app.share.XunleiParser.CLIENT_VERSION
                                // ★ 网页模式**三样都要**：device_id + captcha + Bearer
                                if (c.deviceId.isNotBlank()) h["X-Device-Id"] = c.deviceId
                                if (c.captchaToken.isNotBlank()) {
                                    h["X-Captcha-Token"] = c.captchaToken
                                }
                            } else {
                                h["User-Agent"] = com.panpush.app.share.XunleiParser.UA_APP
                                h["X-Client-Id"] = com.panpush.app.share.XunleiParser.CLIENT_ID
                                h["X-Client-Version"] =
                                    com.panpush.app.share.XunleiParser.CLIENT_VERSION
                                h["X-Device-Id"] = c.deviceId
                                h["X-Captcha-Token"] = c.captchaToken
                            }
                            val r = com.panpush.app.net.Http.get(
                                "https://api-pan.xunlei.com/drive/v1/files?limit=5",
                                h, 20
                            )
                            sb.append("GET /drive/v1/files?limit=5 → HTTP ").append(r.status).append('\n')
                            sb.append("body=").append(r.text.take(300)).append('\n')
                        }
                    } catch (e: Exception) {
                        sb.append("异常 ").append(e.javaClass.simpleName).append(": ")
                            .append(e.message).append('\n')
                    }
                    sb.append("==== 结束 ====")
                    sb.toString().lines().forEach { android.util.Log.i(TAG, it) }
                }.start()
            }
            ACTION_EXTRACT -> {                val which = intent.getStringExtra("site")?.uppercase()
                val sites = if (which.isNullOrBlank()) {
                    listOf(SiteId.BAIDU, SiteId.PAN123, SiteId.XUNLEI, SiteId.ALI, SiteId.QUARK)
                } else {
                    SiteId.entries.filter { it.name == which }
                }
                Thread {
                    val sb = StringBuilder()
                    sb.append("==== DEBUG 验收探测开始 ====\n")
                    sb.append("su 状态: ").append(RootShell.hasSu()).append('\n')
                    val c = (context.applicationContext as PanPushApp).container
                    for (s in sites) {
                        sb.append("\n── ").append(s.label).append(" ──\n")
                        try {
                            when (s) {
                                SiteId.ALI -> {
                                    val creds = c.aliyunCreds.readAppCreds()
                                    sb.append("  aliyunCreds.readAppCreds() = ")
                                        .append(if (creds == null) "null" else "OK").append('\n')
                                    if (creds == null) {
                                        sb.append("  lastWalBytes = ").append(c.aliyunCreds.lastWalBytes).append('\n')
                                        sb.append("  lastTables   = ").append(c.aliyunCreds.lastTables).append('\n')
                                        sb.append("  lastError    = ").append(c.aliyunCreds.lastError).append('\n')
                                    } else {
                                        sb.append("  drive_id     = ").append(creds.driveId).append('\n')
                                        sb.append("  access_token = len ").append(creds.accessToken.length).append('\n')
                                        sb.append("  refresh_token= len ").append(creds.refreshToken.length).append('\n')
                                    }
                                }
                                SiteId.XUNLEI -> {
                                    val x = c.xunleiCreds.load()
                                    sb.append("  xunleiCreds.load() = ")
                                        .append(if (x == null) "null" else "OK").append('\n')
                                    if (x != null) {
                                        sb.append("  device_id    = ").append(x.deviceId.take(12)).append("…\n")
                                        sb.append("  captcha 剩余 = ").append(x.remainingSec()).append("s\n")
                                        sb.append("  access 过期  = ").append(x.accessExpired()).append('\n')
                                        sb.append("  refresh 可用 = ").append(c.xunleiCreds.canRefresh(x)).append('\n')
                                        sb.append("  refresh 已判死= ").append(c.xunleiCreds.refreshTokenDead).append('\n')
                                    }
                                    sb.append("  lastRefreshError = ").append(c.xunleiCreds.lastRefreshError).append('\n')
                                }
                                else -> {
                                    val r = c.extractor.extract(s)
                                    if (r.isSuccess) {
                                        val h = r.getOrThrow()
                                        sb.append("  extract() OK  来源=").append(h.source).append('\n')
                                        sb.append("  cookie 长度 = ").append(h.cookie.length).append('\n')
                                        sb.append("  keyMissing  = ")
                                            .append(CredentialValidator.keyMissing(s, h.cookie) ?: "无（齐全）")
                                            .append('\n')
                                    } else {
                                        // 打**完整**诊断（含实况：扫了哪些库、库里有哪些 host/cookie 名），
                                        // 这样夸克/UC「为什么读不到」能一眼看清
                                        sb.append("  extract() 失败，完整诊断：\n")
                                        try {
                                            c.extractor.diagnose(s).forEach {
                                                sb.append("    ").append(it).append('\n')
                                            }
                                        } catch (e: Exception) {
                                            sb.append("    诊断异常：").append(e.message).append('\n')
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            sb.append("  异常: ").append(e.javaClass.simpleName).append(": ").append(e.message).append('\n')
                        }
                        // 逐站点就绪状态（验收标准 5）
                        // ⚠️ 必须用**与界面同一个**判据（SiteProfile.readiness），否则两边会打架
                        val saved = c.settings.credential(s)
                        val x = try {
                            if (s == SiteId.XUNLEI) c.xunleiCreds.load() else null
                        } catch (e: Exception) {
                            null
                        }
                        val a = try {
                            if (s == SiteId.ALI) c.aliyunCreds.load() else null
                        } catch (e: Exception) {
                            null
                        }
                        val rd = com.panpush.app.cred.SiteProfile.readiness(
                            site = s,
                            savedCredential = saved,
                            savedReady = CredentialValidator.ready(s, saved),
                            xunleiReadable = x != null,
                            xunleiAccessExpired = x?.accessExpired() ?: true,
                            xunleiRefreshable = x != null && c.xunleiCreds.canRefresh(x),
                            aliyunAccessTokenLength = a?.accessToken?.length ?: 0
                        )
                        sb.append("  [已保存凭据] ").append(if (saved.isBlank()) "无" else "${saved.length} 字符").append('\n')
                        sb.append("  [就绪状态] ")
                            .append(com.panpush.app.cred.SiteProfile.readinessLabel(rd)).append('\n')
                        // 内存里的实时凭据（迅雷/阿里 的真相来源在这里，不在 SharedPreferences）
                        sb.append("  [内存凭据] ").append(
                            when (s) {
                                SiteId.XUNLEI -> x?.let { cc ->
                                    "device=${cc.deviceId.take(8)}… captcha=${cc.remainingSec()}s " +
                                        "accessExpired=${cc.accessExpired()} refreshable=${c.xunleiCreds.canRefresh(cc)}"
                                } ?: "无"
                                SiteId.ALI -> a?.let { cc ->
                                    "drive_id=${cc.driveId} access=${cc.accessToken.length} refresh=${cc.refreshToken.length}"
                                } ?: "无"
                                else -> "（该站点凭据在 SharedPreferences，见上两行）"
                            }
                        ).append('\n')
                    }
                    sb.append("\n==== DEBUG 验收探测结束 ====")
                    val text = sb.toString()
                    // 按行打，避免 logcat 单条上限截断
                    text.lines().forEach { android.util.Log.i(TAG, it) }
                }.start()
            }
        }
    }

    companion object {
        const val TAG = "PanPushDebug"
        const val ACTION_EXTRACT = "com.panpush.app.DEBUG_EXTRACT"
        const val ACTION_WEBVIEW = "com.panpush.app.DEBUG_WEBVIEW"
        const val ACTION_WEBCRED = "com.panpush.app.DEBUG_WEBCRED"
        const val ACTION_XLCHECK = "com.panpush.app.DEBUG_XLCHECK"
        const val ACTION_LANZOU = "com.panpush.app.DEBUG_LANZOU"
    }
}
