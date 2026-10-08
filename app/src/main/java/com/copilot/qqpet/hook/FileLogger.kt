package com.copilot.qqpet.hook

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻增量文件日志：把引擎运行、调度决策、协议收发细节持续追加写到
 *   /sdcard/Android/data/com.tencent.mobileqq/files/qpet-logs/engine.log
 * 不受「调试模式日志」开关影响（开关只控制 logcat/XposedBridge 刷屏），便于事后 adb pull 拉回现场。
 * 单文件超过 MAX_BYTES 时滚动到 engine.log.1，只保留一份备份，避免无限增长。
 */
object FileLogger {

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private const val DIR_NAME = "qpet-logs"
    private const val FILE_NAME = "engine.log"
    private const val BACKUP_NAME = "engine.log.1"
    private const val MAX_BYTES = 2L * 1024 * 1024 // 2MB 滚动

    fun bind(context: Context?) {
        appContext = context?.applicationContext ?: context
        raw("===== QPet session bound at ${tsFmt.format(Date())}, logDir=${logDir()} =====")
    }

    fun log(tag: String, msg: String) {
        raw("${tsFmt.format(Date())} [$tag] $msg")
    }

    fun raw(msg: String) {
        val ctx = appContext ?: return
        try {
            val dir = ctx.getExternalFilesDir(DIR_NAME) ?: return
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            synchronized(lock) {
                if (file.exists() && file.length() > MAX_BYTES) {
                    File(dir, BACKUP_NAME).delete()
                    file.renameTo(File(dir, BACKUP_NAME))
                }
                FileWriter(file, true).use { it.append(msg).append('\n').flush() }
            }
        } catch (_: Throwable) {
        }
    }

    fun logDir(): String? = try {
        appContext?.getExternalFilesDir(DIR_NAME)?.absolutePath
    } catch (_: Throwable) {
        null
    }

    fun currentLogFile(): File? {
        val dir = appContext?.getExternalFilesDir(DIR_NAME) ?: return null
        return File(dir, FILE_NAME)
    }
}
