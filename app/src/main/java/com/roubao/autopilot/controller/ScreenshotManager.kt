package com.roubao.autopilot.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 截图管理器
 * 负责截图的获取和处理
 */
class ScreenshotManager(
    private val execCommand: (String) -> String,
    private val getScreenSize: () -> Pair<Int, Int>
) {
    companion object {
        private const val TAG = "ScreenshotManager"
        // 使用 /data/local/tmp，shell 用户有权限访问
        private const val SCREENSHOT_PATH = "/data/local/tmp/autopilot_screen.png"
    }

    /**
     * 截图结果
     */
    data class ScreenshotResult(
        val bitmap: Bitmap,
        val isSensitive: Boolean = false,  // 是否是敏感页面（截图失败）
        val isFallback: Boolean = false    // 是否是降级的黑屏占位图
    )

    /**
     * 截图 - 使用 /data/local/tmp 并设置全局可读权限
     * 失败时返回黑屏占位图（降级处理）
     */
    suspend fun screenshotWithFallback(): ScreenshotResult = withContext(Dispatchers.IO) {
        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            val output = execCommand("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // 检查是否截图失败（敏感页面保护）
            if (output.contains("Status: -1") || output.contains("Failed") || output.contains("error")) {
                Log.d(TAG, "Screenshot blocked (sensitive screen), returning fallback")
                return@withContext createFallbackScreenshot(isSensitive = true)
            }

            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                Log.d(TAG, "Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                val bitmap = BitmapFactory.decodeFile(SCREENSHOT_PATH)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            // 如果无法直接读取，通过 shell cat 读取二进制数据
            Log.d(TAG, "Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                Log.d(TAG, "Read ${bytes.size} bytes via shell")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    return@withContext ScreenshotResult(bitmap)
                }
            }

            Log.d(TAG, "Screenshot file empty or not accessible, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Screenshot exception, returning fallback")
            createFallbackScreenshot(isSensitive = false)
        }
    }

    /**
     * 创建黑屏占位图（降级处理）
     */
    private fun createFallbackScreenshot(isSensitive: Boolean): ScreenshotResult {
        val (width, height) = getScreenSize()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 默认是黑色，无需填充
        return ScreenshotResult(
            bitmap = bitmap,
            isSensitive = isSensitive,
            isFallback = true
        )
    }

    /**
     * 截图 - 使用 /data/local/tmp 并设置全局可读权限
     * @deprecated 使用 screenshotWithFallback() 代替
     */
    @Deprecated("使用 screenshotWithFallback() 代替", ReplaceWith("screenshotWithFallback()"))
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 截图到 /data/local/tmp 并设置权限让 App 可读
            execCommand("screencap -p $SCREENSHOT_PATH && chmod 666 $SCREENSHOT_PATH")
            delay(500)

            // 尝试直接读取
            val file = File(SCREENSHOT_PATH)
            if (file.exists() && file.canRead() && file.length() > 0) {
                Log.d(TAG, "Reading screenshot from: $SCREENSHOT_PATH, size: ${file.length()}")
                return@withContext BitmapFactory.decodeFile(SCREENSHOT_PATH)
            }

            // 如果无法直接读取，通过 shell cat 读取二进制数据
            Log.d(TAG, "Cannot read directly, trying shell cat...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $SCREENSHOT_PATH"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()

            if (bytes.isNotEmpty()) {
                Log.d(TAG, "Read ${bytes.size} bytes via shell")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                Log.d(TAG, "Screenshot file empty or not accessible")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
