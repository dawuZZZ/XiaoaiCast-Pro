package com.tangren.xiaoairc.xiaomi

/**
 * 设备型号能力目录。
 *
 * 数据来源：miair-next / 上游 MiAir 的实测机型白名单（const.py），
 * 是**事实性机型清单**，不含任何上游源码；许可标注见项目 README。
 *
 * 作用：不再依赖用户手动勾选「走 music 接口」，按 hardware 型号自动分派
 * player_play_music / player_play_url，减少「投上去没反应」的机型兼容问题。
 */
object DeviceCatalog {

    /**
     * 必须走 player_play_music 的机型（直接 player_play_url 会返回错误码/不生效）。
     */
    val NEED_USE_PLAY_MUSIC_API: Set<String> = setOf(
        "X08C", "X08E", "X8F", "X4B",
        "LX05", "LX05A", "OH11", "OH2", "OH2P",
        "X6A", "LX04", "L05B", "L05C", "LX06",
        "L06A", "X08A", "X10A", "L15A", "L07A",
        "L16A", "L16B", "L17A"
    )

    /**
     * 该型号是否应走 music 接口。
     *
     * 优先级：用户显式设置 > 型号白名单 > 全局开关。
     *
     * @param hardware 设备 hardware 字段（大小写不敏感）
     * @param forceMusicApi 全局开关（默认开）
     * @param compatibilityMode 用户显式「兼容模式」→ 一律走 player_play_url
     * @param userExplicit 用户改过「player_play_music」开关 → 完全尊重用户选择，
     *        白名单不再强行覆盖（白名单只在用户没动过开关时代做决定）
     */
    fun shouldUseMusicApi(
        hardware: String,
        forceMusicApi: Boolean = true,
        compatibilityMode: Boolean = false,
        userExplicit: Boolean = false
    ): Boolean {
        if (compatibilityMode) return false
        if (userExplicit) return forceMusicApi
        val hw = hardware.uppercase().trim()
        if (hw.isNotEmpty() && hw in NEED_USE_PLAY_MUSIC_API) return true
        return forceMusicApi
    }

    /** 是否已知需要 music 接口的型号（用于 UI 展示机型的接口路径）。 */
    fun isKnownMusicModel(hardware: String): Boolean =
        hardware.uppercase().trim() in NEED_USE_PLAY_MUSIC_API
}
