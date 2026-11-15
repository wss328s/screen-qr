package com.example.screenqr

import android.content.Context
import android.os.Build

/**
 * root 权限自动授权。
 *
 * 目标：设备已 root 时，只需要在第一次给 su 授权（Magisk 弹一次），
 * 之后所有权限都由系统层面直接放行，开机不再弹任何框。
 *
 * 核心机制（已对照 AOSP 源码确证）：
 * SystemUI 的 MediaProjectionPermissionActivity.onCreate 一开头就检查
 * MediaProjectionServiceHelper.hasProjectionPermission(uid, pkg)，
 * 一旦该包已持有 PROJECT_MEDIA appop，就直接 setResult(RESULT_OK) 并 finish，
 * 用户根本看不到录屏授权弹框。
 * 因此 `appops set <pkg> PROJECT_MEDIA allow` = 永久免弹窗。
 *
 * 同理 SYSTEM_ALERT_WINDOW 用 appops 直通，POST_NOTIFICATIONS 用 pm grant。
 */
object RootManager {

    /** 当前进程内是否已确认拿到 root */
    @Volatile
    var available: Boolean = false
        private set

    /** 最近一次操作的状态描述，给设置页显示 */
    @Volatile
    var lastStatus: String = "未检测"

    /** 是否已在本次进程内执行过授权（避免每次进程启动重复跑 su） */
    @Volatile
    private var grantedThisProcess = false

    /**
     * 在同一个 su 会话里依次执行命令。
     * 用 redirectErrorStream 把 stderr 也收进 stdout，避免管道阻塞。
     */
    private fun runAsRoot(cmds: List<String>): String? {
        var p: Process? = null
        return try {
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            p = proc
            val os = proc.outputStream
            os.write("id\n".toByteArray())
            for (c in cmds) os.write((c + "\n").toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            // 兜底：Magisk 有待确认授权时 su 会一直等，5 秒后强制收尾，避免开机流程卡死
            Thread {
                try {
                    Thread.sleep(5000)
                    proc.destroy()
                } catch (_: Exception) {
                }
            }.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (e: Exception) {
            lastStatus = "su 不可用: ${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            try {
                p?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 快速探测是否存在可用的 root（会触发一次 su 授权） */
    fun checkRoot(): Boolean {
        val out = runAsRoot(emptyList()) ?: run {
            available = false
            return false
        }
        available = out.contains("uid=0")
        if (!available) lastStatus = "没有 root 权限"
        return available
    }

    /**
     * 一次性授予全部运行所需权限。
     *
     * @param force 忽略「本次进程已授权」的缓存，强制重跑
     * @return 结果描述（同时写入 [lastStatus]）
     */
    fun grantAll(ctx: Context, force: Boolean = false): String {
        if (!force && grantedThisProcess && available) {
            return lastStatus
        }
        val pkg = ctx.packageName
        val cmds = mutableListOf(
            // 录屏授权：AOSP 看到这个 appop 就直接放行，不再弹框
            "appops set $pkg PROJECT_MEDIA allow",
            // 悬浮窗：同时也是 Android 10+ 后台启动 Activity 的豁免项
            "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        )
        // 以下两个 op 名称在不同 ROM 上写法不同，能生效哪个算哪个，报错无害
        cmds += "appops set $pkg android:project_media allow"
        cmds += "appops set $pkg android:system_alert_window allow"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cmds += "pm grant $pkg android.permission.POST_NOTIFICATIONS"
        }
        // 加入电池优化白名单，避免后台截屏服务被系统冻结（国产 ROM 效果不一，失败也无害）
        cmds += "dumpsys deviceidle whitelist +$pkg"
        cmds += "cmd appops get $pkg PROJECT_MEDIA"

        val out = runAsRoot(cmds)
        if (out == null) {
            lastStatus = "root 不可用，沿用系统弹窗授权"
            return lastStatus
        }
        val hasRoot = out.contains("uid=0")
        available = hasRoot
        val lower = out.lowercase()
        lastStatus = if (hasRoot) {
            if (lower.contains("project_media") && lower.contains("allow")) {
                "已 root：录屏/悬浮窗已永久放行，开机不再弹框"
            } else {
                "已 root：已执行授权命令（个别机型可能仍需一次确认）"
            }
        } else {
            "su 未授权，沿用系统弹窗"
        }
        grantedThisProcess = hasRoot
        return lastStatus
    }
}
