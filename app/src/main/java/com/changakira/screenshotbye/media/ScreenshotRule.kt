package com.changakira.screenshotbye.media

/**
 * 截图判定规则。
 *
 * 为什么不用 Android 14 的 DETECT_SCREEN_CAPTURE：那个回调只在「用户截取本应用自己的
 * Activity」时触发，且不返回截图文件，无法用于全局截图清理。因此这里以 MediaStore 的
 * 入库事件为主信号，用「所在目录 / 文件名」两条规则识别截图。
 */
object ScreenshotRule {

    /** 各 ROM 的截图目录名（AOSP / 小米 / 华为 / OPPO / vivo / 三星 / 荣耀，含中文目录）。 */
    private val FOLDER_NAMES = setOf(
        "screenshot", "screenshots", "screenshot_2", "scrollshot",
        "screen_capture", "screenrecord",
        "截图", "屏幕截图", "屏幕快照",
    )

    /** 文件名特征，命中任一即认为是截图。 */
    private val NAME_PATTERNS = listOf(
        Regex("""screenshot[\s_\-\.]?\d""", RegexOption.IGNORE_CASE),   // Screenshot_20240101-120000
        Regex("""screen[\s_\-]?shot""", RegexOption.IGNORE_CASE),        // Screen_Shot / ScreenShot
        Regex("""screen[\s_\-]?capture""", RegexOption.IGNORE_CASE),
        Regex("""screenrecord[\s_\-]?\d""", RegexOption.IGNORE_CASE),    // 三星录屏
        Regex("""^sc2_\d""", RegexOption.IGNORE_CASE),
        Regex("""截图"""),
        Regex("""屏幕快照"""),
        Regex("""^pocorecord""", RegexOption.IGNORE_CASE),
    )

    private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp", "heic")
    private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "3gp")

    /** 纯数字时间戳命名（13 位毫秒）通常是下载/推送图片，不是系统截图。 */
    private val LOOKS_LIKE_DOWNLOAD = Regex("""^\d{10,13}\.\w+$""")

    /**
     * @param displayName 文件名（含扩展名）
     * @param folderLabel 所在目录的可读名，例如 "Screenshots"
     * @param relativePath API 29+ 的 RELATIVE_PATH，例如 "DCIM/Screenshots/"
     */
    fun matches(
        displayName: String,
        folderLabel: String,
        relativePath: String?,
        allowVideo: Boolean,
        matchByFolder: Boolean,
        matchByName: Boolean,
    ): Boolean {
        // 落在 Android/data 下的自有文件不参与判定
        if (relativePath?.contains("Android/data", ignoreCase = true) == true) return false

        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in IMG_EXT && !(allowVideo && ext in VIDEO_EXT)) return false

        // 纯时间戳命名多为下载/推送图片；文件名规则若也没命中，就不当作截图
        if (!LOOKS_LIKE_DOWNLOAD.matches(displayName) && matchByFolder &&
            folderIsScreenshot(folderLabel, relativePath)
        ) return true

        return matchByName && NAME_PATTERNS.any { it.containsMatchIn(displayName) }
    }

    private fun folderIsScreenshot(folderLabel: String, relativePath: String?): Boolean {
        val leafFromRelative = relativePath?.trimEnd('/')?.substringAfterLast('/')
        return FOLDER_NAMES.any { f ->
            folderLabel.equals(f, ignoreCase = true) ||
                folderLabel.contains(f, ignoreCase = true) ||
                leafFromRelative?.equals(f, ignoreCase = true) == true
        }
    }

    /** 从绝对路径推断目录可读名，供 API 29 以下（无 RELATIVE_PATH）使用。 */
    fun folderFromPath(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return path.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')
    }
}
