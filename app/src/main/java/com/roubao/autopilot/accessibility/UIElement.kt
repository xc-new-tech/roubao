package com.roubao.autopilot.accessibility

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 元素数据类
 * 表示 Accessibility Tree 中的一个节点
 */
data class UIElement(
    val index: Int,                    // 元素索引（用于 tapByIndex）
    val className: String,             // 类名 (如 android.widget.Button)
    val text: String?,                 // 文本内容
    val contentDescription: String?,   // 内容描述
    val resourceId: String?,           // 资源 ID (如 com.example:id/button)
    val bounds: Rect,                  // 边界矩形
    val isClickable: Boolean,          // 是否可点击
    val isLongClickable: Boolean,      // 是否可长按
    val isScrollable: Boolean,         // 是否可滚动
    val isEditable: Boolean,           // 是否可编辑
    val isCheckable: Boolean,          // 是否可选中
    val isChecked: Boolean,            // 是否已选中
    val isFocused: Boolean,            // 是否已聚焦
    val isEnabled: Boolean,            // 是否启用
    val packageName: String?,          // 包名
    val depth: Int = 0,                // 节点深度
    val children: MutableList<UIElement> = mutableListOf()
) {
    /**
     * 获取元素的显示文本（优先 text，其次 contentDescription）
     */
    val displayText: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: ""

    /**
     * 获取元素中心点坐标
     */
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /**
     * 是否是可交互元素
     */
    val isInteractive: Boolean
        get() = isClickable || isLongClickable || isEditable || isCheckable || isScrollable

    /**
     * 获取简短的类名（去掉包名前缀）
     */
    val shortClassName: String
        get() = className.substringAfterLast(".")

    /**
     * 转换为格式化字符串（用于 LLM prompt）
     */
    fun toFormattedString(): String {
        val sb = StringBuilder()
        sb.append("[$index] ")
        sb.append(shortClassName)

        if (displayText.isNotEmpty()) {
            sb.append(" \"${displayText.take(50)}\"")
            if (displayText.length > 50) sb.append("...")
        }

        val attrs = mutableListOf<String>()
        if (isClickable) attrs.add("clickable")
        if (isEditable) attrs.add("editable")
        if (isScrollable) attrs.add("scrollable")
        if (isCheckable) attrs.add(if (isChecked) "checked" else "checkable")

        if (attrs.isNotEmpty()) {
            sb.append(" (${attrs.joinToString(", ")})")
        }

        sb.append(" [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")

        return sb.toString()
    }

    /**
     * 转换为 JSON 对象
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("index", index)
            put("className", className)
            put("text", text ?: "")
            put("contentDescription", contentDescription ?: "")
            put("resourceId", resourceId ?: "")
            put("bounds", JSONObject().apply {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
            put("isClickable", isClickable)
            put("isLongClickable", isLongClickable)
            put("isScrollable", isScrollable)
            put("isEditable", isEditable)
            put("isCheckable", isCheckable)
            put("isChecked", isChecked)
            put("isFocused", isFocused)
            put("isEnabled", isEnabled)
            put("packageName", packageName ?: "")
            put("depth", depth)
            if (children.isNotEmpty()) {
                put("children", JSONArray().apply {
                    children.forEach { put(it.toJson()) }
                })
            }
        }
    }

    companion object {
        /**
         * 从 JSON 对象解析
         */
        fun fromJson(json: JSONObject, index: Int = 0): UIElement {
            val boundsJson = json.optJSONObject("bounds")
            val bounds = if (boundsJson != null) {
                Rect(
                    boundsJson.optInt("left"),
                    boundsJson.optInt("top"),
                    boundsJson.optInt("right"),
                    boundsJson.optInt("bottom")
                )
            } else {
                Rect()
            }

            val element = UIElement(
                index = index,
                className = json.optString("className", ""),
                text = json.optString("text").takeIf { it.isNotEmpty() },
                contentDescription = json.optString("contentDescription").takeIf { it.isNotEmpty() },
                resourceId = json.optString("resourceId").takeIf { it.isNotEmpty() },
                bounds = bounds,
                isClickable = json.optBoolean("isClickable"),
                isLongClickable = json.optBoolean("isLongClickable"),
                isScrollable = json.optBoolean("isScrollable"),
                isEditable = json.optBoolean("isEditable"),
                isCheckable = json.optBoolean("isCheckable"),
                isChecked = json.optBoolean("isChecked"),
                isFocused = json.optBoolean("isFocused"),
                isEnabled = json.optBoolean("isEnabled", true),
                packageName = json.optString("packageName").takeIf { it.isNotEmpty() },
                depth = json.optInt("depth", 0)
            )

            // 递归解析子节点
            val childrenArray = json.optJSONArray("children")
            if (childrenArray != null) {
                for (i in 0 until childrenArray.length()) {
                    element.children.add(fromJson(childrenArray.getJSONObject(i)))
                }
            }

            return element
        }
    }
}

/**
 * UI 树结构
 */
data class UITree(
    val elements: List<UIElement>,           // 扁平化的元素列表（带索引）
    val root: UIElement?,                    // 根节点（树形结构）
    val packageName: String,                 // 当前应用包名
    val activityName: String,                // 当前 Activity 名称
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 根据索引获取元素
     */
    fun getByIndex(index: Int): UIElement? {
        return elements.getOrNull(index)
    }

    /**
     * 查找包含指定文本的元素
     */
    fun findByText(text: String, ignoreCase: Boolean = true): List<UIElement> {
        return elements.filter { element ->
            element.displayText.contains(text, ignoreCase)
        }
    }

    /**
     * 查找指定资源 ID 的元素
     */
    fun findByResourceId(resourceId: String): UIElement? {
        return elements.find { it.resourceId?.contains(resourceId) == true }
    }

    /**
     * 获取所有可点击元素
     */
    fun getClickableElements(): List<UIElement> {
        return elements.filter { it.isClickable && it.isEnabled }
    }

    /**
     * 获取所有可编辑元素
     */
    fun getEditableElements(): List<UIElement> {
        return elements.filter { it.isEditable && it.isEnabled }
    }

    /**
     * 转换为格式化文本（用于 LLM prompt）
     */
    fun toFormattedString(onlyInteractive: Boolean = true): String {
        val sb = StringBuilder()
        sb.appendLine("=== UI Elements ===")
        sb.appendLine("Package: $packageName")
        sb.appendLine("Activity: $activityName")
        sb.appendLine()

        val targetElements = if (onlyInteractive) {
            elements.filter { it.isInteractive && it.isEnabled }
        } else {
            elements
        }

        targetElements.forEach { element ->
            sb.appendLine(element.toFormattedString())
        }

        return sb.toString()
    }

    /**
     * 转换为 JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("packageName", packageName)
            put("activityName", activityName)
            put("timestamp", timestamp)
            put("elementCount", elements.size)
            put("elements", JSONArray().apply {
                elements.forEach { put(it.toJson()) }
            })
        }
    }
}
