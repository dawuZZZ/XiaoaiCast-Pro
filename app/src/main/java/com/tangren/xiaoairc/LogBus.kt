package com.tangren.xiaoairc

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简日志总线：内存环形缓冲 + 监听器（供界面实时刷新）。
 * 相比 py 版 MiAir 的 logging 配置，这里够用了。
 */
object LogBus {

    private const val TAG = "XiaoaiDLNA"
    private const val MAX_LINES = 500

    private val lines = ArrayDeque<String>()
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun i(msg: String) = add(Log.INFO, msg)

    fun w(msg: String) = add(Log.WARN, msg)

    fun e(msg: String) = add(Log.ERROR, msg)

    private fun add(priority: Int, msg: String) {
        Log.println(priority, TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        val snapshotListeners: List<(String) -> Unit>
        synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            snapshotListeners = listeners.toList()
        }
        for (l in snapshotListeners) {
            try {
                l(line)
            } catch (_: Throwable) {
            }
        }
    }

    fun snapshot(): String = synchronized(this) { lines.joinToString("\n") }

    fun clear() = synchronized(this) { lines.clear() }

    fun addListener(l: (String) -> Unit) = synchronized(this) { listeners.add(l) }

    fun removeListener(l: (String) -> Unit) = synchronized(this) { listeners.remove(l) }
}
