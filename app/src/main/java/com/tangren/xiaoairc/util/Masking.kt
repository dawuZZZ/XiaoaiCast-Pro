package com.tangren.xiaoairc.util

/**
 * 敏感信息脱敏。
 *
 * 手机端没有「前端 / 后端」之分，脱敏的主要用途是：
 *  1. 界面展示时不把 passToken / serviceToken 明文露出来；
 *  2. **导出日志**前把已知凭据从日志文本里抹掉 —— 否则用户把日志发出去求助时，
 *     等于把账号凭据一起发了出去（旧版 xiaoai-cast 的「导出分享日志」就有这个隐患）。
 *
 * 思路对齐 miair-next 的 core/masking.py（Kotlin 重写）。
 */
object Masking {

    const val MASKED = "********"

    /** 完整敏感凭据：整体替换为占位符。 */
    fun maskToken(value: String?): String {
        if (value.isNullOrEmpty()) return value ?: ""
        return MASKED
    }

    /** 账号类标识：保留末 3 位明文，其余遮蔽。 */
    fun maskId(value: String?): String {
        if (value.isNullOrEmpty()) return value ?: ""
        if (value.length <= 3) return MASKED
        return MASKED + value.takeLast(3)
    }

    /** 通用密钥（如推送 SPT）：保留末 3 位。 */
    fun maskSecret(value: String?): String = maskId(value)

    /** 对 `k=v; k2=v2` 形式的 Cookie 串做脱敏。 */
    fun maskCookie(cookie: String?): String {
        if (cookie.isNullOrEmpty()) return cookie ?: ""
        return cookie.split(";").mapNotNull { item ->
            val s = item.trim()
            if (s.isEmpty()) return@mapNotNull null
            val eq = s.indexOf('=')
            if (eq < 0) return@mapNotNull s
            val key = s.substring(0, eq).trim()
            val value = s.substring(eq + 1).trim()
            "$key=${maskField(key, value)}"
        }.joinToString("; ")
    }

    private fun maskField(key: String, value: String): String = when {
        key.equals("passToken", ignoreCase = true) -> MASKED
        key.equals("serviceToken", ignoreCase = true) -> MASKED
        key.equals("ssecurity", ignoreCase = true) -> MASKED
        key.equals("password", ignoreCase = true) -> MASKED
        key.equals("userId", ignoreCase = true) -> maskId(value)
        key.equals("cUserId", ignoreCase = true) -> maskId(value)
        else -> value
    }

    /**
     * 导出日志前的清洗：先按已知真实凭据做精确替换，再用正则兜住漏网的
     * `key=value` / `"key":"value"` 形态。
     *
     * @param text 原始日志
     * @param knownSecrets 已知的真实敏感值（passToken / serviceToken / ssecurity / password）
     */
    fun scrubLog(text: String, knownSecrets: Collection<String>): String {
        var out = text
        // 1) 精确抹掉已知凭据（长度 >= 8 才处理，避免误伤短字符串）
        for (secret in knownSecrets) {
            if (secret.length >= 8 && out.contains(secret)) {
                out = out.replace(secret, MASKED)
            }
        }
        // 2) 正则兜底：passToken=... / "passToken":"..." / serviceToken / ssecurity / password
        val kvRegex = Regex(
            "(?i)(passToken|serviceToken|ssecurity|password)(\\s*[=:]\\s*[\"']?)([^\\s\"',;&]{4,})"
        )
        out = kvRegex.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + MASKED }
        return out
    }
}
