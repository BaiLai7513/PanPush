package com.panpush.app.cred

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * root 授权（显式提权）。
 *
 * 与 Termux 版 `--elevate` 语义一致：只有用户主动点“用 root 提取”时才会调用 su。
 * 依赖设备上可用的 su（Magisk / KernelSU / APatch / 系统 su），
 * 部分设备对普通应用隐藏 su，此时会优雅返回失败原因，不会崩溃。
 */
object RootShell {

    /** 常见 su 路径（顺序与 docs §7.3 一致） */
    private val SU_PATHS = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    data class Result(val ok: Boolean, val stdout: String, val stderr: String) {
        val combined: String get() = (stdout + "\n" + stderr).trim()
    }

    /**
     * 设备上是否**有** su 可执行文件（只看文件存在，**不触发授权弹窗**）。
     *
     * ⚠️ 只能说明「可能有 root」：APatch/Magisk 对普通应用开 denylist 时会隐藏 su，
     * 那时这里返回 false。所以界面把 root 当**主推荐**通道（有就优先用，快且方便），
     * 但失败时必须能优雅回退到 WebView 登录。
     */
    fun hasSu(): Boolean {
        for (p in candidateSu()) {
            if (p == "su") {
                // PATH 里的 su
                if (findSuInPath() != null) return true
            } else if (java.io.File(p).exists()) {
                return true
            }
        }
        return false
    }

    private fun findSuInPath(): String? = try {
        System.getenv("PATH")?.split(java.io.File.pathSeparator)
            ?.map { java.io.File(it, "su") }
            ?.firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    } catch (e: Exception) {
        null
    }

    /**
     * # 为什么每条命令都要先 `nsenter -t 1 -m`
     *
     * ## 现象（真机定位，2026-10-12）
     *
     * APatch/Magisk 的**应用隔离**会给应用进程一个**被过滤的挂载命名空间**：
     * 在它的视图里 `/data/data` 只剩 4 个条目（`com.google.android.gms` + `com.panpush.app`），
     * 而全局命名空间里有 **693** 个。所以 app 里 `su -c 'ls /data/data/com.xunlei...'`
     * 会报 **`No such file or directory`**（注意不是 `Permission denied` —— 这是命名空间看不见，
     * 不是权限不足）。更坑的是：实测这个受限视图**会轮转**（同一命令一次成功一次失败），
     * 这正是「有时能读、有时读不到」的来源。
     *
     * ## 修法
     *
     * 实测在 app 的命名空间里执行 `nsenter -t 1 -m -- ls -ld /data/data/com.xunlei.downloadprovider`
     * **立刻可见**（init(pid 1) 在全局命名空间；`nsenter -t 1 -m` 进入它）。
     * 于是：所有 root 命令统一加前缀 `nsenter -t 1 -m --`，
     * 读 `/data/data` 就不再受应用隔离影响。
     *
     * 若设备没有 `nsenter`、或进入失败，自动退回**不带前缀**的原始命令（原来的行为）。
     */
    /**
     * ⚠️ toybox 的 `nsenter` 参数解析有个坑（真机实测）：
     *  · 直接执行 `nsenter -t 1 -m -- cmd` → **OK**
     *  · **嵌套**时（外面已经套了一层 nsenter）`nsenter -t 1 -m -- cmd` → 报 `nsenter: not integer: -m`
     *  · 换成**长选项** `nsenter --mount -t 1 -- cmd` → 嵌套/非嵌套**都 OK**
     * 所以这里统一用 `--mount`。
     */
    private const val NS_PREFIX = "nsenter --mount -t 1 -- "

    /** 绝对路径的 ls：避免 app 的 PATH 与 root shell 不同导致 `ls` 解析到不同实现 */
    private const val LS = "/system/bin/ls"

    /** 用全局挂载命名空间执行（读 /data/data 必须） */
    private fun ns(cmd: String): String = NS_PREFIX + cmd

    /** 判断输出是否像「命令/文件不存在」类失败 */
    private fun looksMissing(t: String): Boolean =
        t.contains("No such file") || t.contains("Not a directory") ||
            t.contains("Permission denied") || t.contains("nsenter") ||
            t.contains("not found") || t.contains("inaccessible")

    /**
     * 执行一条 root 命令：**先试全局命名空间，失败再退回原始命令**。
     * 覆盖 `ls` / `cat` / `cp` / `pm` 等所有需要看全局挂载的操作。
     */
    /**
     * 进入全局挂载命名空间执行。
     *
     * ⚠️⚠️ 真机实锤的关键坑：`nsenter --mount -t 1 -- <cmd>` 是**直接 `execve()`** 那个 cmd，
     * **不经过 shell**。于是：
     *  · `nsenter … -- cd /xxx` → `nsenter: exec cd: No such file or directory`
     *    （`cd` 是 shell **内建**命令，根本没有对应的可执行文件）
     *  · `nsenter … -- cp a b; echo DONE` → 只有 `cp a b` 被 execve，
     *    后面的 `; echo DONE` 由**外层** shell 执行（且在外层命名空间里！）
     *
     * 所以必须**显式包一层 `sh -c`**：把整条命令（含 `cd` / `;` / `&&`）交给命名空间内的 shell。
     * 这一条修好几个真实故障：阿里 `cp` 复制（用到 `cd`）、各类复合脚本命令。
     */
    private fun nsWrap(command: String): String {
        // 命令里已经显式带 sh -c 时不再重复包裹
        if (command.trimStart().startsWith("sh -c ")) return command
        val q = if (command.contains('"')) {
            "'" + command.replace("'", "'\\''") + "'"
        } else {
            "\"" + command + "\""
        }
        // nsenter 的参数必须是「一个可执行文件 + 它的参数」，所以 sh -c 要作为整体传入
        return "sh -c $q"
    }

    private fun execNs(command: String, timeoutSec: Long): Result {
        val inner = nsWrap(command)
        val a = exec(NS_PREFIX + inner, timeoutSec)
        if (!looksMissing(a.stdout + "\n" + a.stderr)) return a
        val b = exec(command, timeoutSec)
        // 两个都不行时，返回信息更全的那个
        return if (b.stdout.isNotBlank() || b.stderr.isNotBlank()) b else a
    }

    /** 执行一条 shell 命令（经 su）。调用方需自行在后台线程执行。 */
    fun exec(command: String, timeoutSec: Long = 25): Result {
        val lastError = StringBuilder()
        for (su in candidateSu()) {
            val r = try {
                runProcess(listOf(su, "-c", command), timeoutSec)
            } catch (e: Exception) {
                lastError.append("$su: ${e.message}\n")
                // 便于 adb logcat 抓取排障（su 路径 / 异常）
                logDebug("exec THROW su=$su cmd=$command err=${e.javaClass.simpleName}: ${e.message}")
                continue
            }
            // 只在**非预期**情况打日志，避免刷屏：
            //  · 「文件不存在」是正常的探测结果，不打
            //  · 其它 stderr（权限/nsenter 缺失等）才值得记录
            val combined = r.stdout + "\n" + r.stderr
            if (r.stderr.isNotBlank() && !looksMissing(combined)) {
                logDebug("exec su=$su exit=${if (r.ok) 0 else 1} stderr=${r.stderr.take(200)} cmd=$command")
            }
            if (r.ok || r.stdout.isNotBlank()) return r
            // su 存在但被拒绝/无授权：换个路径再试
            lastError.append("$su: ${r.combined}\n")
            if (r.combined.contains("denied", true) || r.combined.contains("not found", true)) continue
            if (r.combined.isNotBlank()) break
        }
        logDebug("exec FAILED cmd=$command lastError=${lastError.toString().take(300)}")
        return Result(false, "", lastError.toString().ifBlank { "未找到可用的 su（设备未 root 或隐藏了 su）" })
    }

    /** 写 adb logcat（tag=PanPushRoot），仅在排障时使用 */
    private fun logDebug(msg: String) {
        try {
            android.util.Log.i("PanPushRoot", msg)
        } catch (_: Exception) {
        }
    }

    /**
     * 检查某路径是否存在。
     * 走 [execNs]（全局命名空间）——**这一步是本次最关键修复**：
     * 之前用 app 的受限命名空间探测，`/data/data/<其它App>` 会一律报不存在。
     */
    fun exists(path: String): Boolean {
        val r = execNs("$LS -ld ${shQuote(path)}", 15)
        val t = r.stdout + "\n" + r.stderr
        if (looksMissing(t)) return false
        return r.stdout.isNotBlank()
    }

    /** 读取文件内容（经 su cat，全局命名空间） */
    fun readFile(path: String, maxBytes: Int = 512 * 1024): String? {
        val r = execNs("cat ${shQuote(path)}", 25)
        val t = r.stdout + "\n" + r.stderr
        if (looksMissing(t)) return null
        if (r.stdout.isBlank()) return null
        return if (r.stdout.length > maxBytes) r.stdout.substring(0, maxBytes) else r.stdout
    }

    /** 读取目录列表（经 su ls -1，全局命名空间） */
    fun listDir(path: String): List<String> {
        val r = execNs("$LS -1 ${shQuote(path)}", 25)
        if (r.stdout.isBlank()) return emptyList()
        return r.stdout.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !looksMissing(it) }
    }

    /**
     * 用**全局挂载命名空间**执行任意 root 命令（公开入口）。
     *
     * 所有需要读 `/data/data/<别的 App>` 的缓存操作都应该用它 ——
     * 否则命令在 app 的受限命名空间里会**静默失败**（见 [NS_PREFIX] 的说明）。
     */
    fun execGlobal(command: String, timeoutSec: Long = 30): Result = execNs(command, timeoutSec)

    /** 执行一串 shell 语句（`;` 分隔的复合命令），走全局命名空间。 */
    fun execScript(command: String, timeoutSec: Long = 30): Result = execNs(command, timeoutSec)

    /** 判断当前进程是否已是 root（一般是 false；Termux root shell 里才为 true） */
    fun isRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("id"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(3, TimeUnit.SECONDS)
        out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun candidateSu(): List<String> {
        val found = SU_PATHS.filter { path ->
            try {
                java.io.File(path).exists()
            } catch (e: Exception) {
                false
            }
        }
        // 兜底：PATH 里的 su
        return (found + listOf("su")).distinct()
    }

    private fun runProcess(cmd: List<String>, timeoutSec: Long): Result {
        val pb = ProcessBuilder(cmd)
        val proc = pb.start()
        val out = StringBuilder()
        val err = StringBuilder()

        val tOut = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                    br.forEachLine { out.append(it).append('\n') }
                }
            } catch (_: Exception) {
            }
        }
        val tErr = Thread {
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                    br.forEachLine { err.append(it).append('\n') }
                }
            } catch (_: Exception) {
            }
        }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroy()
            return Result(false, out.toString(), "su 超时（未授权或设备无 su）")
        }
        tOut.join(1500); tErr.join(1500)
        return Result(proc.exitValue() == 0, out.toString(), err.toString())
    }
}
