package com.roubao.autopilot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.roubao.autopilot.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * API 提供商配置
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String
) {
    companion object {
        val ALIYUN = ApiProvider(
            id = "aliyun",
            name = "阿里云 (Qwen-VL)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3-vl-plus"
        )
        val OPENAI = ApiProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o"
        )
        val OPENROUTER = ApiProvider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "anthropic/claude-3.5-sonnet"
        )
        val CUSTOM = ApiProvider(
            id = "custom",
            name = "自定义 (智谱测试)",
            baseUrl = DEFAULT_BASE_URL,
            defaultModel = DEFAULT_MODEL
        )

        val ALL = listOf(ALIYUN, OPENAI, OPENROUTER, CUSTOM)
    }
}

/**
 * 服务商配置（每个服务商独立保存）
 */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val cachedModels: List<String> = emptyList(),
    val customBaseUrl: String = ""  // 仅 custom 服务商使用
)

/**
 * 默认配置 (API Key 需要用户在设置中配置)
 */
// 视觉模型默认配置 (智谱 AutoGLM)
const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
const val DEFAULT_MODEL = "autoglm-phone"
const val DEFAULT_API_KEY = ""  // 需要用户在设置中配置

// 规划模型默认配置 (Claude)
const val DEFAULT_PLANNING_BASE_URL = ""  // 需要用户在设置中配置
const val DEFAULT_PLANNING_API_KEY = ""  // 需要用户在设置中配置
const val DEFAULT_PLANNING_MODEL = "claude-3-5-sonnet-20241022"

/**
 * 规划模型配置 (Claude)
 */
data class PlanningConfig(
    val enabled: Boolean = true,  // 默认启用
    val baseUrl: String = DEFAULT_PLANNING_BASE_URL,
    val apiKey: String = DEFAULT_PLANNING_API_KEY,
    val model: String = DEFAULT_PLANNING_MODEL
)

/**
 * 应用设置
 */
data class AppSettings(
    val currentProviderId: String = ApiProvider.CUSTOM.id,  // 当前选中的服务商 (测试阶段默认智谱)
    val providerConfigs: Map<String, ProviderConfig> = emptyMap(),  // 每个服务商的配置
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasSeenOnboarding: Boolean = false,
    val maxSteps: Int = 25,
    val cloudCrashReportEnabled: Boolean = true,
    val rootModeEnabled: Boolean = false,
    val suCommandEnabled: Boolean = false,
    val useAutoGLMMode: Boolean = true,  // 是否使用 AutoGLM 模式 (默认开启)
    val useGestureNavigation: Boolean = true,  // 是否使用全屏手势导航 (默认开启)
    val planningConfig: PlanningConfig = PlanningConfig()  // 规划模型配置
) {
    // 便捷属性：获取当前服务商的配置
    val currentConfig: ProviderConfig
        get() = providerConfigs[currentProviderId] ?: ProviderConfig()

    val currentProvider: ApiProvider
        get() = ApiProvider.ALL.find { it.id == currentProviderId } ?: ApiProvider.ALIYUN

    val apiKey: String get() = currentConfig.apiKey
    val model: String get() = currentConfig.model.ifEmpty { currentProvider.defaultModel }
    val cachedModels: List<String> get() = currentConfig.cachedModels

    val baseUrl: String
        get() = if (currentProviderId == "custom") {
            currentConfig.customBaseUrl
        } else {
            currentProvider.baseUrl
        }
}

/**
 * 设置管理器
 */
class SettingsManager(context: Context) {

    // 普通设置存储
    private val prefs: SharedPreferences =
        context.getSharedPreferences("baozi_settings", Context.MODE_PRIVATE)

    // 加密存储（用于敏感数据如 API Key）
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "baozi_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密失败时回退到普通存储（不应该发生）
            android.util.Log.e("SettingsManager", "Failed to create encrypted prefs", e)
            prefs
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        // 迁移旧的明文 API Key 到加密存储
        migrateApiKeyToSecureStorage()
    }

    /**
     * 迁移旧的明文 API Key 到加密存储
     */
    private fun migrateApiKeyToSecureStorage() {
        val oldApiKey = prefs.getString("api_key", null)
        if (!oldApiKey.isNullOrEmpty()) {
            // 保存到加密存储
            securePrefs.edit().putString("api_key", oldApiKey).apply()
            // 删除旧的明文存储
            prefs.edit().remove("api_key").apply()
            android.util.Log.d("SettingsManager", "API Key migrated to secure storage")
        }
    }

    private fun loadSettings(): AppSettings {
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            ThemeMode.DARK
        }

        // 加载当前选中的服务商 (测试阶段默认使用自定义/智谱)
        val currentProviderId = prefs.getString("current_provider_id", ApiProvider.CUSTOM.id) ?: ApiProvider.CUSTOM.id

        // 加载每个服务商的配置
        val providerConfigs = mutableMapOf<String, ProviderConfig>()
        for (provider in ApiProvider.ALL) {
            val config = loadProviderConfig(provider.id)
            providerConfigs[provider.id] = config
        }

        // 迁移旧数据（如果有）
        val oldApiKey = securePrefs.getString("api_key", null)
        val oldModel = prefs.getString("model", null)
        val oldBaseUrl = prefs.getString("base_url", null)
        val oldCachedModels = prefs.getStringSet("cached_models", null)

        if (oldApiKey != null || oldModel != null) {
            // 找到旧数据对应的服务商
            val oldProviderId = when (oldBaseUrl) {
                ApiProvider.ALIYUN.baseUrl -> ApiProvider.ALIYUN.id
                ApiProvider.OPENAI.baseUrl -> ApiProvider.OPENAI.id
                ApiProvider.OPENROUTER.baseUrl -> ApiProvider.OPENROUTER.id
                else -> "custom"
            }

            // 迁移到新格式
            val migratedConfig = ProviderConfig(
                apiKey = oldApiKey ?: "",
                model = oldModel ?: "",
                cachedModels = oldCachedModels?.toList() ?: emptyList(),
                customBaseUrl = if (oldProviderId == "custom") oldBaseUrl ?: "" else ""
            )
            providerConfigs[oldProviderId] = migratedConfig
            saveProviderConfig(oldProviderId, migratedConfig)

            // 清除旧数据
            securePrefs.edit().remove("api_key").apply()
            prefs.edit()
                .remove("model")
                .remove("base_url")
                .remove("cached_models")
                .putString("current_provider_id", oldProviderId)
                .apply()

            android.util.Log.d("SettingsManager", "Migrated old settings to provider: $oldProviderId")
        }

        // 加载规划模型配置 (使用默认值)
        val planningConfig = PlanningConfig(
            enabled = prefs.getBoolean("planning_enabled", true),  // 默认启用
            baseUrl = prefs.getString("planning_base_url", DEFAULT_PLANNING_BASE_URL) ?: DEFAULT_PLANNING_BASE_URL,
            apiKey = securePrefs.getString("planning_api_key", DEFAULT_PLANNING_API_KEY) ?: DEFAULT_PLANNING_API_KEY,
            model = prefs.getString("planning_model", DEFAULT_PLANNING_MODEL) ?: DEFAULT_PLANNING_MODEL
        )

        return AppSettings(
            currentProviderId = currentProviderId,
            providerConfigs = providerConfigs,
            themeMode = themeMode,
            hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false),
            maxSteps = prefs.getInt("max_steps", 25),
            cloudCrashReportEnabled = prefs.getBoolean("cloud_crash_report_enabled", true),
            rootModeEnabled = prefs.getBoolean("root_mode_enabled", false),
            suCommandEnabled = prefs.getBoolean("su_command_enabled", false),
            useAutoGLMMode = prefs.getBoolean("use_autoglm_mode", true),
            useGestureNavigation = prefs.getBoolean("use_gesture_navigation", true),
            planningConfig = planningConfig
        )
    }

    /**
     * 加载指定服务商的配置
     */
    private fun loadProviderConfig(providerId: String): ProviderConfig {
        val prefix = "provider_${providerId}_"
        val apiKey = securePrefs.getString("${prefix}api_key", "") ?: ""
        val model = prefs.getString("${prefix}model", "") ?: ""
        val customBaseUrl = prefs.getString("${prefix}custom_base_url", "") ?: ""

        // 测试阶段：自定义服务商使用预设值
        if (providerId == "custom") {
            return ProviderConfig(
                apiKey = apiKey.ifEmpty { DEFAULT_API_KEY },
                model = model.ifEmpty { DEFAULT_MODEL },
                cachedModels = prefs.getStringSet("${prefix}cached_models", emptySet())?.toList() ?: emptyList(),
                customBaseUrl = customBaseUrl.ifEmpty { DEFAULT_BASE_URL }
            )
        }

        return ProviderConfig(
            apiKey = apiKey,
            model = model,
            cachedModels = prefs.getStringSet("${prefix}cached_models", emptySet())?.toList() ?: emptyList(),
            customBaseUrl = customBaseUrl
        )
    }

    /**
     * 保存指定服务商的配置
     */
    private fun saveProviderConfig(providerId: String, config: ProviderConfig) {
        val prefix = "provider_${providerId}_"
        securePrefs.edit().putString("${prefix}api_key", config.apiKey).apply()
        prefs.edit()
            .putString("${prefix}model", config.model)
            .putStringSet("${prefix}cached_models", config.cachedModels.toSet())
            .putString("${prefix}custom_base_url", config.customBaseUrl)
            .apply()
    }

    /**
     * 更新当前服务商的配置
     */
    private fun updateCurrentConfig(update: (ProviderConfig) -> ProviderConfig) {
        val currentId = _settings.value.currentProviderId
        val currentConfig = _settings.value.currentConfig
        val newConfig = update(currentConfig)

        saveProviderConfig(currentId, newConfig)

        val newConfigs = _settings.value.providerConfigs.toMutableMap()
        newConfigs[currentId] = newConfig
        _settings.value = _settings.value.copy(providerConfigs = newConfigs)
    }

    fun updateApiKey(apiKey: String) {
        updateCurrentConfig { it.copy(apiKey = apiKey) }
    }

    fun updateBaseUrl(baseUrl: String) {
        // 只有自定义服务商才能修改 URL
        if (_settings.value.currentProviderId == "custom") {
            updateCurrentConfig { it.copy(customBaseUrl = baseUrl) }
        }
    }

    fun updateModel(model: String) {
        updateCurrentConfig { it.copy(model = model) }
    }

    /**
     * 更新缓存的模型列表（从 API 获取后调用）
     */
    fun updateCachedModels(models: List<String>) {
        val distinctModels = models.distinct()
        updateCurrentConfig { it.copy(cachedModels = distinctModels) }
    }

    /**
     * 清空缓存的模型列表
     */
    fun clearCachedModels() {
        updateCurrentConfig { it.copy(cachedModels = emptyList()) }
    }

    /**
     * 选择服务商（切换时自动加载该服务商的配置）
     */
    fun selectProvider(provider: ApiProvider) {
        prefs.edit().putString("current_provider_id", provider.id).apply()
        _settings.value = _settings.value.copy(currentProviderId = provider.id)
    }

    /**
     * 获取当前服务商
     */
    fun getCurrentProvider(): ApiProvider {
        return _settings.value.currentProvider
    }

    /**
     * 判断是否使用自定义 URL
     */
    fun isCustomUrl(): Boolean {
        return _settings.value.currentProviderId == "custom"
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setOnboardingSeen() {
        prefs.edit().putBoolean("has_seen_onboarding", true).apply()
        _settings.value = _settings.value.copy(hasSeenOnboarding = true)
    }

    fun updateMaxSteps(maxSteps: Int) {
        val validSteps = maxSteps.coerceIn(5, 100) // 限制范围 5-100
        prefs.edit().putInt("max_steps", validSteps).apply()
        _settings.value = _settings.value.copy(maxSteps = validSteps)
    }

    fun updateCloudCrashReportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_crash_report_enabled", enabled).apply()
        _settings.value = _settings.value.copy(cloudCrashReportEnabled = enabled)
    }

    fun updateRootModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("root_mode_enabled", enabled).apply()
        _settings.value = _settings.value.copy(rootModeEnabled = enabled)
        // 关闭 Root 模式时，同时关闭 su -c
        if (!enabled) {
            updateSuCommandEnabled(false)
        }
    }

    fun updateSuCommandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("su_command_enabled", enabled).apply()
        _settings.value = _settings.value.copy(suCommandEnabled = enabled)
    }

    fun updateUseAutoGLMMode(enabled: Boolean) {
        prefs.edit().putBoolean("use_autoglm_mode", enabled).apply()
        _settings.value = _settings.value.copy(useAutoGLMMode = enabled)
    }

    fun updateUseGestureNavigation(enabled: Boolean) {
        prefs.edit().putBoolean("use_gesture_navigation", enabled).apply()
        _settings.value = _settings.value.copy(useGestureNavigation = enabled)
    }

    // ========== 规划模型配置 ==========

    fun updatePlanningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("planning_enabled", enabled).apply()
        _settings.value = _settings.value.copy(
            planningConfig = _settings.value.planningConfig.copy(enabled = enabled)
        )
    }

    fun updatePlanningBaseUrl(baseUrl: String) {
        prefs.edit().putString("planning_base_url", baseUrl).apply()
        _settings.value = _settings.value.copy(
            planningConfig = _settings.value.planningConfig.copy(baseUrl = baseUrl)
        )
    }

    fun updatePlanningApiKey(apiKey: String) {
        securePrefs.edit().putString("planning_api_key", apiKey).apply()
        _settings.value = _settings.value.copy(
            planningConfig = _settings.value.planningConfig.copy(apiKey = apiKey)
        )
    }

    fun updatePlanningModel(model: String) {
        prefs.edit().putString("planning_model", model).apply()
        _settings.value = _settings.value.copy(
            planningConfig = _settings.value.planningConfig.copy(model = model)
        )
    }

    /**
     * 一次性更新完整的规划配置
     */
    fun updatePlanningConfig(config: PlanningConfig) {
        prefs.edit()
            .putBoolean("planning_enabled", config.enabled)
            .putString("planning_base_url", config.baseUrl)
            .putString("planning_model", config.model)
            .apply()
        securePrefs.edit().putString("planning_api_key", config.apiKey).apply()
        _settings.value = _settings.value.copy(planningConfig = config)
    }
}
