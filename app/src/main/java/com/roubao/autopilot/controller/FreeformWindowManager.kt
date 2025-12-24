package com.roubao.autopilot.controller

import android.content.Context
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 小窗 (Freeform) 模式管理器
 *
 * 支持以小窗模式启动应用，实现后台执行任务：
 * - 用户在前台操作其他应用
 * - 肉包在小窗中执行自动化任务
 */
class FreeformWindowManager(
    private val context: Context,
    private val deviceController: DeviceController
) {
    companion object {
        private const val TAG = "FreeformWindowManager"

        // Android 窗口模式常量
        const val WINDOWING_MODE_FREEFORM = 5

        // 默认小窗大小 (相对于屏幕)
        const val DEFAULT_WIDTH_RATIO = 0.5f
        const val DEFAULT_HEIGHT_RATIO = 0.45f
    }

    data class FreeformWindow(
        val packageName: String,
        val activityName: String?,
        val bounds: Rect,
        val taskId: Int
    )

    /**
     * 启用系统的 freeform 支持
     */
    suspend fun enableFreeformSupport(): Boolean = withContext(Dispatchers.IO) {
        try {
            deviceController.exec("settings put global enable_freeform_support 1")
            deviceController.exec("settings put global force_resizable_activities 1")
            Log.d(TAG, "Freeform support enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable freeform support", e)
            false
        }
    }

    /**
     * 以小窗模式启动应用
     *
     * @param packageName 应用包名
     * @param activityName Activity 名称 (可选，为空则启动主 Activity)
     * @param bounds 小窗位置和大小 (可选，为空则使用默认位置)
     * @return 是否启动成功
     */
    suspend fun launchInFreeform(
        packageName: String,
        activityName: String? = null,
        bounds: Rect? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 确保 freeform 支持已启用
            enableFreeformSupport()

            // 构建启动命令
            val component = if (activityName != null) {
                "-n $packageName/$activityName"
            } else {
                // 使用 monkey 启动主 Activity
                ""
            }

            val command = if (component.isNotEmpty()) {
                "am start --windowingMode $WINDOWING_MODE_FREEFORM $component"
            } else {
                // 先获取主 Activity，然后启动
                val mainActivity = getMainActivity(packageName)
                if (mainActivity != null) {
                    "am start --windowingMode $WINDOWING_MODE_FREEFORM -n $packageName/$mainActivity"
                } else {
                    // 使用 monkey 作为备选
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
                }
            }

            Log.d(TAG, "Launching in freeform: $command")
            val result = deviceController.exec(command)

            // 等待应用启动
            delay(500)

            // 如果指定了边界，调整窗口大小
            bounds?.let {
                resizeFreeformWindow(packageName, it)
            }

            Log.d(TAG, "Launch result: $result")
            !result.contains("Error") && !result.contains("Exception")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch in freeform: $packageName", e)
            false
        }
    }

    /**
     * 获取应用的主 Activity
     */
    private suspend fun getMainActivity(packageName: String): String? = withContext(Dispatchers.IO) {
        try {
            val result = deviceController.exec(
                "cmd package resolve-activity --brief $packageName"
            )
            // 解析输出，格式如: com.example.app/.MainActivity
            val lines = result.trim().split("\n")
            for (line in lines) {
                if (line.contains("/")) {
                    val parts = line.split("/")
                    if (parts.size == 2) {
                        return@withContext parts[1]
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get main activity", e)
            null
        }
    }

    /**
     * 获取小窗的边界信息
     *
     * @param packageName 应用包名 (可选，为空则返回任意小窗)
     * @return 小窗边界，如果没有小窗则返回 null
     */
    suspend fun getFreeformBounds(packageName: String? = null): Rect? = withContext(Dispatchers.IO) {
        try {
            val result = deviceController.exec("dumpsys activity activities")

            // 解析输出，查找 freeform 窗口
            val lines = result.split("\n")
            var inTargetTask = packageName == null
            var foundFreeform = false

            for (i in lines.indices) {
                val line = lines[i]

                // 检查是否是目标包名
                if (packageName != null && line.contains(packageName)) {
                    inTargetTask = true
                }

                // 查找 freeform 模式
                if (inTargetTask && line.contains("windowingMode=freeform")) {
                    foundFreeform = true
                }

                // 查找边界信息
                if (foundFreeform && line.contains("mBounds=Rect(")) {
                    val boundsMatch = Regex("""mBounds=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)""")
                        .find(line)

                    if (boundsMatch != null) {
                        val (left, top, right, bottom) = boundsMatch.destructured
                        return@withContext Rect(
                            left.toInt(),
                            top.toInt(),
                            right.toInt(),
                            bottom.toInt()
                        )
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get freeform bounds", e)
            null
        }
    }

    /**
     * 调整小窗大小和位置
     */
    suspend fun resizeFreeformWindow(packageName: String, bounds: Rect): Boolean = withContext(Dispatchers.IO) {
        try {
            // 获取 task ID
            val taskId = getTaskId(packageName)
            if (taskId == null) {
                Log.w(TAG, "Task not found for $packageName")
                return@withContext false
            }

            val command = "am task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
            deviceController.exec(command)
            Log.d(TAG, "Resized freeform window: $bounds")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize freeform window", e)
            false
        }
    }

    /**
     * 获取应用的 Task ID
     */
    private suspend fun getTaskId(packageName: String): Int? = withContext(Dispatchers.IO) {
        try {
            val result = deviceController.exec("am stack list")
            val lines = result.split("\n")

            for (line in lines) {
                if (line.contains(packageName)) {
                    // 解析 taskId=xxx
                    val taskMatch = Regex("""taskId=(\d+)""").find(line)
                    if (taskMatch != null) {
                        return@withContext taskMatch.groupValues[1].toInt()
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get task ID", e)
            null
        }
    }

    /**
     * 关闭小窗中的应用
     *
     * @param packageName 应用包名
     * @param forceStop 是否强制停止应用 (默认 true，完全关闭；false 仅移除窗口)
     */
    suspend fun closeFreeformWindow(packageName: String, forceStop: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            // 方法1: 尝试移除 task (仅关闭窗口)
            val taskId = getTaskId(packageName)
            if (taskId != null) {
                val result = deviceController.exec("am task remove $taskId")
                Log.d(TAG, "Removed task $taskId for $packageName: $result")
            }

            // 方法2: 如果需要强制停止，确保应用完全关闭
            if (forceStop) {
                deviceController.exec("am force-stop $packageName")
                Log.d(TAG, "Force stopped: $packageName")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close freeform window", e)
            // 降级：尝试 force-stop
            try {
                deviceController.exec("am force-stop $packageName")
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 将小窗移动到指定位置
     *
     * @param position 位置: "top-left", "top-right", "bottom-left", "bottom-right", "center"
     */
    suspend fun moveFreeformWindow(packageName: String, position: String): Boolean {
        val screenWidth = 1080  // TODO: 获取实际屏幕尺寸
        val screenHeight = 2400

        val windowWidth = (screenWidth * DEFAULT_WIDTH_RATIO).toInt()
        val windowHeight = (screenHeight * DEFAULT_HEIGHT_RATIO).toInt()

        val bounds = when (position) {
            "top-left" -> Rect(0, 100, windowWidth, 100 + windowHeight)
            "top-right" -> Rect(screenWidth - windowWidth, 100, screenWidth, 100 + windowHeight)
            "bottom-left" -> Rect(0, screenHeight - windowHeight - 200, windowWidth, screenHeight - 200)
            "bottom-right" -> Rect(screenWidth - windowWidth, screenHeight - windowHeight - 200, screenWidth, screenHeight - 200)
            "center" -> {
                val left = (screenWidth - windowWidth) / 2
                val top = (screenHeight - windowHeight) / 2
                Rect(left, top, left + windowWidth, top + windowHeight)
            }
            else -> return false
        }

        return resizeFreeformWindow(packageName, bounds)
    }

    /**
     * 检查应用是否在小窗模式运行
     */
    suspend fun isInFreeformMode(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = deviceController.exec("dumpsys activity activities")

            // 查找包名对应的 task，检查其 windowingMode
            var inTargetTask = false
            for (line in result.split("\n")) {
                if (line.contains(packageName)) {
                    inTargetTask = true
                }
                if (inTargetTask && line.contains("windowingMode=freeform")) {
                    return@withContext true
                }
                // 遇到新的 task 则重置
                if (inTargetTask && line.contains("Task{") && !line.contains(packageName)) {
                    inTargetTask = false
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check freeform mode", e)
            false
        }
    }

    /**
     * 获取所有小窗应用
     */
    suspend fun getAllFreeformWindows(): List<FreeformWindow> = withContext(Dispatchers.IO) {
        val windows = mutableListOf<FreeformWindow>()

        try {
            val result = deviceController.exec("dumpsys activity activities")
            val lines = result.split("\n")

            var currentPackage: String? = null
            var currentTaskId: Int? = null
            var inFreeform = false

            for (line in lines) {
                // 检测 Task
                val taskMatch = Regex("""Task\{[^}]+ #(\d+).*?A=([^\s}]+)""").find(line)
                if (taskMatch != null) {
                    currentTaskId = taskMatch.groupValues[1].toIntOrNull()
                    currentPackage = taskMatch.groupValues[2]
                    inFreeform = false
                }

                // 检测 freeform 模式
                if (line.contains("windowingMode=freeform")) {
                    inFreeform = true
                }

                // 获取边界
                if (inFreeform && line.contains("mBounds=Rect(") && currentPackage != null) {
                    val boundsMatch = Regex("""mBounds=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)""")
                        .find(line)

                    if (boundsMatch != null) {
                        val (left, top, right, bottom) = boundsMatch.destructured
                        windows.add(FreeformWindow(
                            packageName = currentPackage,
                            activityName = null,
                            bounds = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()),
                            taskId = currentTaskId ?: -1
                        ))
                        inFreeform = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get freeform windows", e)
        }

        windows
    }

    /**
     * 将坐标从小窗内相对坐标转换为屏幕绝对坐标
     *
     * @param relativeX 小窗内的相对 X 坐标 (0-1000)
     * @param relativeY 小窗内的相对 Y 坐标 (0-1000)
     * @param bounds 小窗边界
     * @return 屏幕绝对坐标
     */
    fun convertToAbsoluteCoordinates(relativeX: Int, relativeY: Int, bounds: Rect): Pair<Int, Int> {
        val windowWidth = bounds.width()
        val windowHeight = bounds.height()

        val absoluteX = bounds.left + (relativeX * windowWidth / 1000)
        val absoluteY = bounds.top + (relativeY * windowHeight / 1000)

        return Pair(absoluteX, absoluteY)
    }
}
