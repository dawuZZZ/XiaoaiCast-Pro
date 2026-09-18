package com.tangren.xiaoairc.xiaomi

import com.tangren.xiaoairc.Prefs

/**
 * 登录保护。
 *
 * 两个作用：
 *  1. 登录失败限速：窗口内失败过多时暂时拒绝再登录，避免反复触发小米风控
 *     （密码错 / 风控时会返回登录页 HTML，越试越容易被拦）。
 *  2. 连续失败自愈：连续 N 次登录失败（且都是"凭据类"失败）后，通知上层重启
 *     投屏链路，尝试自我恢复——对齐 miair-next 的 auto restart 思路。
 *
 * 计数持久化在 Prefs 里，App 重启不清零，避免"重启一遍绕过限速"。
 */
object LoginGuard {

    /** 窗口内允许的最大失败次数 */
    const val MAX_FAILURES = 5

    /** 限速窗口 / 锁定时长（秒） */
    const val LOCKOUT_SECONDS = 300L

    /** 连续失败达到该值 → 建议自愈重启 */
    const val CONSECUTIVE_RESTART_THRESHOLD = 6

    /** 距解锁剩余秒数；0 表示未锁定。 */
    fun remainingLockSeconds(prefs: Prefs, now: Long = System.currentTimeMillis()): Long {
        val windowStart = now - LOCKOUT_SECONDS * 1000
        val times = prefs.loginFailTimes.filter { it > windowStart }.sorted()
        if (times.size < MAX_FAILURES) return 0
        val unlockAt = times.first() + LOCKOUT_SECONDS * 1000
        return ((unlockAt - now) / 1000).coerceAtLeast(0)
    }

    fun isLocked(prefs: Prefs): Boolean = remainingLockSeconds(prefs) > 0

    /** 记录一次失败，返回当前的连续失败次数。 */
    fun recordFailure(prefs: Prefs, now: Long = System.currentTimeMillis()): Int {
        val windowStart = now - LOCKOUT_SECONDS * 1000
        val kept = prefs.loginFailTimes.filter { it > windowStart } + now
        prefs.loginFailTimes = kept

        val consecutive = prefs.loginConsecutive + 1
        prefs.loginConsecutive = consecutive
        return consecutive
    }

    /** 登录成功：清空窗口记录与连续计数。 */
    fun recordSuccess(prefs: Prefs) {
        prefs.loginFailTimes = emptyList()
        prefs.loginConsecutive = 0
    }

    fun consecutiveFailures(prefs: Prefs): Int = prefs.loginConsecutive

    /**
     * App 冷启动时调用：连续失败计数清零。
     * 该计数只服务于"本次会话内的自愈重启"判断；跨进程持久化会让
     * 昨天积累的失败在今天无谓地触发重启。限速窗口（loginFailTimes）不受影响，
     * 不存在"重启绕过限速"的问题。
     */
    fun onAppColdStart(prefs: Prefs) {
        if (prefs.loginConsecutive > 0) {
            prefs.loginConsecutive = 0
        }
    }

    /** 是否应触发自愈重启，并在触发后清零计数（避免重复触发）。 */
    fun consumeSelfHeal(prefs: Prefs): Boolean {
        if (prefs.loginConsecutive >= CONSECUTIVE_RESTART_THRESHOLD) {
            prefs.loginConsecutive = 0
            return true
        }
        return false
    }
}
