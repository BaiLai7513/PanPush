package com.panpush.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.panpush.app.webview.CookieInspector
import com.panpush.app.webview.WebViewLoginActivity

/**
 * 示例：应用内 WebView 登录 + 读取登录态（读自己库，无需 root）。
 *
 * 流程：
 *   ① 应用内 WebView 登录（WebViewLoginActivity）
 *   ② 登录成功后读 Cookie
 *   ③ 导出 JSON（Cookie + localStorage）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvOut: TextView

    // 登录结果回调：拿到登录 Cookie
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val ck = result.data?.getStringExtra(WebViewLoginActivity.EXTRA_RESULT_COOKIE)
            append("✅ 登录成功，Cookie:\n$ck\n")
        } else {
            append("⚠️ 登录取消\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOut = findViewById(R.id.tvOut)

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            // 以夸克为例；换站点只改这三个值
            val intent = WebViewLoginActivity.buildIntent(
                this,
                loginUrl = "https://pan.quark.cn/",
                checkHost = "pan.quark.cn",
                checkCookie = "ctoken"
            )
            loginLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btnRead).setOnClickListener {
            val rows = CookieInspector.readCookies(this)
            val sb = StringBuilder("Cookie 条数: ${rows.size}\n")
            rows.forEach { r ->
                val flag = (if (r.secure) "S" else "-") + (if (r.httpOnly) "H" else "-")
                sb.append("  ${r.host}\t${r.name}=${r.value.take(60)}  ($flag)")
                    .append(if (r.encrypted) " [ENCRYPTED]" else "").append('\n')
            }
            append(sb.toString())
        }

        findViewById<Button>(R.id.btnJson).setOnClickListener {
            append(CookieInspector.exportJson(this) + "\n")
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener { tvOut.text = "" }
    }

    private fun append(s: String) {
        tvOut.append(s + "\n")
    }
}