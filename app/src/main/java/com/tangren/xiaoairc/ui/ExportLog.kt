package com.tangren.xiaoairc.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tangren.xiaoairc.LogBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把日志写成文件再分享出去：剪贴板复制 500 行会截断，发文件才方便求助。 */
object ExportLog {

    fun share(ctx: Context, content: String): Boolean = try {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "xiaoaidlna-$stamp.txt")
        file.writeText(
            "小爱DLNA 运行日志\n" +
                "导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                "-------------------------------\n" + content,
            Charsets.UTF_8
        )
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "小爱DLNA 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        LogBus.e("导出日志失败：${t.message}")
        false
    }
}
