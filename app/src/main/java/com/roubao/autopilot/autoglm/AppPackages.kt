package com.roubao.autopilot.autoglm

import android.content.Context
import org.json.JSONObject

/**
 * 应用包名映射管理器
 * 从 assets/app_packages.json 加载应用名到包名的映射
 */
class AppPackages private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AppPackages? = null

        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): AppPackages {
            return instance ?: synchronized(this) {
                instance ?: AppPackages(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 内置的常用应用映射 (作为 JSON 加载失败时的备选)
         */
        private val BUILTIN_PACKAGES = mapOf(
            // 社交
            "微信" to "com.tencent.mm",
            "WeChat" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            // 电商
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            // 生活
            "小红书" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "豆瓣" to "com.douban.frodo",
            // 地图
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            // 外卖
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "大众点评" to "com.dianping.v1",
            // 出行
            "携程" to "ctrip.android.view",
            "12306" to "com.MobileTicket",
            "滴滴出行" to "com.sdu.didi.psnger",
            // 视频
            "bilibili" to "tv.danmaku.bili",
            "抖音" to "com.ss.android.ugc.aweme",
            "快手" to "com.smile.gifmaker",
            // 音乐
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            // 系统
            "设置" to "com.android.settings",
            "Settings" to "com.android.settings",
            "Chrome" to "com.android.chrome"
        )
    }

    // 应用名 -> 包名 映射
    private val appToPackage = mutableMapOf<String, String>()

    // 包名 -> 应用名 映射 (反向查找)
    private val packageToApp = mutableMapOf<String, String>()

    // 分类映射
    private val categories = mutableMapOf<String, Map<String, String>>()

    init {
        loadFromAssets(context)
    }

    /**
     * 从 assets 加载应用映射
     */
    private fun loadFromAssets(context: Context) {
        try {
            val jsonString = context.assets.open("app_packages.json")
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(jsonString)

            // 遍历所有分类
            json.keys().forEach { category ->
                if (category.startsWith("_")) return@forEach  // 跳过注释字段

                val categoryObj = json.optJSONObject(category) ?: return@forEach
                val categoryMap = mutableMapOf<String, String>()

                categoryObj.keys().forEach { appName ->
                    val packageName = categoryObj.optString(appName)
                    if (packageName.isNotEmpty()) {
                        appToPackage[appName] = packageName
                        appToPackage[appName.lowercase()] = packageName  // 小写别名
                        packageToApp[packageName] = appName
                        categoryMap[appName] = packageName
                    }
                }

                categories[category] = categoryMap
            }

            println("[AppPackages] 已加载 ${appToPackage.size} 个应用映射, ${categories.size} 个分类")

        } catch (e: Exception) {
            e.printStackTrace()
            println("[AppPackages] 加载 JSON 失败，使用内置映射")
            // 使用内置映射作为备选
            BUILTIN_PACKAGES.forEach { (name, pkg) ->
                appToPackage[name] = pkg
                appToPackage[name.lowercase()] = pkg
                packageToApp[pkg] = name
            }
        }
    }

    /**
     * 根据应用名获取包名
     * @param appName 应用名称 (支持中英文，不区分大小写)
     * @return 包名，如果找不到返回 null
     */
    fun getPackageName(appName: String): String? {
        // 直接查找
        appToPackage[appName]?.let { return it }

        // 小写查找
        appToPackage[appName.lowercase()]?.let { return it }

        // 去除空格查找
        appToPackage[appName.replace(" ", "")]?.let { return it }

        return null
    }

    /**
     * 根据包名获取应用名
     * @param packageName 包名
     * @return 应用名，如果找不到返回 null
     */
    fun getAppName(packageName: String): String? {
        return packageToApp[packageName]
    }

    /**
     * 检查是否支持该应用
     * @param appName 应用名称
     */
    fun isSupported(appName: String): Boolean {
        return getPackageName(appName) != null
    }

    /**
     * 获取所有支持的应用名列表
     */
    fun listSupportedApps(): List<String> {
        return appToPackage.keys.filter { !it.all { c -> c.isLowerCase() } }
    }

    /**
     * 获取指定分类的应用列表
     * @param category 分类名 (social, ecommerce, video, etc.)
     */
    fun getAppsByCategory(category: String): Map<String, String> {
        return categories[category] ?: emptyMap()
    }

    /**
     * 获取所有分类
     */
    fun listCategories(): List<String> {
        return categories.keys.toList()
    }

    /**
     * 模糊搜索应用
     * @param query 搜索关键词
     * @return 匹配的应用列表 (应用名 to 包名)
     */
    fun searchApps(query: String): List<Pair<String, String>> {
        val lowerQuery = query.lowercase()
        return appToPackage.entries
            .filter { it.key.lowercase().contains(lowerQuery) }
            .map { it.key to it.value }
            .distinctBy { it.second }  // 按包名去重
    }

    /**
     * 智能匹配应用名
     * 支持: 完全匹配 > 前缀匹配 > 包含匹配
     * @param appName 应用名称
     * @return 最佳匹配的包名，如果找不到返回原始输入 (可能已经是包名)
     */
    fun smartMatch(appName: String): String {
        // 1. 完全匹配
        getPackageName(appName)?.let { return it }

        // 2. 如果输入看起来已经是包名格式，直接返回
        if (appName.contains(".") && appName.count { it == '.' } >= 2) {
            return appName
        }

        // 3. 前缀匹配
        val prefixMatch = appToPackage.entries.find {
            it.key.lowercase().startsWith(appName.lowercase())
        }
        if (prefixMatch != null) {
            return prefixMatch.value
        }

        // 4. 包含匹配
        val containsMatch = appToPackage.entries.find {
            it.key.lowercase().contains(appName.lowercase())
        }
        if (containsMatch != null) {
            return containsMatch.value
        }

        // 5. 返回原始输入
        return appName
    }
}
