package com.roubao.autopilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.data.LoopConfig
import com.roubao.autopilot.data.ParamType
import com.roubao.autopilot.data.PlaybackState
import com.roubao.autopilot.data.Script
import com.roubao.autopilot.data.ScriptAction
import com.roubao.autopilot.data.ScriptParam
import com.roubao.autopilot.ui.theme.BaoziTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(
    script: Script,
    playbackState: PlaybackState,
    onBack: () -> Unit,
    onPlay: (Script, Map<String, String>) -> Unit,  // 添加参数值
    onStop: () -> Unit,
    onSave: (Script) -> Unit
) {
    val colors = BaoziTheme.colors

    // 编辑状态
    var name by remember { mutableStateOf(script.name) }
    var description by remember { mutableStateOf(script.description) }
    var loopEnabled by remember { mutableStateOf(script.loopConfig.enabled) }
    var loopCount by remember { mutableStateOf(script.loopConfig.loopCount.toString()) }
    var loopDelay by remember { mutableStateOf((script.loopConfig.loopDelayMs / 1000).toString()) }
    var stopOnError by remember { mutableStateOf(script.loopConfig.stopOnError) }

    // 参数状态
    var scriptParams by remember { mutableStateOf(script.params) }
    var paramValues by remember {
        mutableStateOf(script.params.associate { it.name to it.defaultValue })
    }
    var showParamDialog by remember { mutableStateOf(false) }
    var showAddParamDialog by remember { mutableStateOf(false) }

    // 检查是否有修改
    val hasChanges = name != script.name ||
            description != script.description ||
            loopEnabled != script.loopConfig.enabled ||
            loopCount != script.loopConfig.loopCount.toString() ||
            loopDelay != (script.loopConfig.loopDelayMs / 1000).toString() ||
            stopOnError != script.loopConfig.stopOnError ||
            scriptParams != script.params

    val isPlaying = playbackState.currentScript?.id == script.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    text = "脚本详情",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.textPrimary
                    )
                }
            },
            actions = {
                if (hasChanges) {
                    TextButton(onClick = {
                        val updatedScript = script.copy(
                            name = name,
                            description = description,
                            loopConfig = LoopConfig(
                                enabled = loopEnabled,
                                loopCount = loopCount.toIntOrNull() ?: 1,
                                loopDelayMs = (loopDelay.toLongOrNull() ?: 2) * 1000,
                                stopOnError = stopOnError
                            ),
                            params = scriptParams
                        )
                        onSave(updatedScript)
                    }) {
                        Text("保存", color = colors.primary)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 基本信息卡片
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "基本信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textHint
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // 脚本名称
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("脚本名称") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.backgroundInput,
                                focusedLabelColor = colors.primary,
                                unfocusedLabelColor = colors.textHint,
                                cursorColor = colors.primary,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 描述
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("描述 (可选)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.backgroundInput,
                                focusedLabelColor = colors.primary,
                                unfocusedLabelColor = colors.textHint,
                                cursorColor = colors.primary,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 统计信息
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "动作数: ${script.actionCount}",
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                            Text(
                                text = "创建: ${script.formattedCreatedAt}",
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            // 循环配置卡片
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = colors.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "循环播放",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                            }
                            Switch(
                                checked = loopEnabled,
                                onCheckedChange = { loopEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.primary,
                                    checkedTrackColor = colors.primary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        if (loopEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // 循环次数
                                OutlinedTextField(
                                    value = loopCount,
                                    onValueChange = { loopCount = it.filter { c -> c.isDigit() } },
                                    label = { Text("循环次数") },
                                    placeholder = { Text("0=无限") },
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.primary,
                                        unfocusedBorderColor = colors.backgroundInput,
                                        focusedLabelColor = colors.primary,
                                        unfocusedLabelColor = colors.textHint,
                                        cursorColor = colors.primary,
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )

                                // 循环间隔
                                OutlinedTextField(
                                    value = loopDelay,
                                    onValueChange = { loopDelay = it.filter { c -> c.isDigit() } },
                                    label = { Text("间隔(秒)") },
                                    modifier = Modifier.weight(1f),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colors.primary,
                                        unfocusedBorderColor = colors.backgroundInput,
                                        focusedLabelColor = colors.primary,
                                        unfocusedLabelColor = colors.textHint,
                                        cursorColor = colors.primary,
                                        focusedTextColor = colors.textPrimary,
                                        unfocusedTextColor = colors.textPrimary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 出错停止
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "出错时停止",
                                    fontSize = 14.sp,
                                    color = colors.textSecondary
                                )
                                Switch(
                                    checked = stopOnError,
                                    onCheckedChange = { stopOnError = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = colors.primary,
                                        checkedTrackColor = colors.primary.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 参数配置卡片
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "参数设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            TextButton(onClick = { showAddParamDialog = true }) {
                                Text("+ 添加参数", color = colors.primary, fontSize = 14.sp)
                            }
                        }

                        if (scriptParams.isEmpty()) {
                            Text(
                                text = "暂无参数。添加参数后可在执行时动态修改值",
                                fontSize = 13.sp,
                                color = colors.textHint,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            scriptParams.forEachIndexed { index, param ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = param.label,
                                            fontSize = 14.sp,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "绑定: \"${param.originalValue}\"",
                                            fontSize = 12.sp,
                                            color = colors.secondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scriptParams = scriptParams.toMutableList().apply {
                                                removeAt(index)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("×", color = colors.error, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 播放按钮
            item {
                Button(
                    onClick = {
                        if (isPlaying) {
                            onStop()
                        } else {
                            // 如果脚本有参数，显示参数输入对话框
                            if (scriptParams.isNotEmpty()) {
                                showParamDialog = true
                            } else {
                                // 无参数，直接播放
                                val playScript = if (hasChanges) {
                                    script.copy(
                                        name = name,
                                        description = description,
                                        loopConfig = LoopConfig(
                                            enabled = loopEnabled,
                                            loopCount = loopCount.toIntOrNull() ?: 1,
                                            loopDelayMs = (loopDelay.toLongOrNull() ?: 2) * 1000,
                                            stopOnError = stopOnError
                                        ),
                                        params = scriptParams
                                    )
                                } else {
                                    script
                                }
                                onPlay(playScript, emptyMap())
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlaying) colors.error else colors.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPlaying) "停止播放" else "开始播放",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 播放状态
            if (isPlaying) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "播放中",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = playbackState.message,
                                fontSize = 14.sp,
                                color = colors.textPrimary
                            )
                            if (loopEnabled) {
                                Text(
                                    text = "第 ${playbackState.currentLoop} 轮",
                                    fontSize = 13.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // 动作列表标题
            item {
                Text(
                    text = "动作列表",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textHint
                )
            }

            // 动作列表
            itemsIndexed(script.actions) { index, action ->
                ActionItem(
                    action = action,
                    index = index,
                    isCurrentAction = isPlaying && playbackState.currentActionIndex == index
                )
            }
        }
    }

    // 参数输入对话框 - 播放前输入参数值
    if (showParamDialog) {
        AlertDialog(
            onDismissRequest = { showParamDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text("输入参数", color = colors.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    scriptParams.forEach { param ->
                        OutlinedTextField(
                            value = paramValues[param.name] ?: param.defaultValue,
                            onValueChange = { newValue ->
                                paramValues = paramValues.toMutableMap().apply {
                                    put(param.name, newValue)
                                }
                            },
                            label = { Text(param.label) },
                            placeholder = { Text(param.defaultValue.ifEmpty { "请输入${param.label}" }) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.backgroundInput,
                                focusedLabelColor = colors.primary,
                                unfocusedLabelColor = colors.textHint,
                                cursorColor = colors.primary,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showParamDialog = false
                    val playScript = if (hasChanges) {
                        script.copy(
                            name = name,
                            description = description,
                            loopConfig = LoopConfig(
                                enabled = loopEnabled,
                                loopCount = loopCount.toIntOrNull() ?: 1,
                                loopDelayMs = (loopDelay.toLongOrNull() ?: 2) * 1000,
                                stopOnError = stopOnError
                            ),
                            params = scriptParams
                        )
                    } else {
                        script
                    }
                    onPlay(playScript, paramValues)
                }) {
                    Text("开始", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showParamDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    // 添加参数对话框
    if (showAddParamDialog) {
        var newParamLabel by remember { mutableStateOf("") }
        var selectedOriginalValue by remember { mutableStateOf("") }

        // 提取脚本中所有 Type 动作的文本值
        val textValues = remember(script.actions) {
            script.actions
                .filter { it.actionType in listOf("Type", "Type Name") }
                .mapNotNull { it.params["text"] as? String }
                .distinct()
        }

        AlertDialog(
            onDismissRequest = { showAddParamDialog = false },
            containerColor = colors.backgroundCard,
            title = {
                Text("添加参数", color = colors.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 选择要参数化的值
                    Text(
                        text = "选择要参数化的文本",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )

                    if (textValues.isEmpty()) {
                        Text(
                            text = "脚本中没有文本输入动作",
                            fontSize = 13.sp,
                            color = colors.textHint
                        )
                    } else {
                        textValues.forEach { textValue ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selectedOriginalValue == textValue)
                                            colors.primary.copy(alpha = 0.2f)
                                        else
                                            colors.backgroundInput
                                    )
                                    .clickable { selectedOriginalValue = textValue }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedOriginalValue == textValue,
                                    onClick = { selectedOriginalValue = textValue },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = colors.primary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = textValue,
                                    fontSize = 14.sp,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 参数名称
                    OutlinedTextField(
                        value = newParamLabel,
                        onValueChange = { newParamLabel = it },
                        label = { Text("参数名称") },
                        placeholder = { Text("如: 要点的食物") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.backgroundInput,
                            focusedLabelColor = colors.primary,
                            unfocusedLabelColor = colors.textHint,
                            cursorColor = colors.primary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newParamLabel.isNotBlank() && selectedOriginalValue.isNotBlank()) {
                            val paramName = "param_${System.currentTimeMillis()}"
                            scriptParams = scriptParams + ScriptParam(
                                name = paramName,
                                label = newParamLabel.trim(),
                                originalValue = selectedOriginalValue,  // 脚本中的原始值
                                defaultValue = selectedOriginalValue    // 默认输入值=原始值
                            )
                            paramValues = paramValues.toMutableMap().apply {
                                put(paramName, selectedOriginalValue)
                            }
                            showAddParamDialog = false
                        }
                    },
                    enabled = newParamLabel.isNotBlank() && selectedOriginalValue.isNotBlank()
                ) {
                    Text(
                        "添加",
                        color = if (newParamLabel.isNotBlank() && selectedOriginalValue.isNotBlank())
                            colors.primary else colors.textHint
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddParamDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
fun ActionItem(
    action: ScriptAction,
    index: Int,
    isCurrentAction: Boolean
) {
    val colors = BaoziTheme.colors

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentAction) colors.primary.copy(alpha = 0.1f) else colors.backgroundCard
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCurrentAction) colors.primary else colors.backgroundInput
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrentAction) Color.White else colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 动作信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.actionType,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrentAction) colors.primary else colors.textPrimary
                )
                if (action.description.isNotEmpty()) {
                    Text(
                        text = action.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 延时
            Text(
                text = "${action.delayAfterMs}ms",
                fontSize = 12.sp,
                color = colors.textHint
            )
        }
    }
}
