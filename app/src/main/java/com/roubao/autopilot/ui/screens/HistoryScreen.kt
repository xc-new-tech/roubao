package com.roubao.autopilot.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.data.ExecutionRecord
import com.roubao.autopilot.data.ExecutionStatus
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.ui.theme.BaoziTheme
import com.roubao.autopilot.ui.theme.Primary
import com.roubao.autopilot.ui.theme.Secondary

@Composable
fun HistoryScreen(
    records: List<ExecutionRecord>,
    onRecordClick: (ExecutionRecord) -> Unit,
    onDeleteRecord: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    text = "执行记录",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "共 ${records.size} 条记录",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        if (records.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📝",
                        fontSize = 64.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无执行记录",
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "执行任务后记录会显示在这里",
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                }
            }
        } else {
            // 记录列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = records,
                    key = { it.id }
                ) { record ->
                    HistoryRecordCard(
                        record = record,
                        onClick = { onRecordClick(record) },
                        onDelete = { onDeleteRecord(record.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRecordCard(
    record: ExecutionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = BaoziTheme.colors
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = { Text("删除记录", color = colors.textPrimary) },
            text = { Text("确定要删除这条执行记录吗？", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when (record.status) {
                            ExecutionStatus.COMPLETED -> colors.success.copy(alpha = 0.2f)
                            ExecutionStatus.FAILED -> colors.error.copy(alpha = 0.2f)
                            ExecutionStatus.STOPPED -> colors.warning.copy(alpha = 0.2f)
                            ExecutionStatus.RUNNING -> colors.primary.copy(alpha = 0.2f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (record.status) {
                        ExecutionStatus.COMPLETED -> Icons.Default.CheckCircle
                        ExecutionStatus.FAILED -> Icons.Default.Warning
                        ExecutionStatus.STOPPED -> Icons.Default.PlayArrow
                        ExecutionStatus.RUNNING -> Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    tint = when (record.status) {
                        ExecutionStatus.COMPLETED -> colors.success
                        ExecutionStatus.FAILED -> colors.error
                        ExecutionStatus.STOPPED -> colors.warning
                        ExecutionStatus.RUNNING -> colors.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.instruction,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态标签
                    val (statusText, statusColor) = when (record.status) {
                        ExecutionStatus.COMPLETED -> "已完成" to colors.success
                        ExecutionStatus.FAILED -> "失败" to colors.error
                        ExecutionStatus.STOPPED -> "已取消" to colors.warning
                        ExecutionStatus.RUNNING -> "执行中" to colors.primary
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        modifier = Modifier
                            .background(
                                statusColor.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = record.formattedStartTime,
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "${record.steps.size}步",
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = record.formattedDuration,
                        fontSize = 12.sp,
                        color = colors.textHint,
                        maxLines = 1
                    )
                }
            }

            // 删除按钮
            IconButton(
                onClick = { showDeleteDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = colors.textHint
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    record: ExecutionRecord,
    onBack: () -> Unit,
    onRerun: (String) -> Unit = {},  // 重复执行回调，参数为任务指令
    onSaveAsScript: ((String) -> Unit)? = null  // 保存为脚本回调，参数为脚本名称
) {
    val colors = BaoziTheme.colors
    // Tab 状态：0 = 时间线，1 = 日志
    var selectedTab by remember { mutableStateOf(0) }
    // 保存脚本对话框
    var showSaveScriptDialog by remember { mutableStateOf(false) }
    var scriptName by remember { mutableStateOf(record.title) }

    // 保存为脚本对话框
    if (showSaveScriptDialog && onSaveAsScript != null) {
        AlertDialog(
            onDismissRequest = { showSaveScriptDialog = false },
            containerColor = colors.backgroundCard,
            title = { Text("保存为脚本", color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        text = "为脚本命名:",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = scriptName,
                        onValueChange = { scriptName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.backgroundInput,
                            cursorColor = colors.primary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (scriptName.isNotBlank()) {
                            onSaveAsScript(scriptName)
                            showSaveScriptDialog = false
                        }
                    }
                ) {
                    Text("保存", color = colors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveScriptDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = record.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = record.formattedStartTime,
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
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
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background
            )
        )

        // 任务信息卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "任务指令",
                    fontSize = 12.sp,
                    color = colors.textHint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.instruction,
                    fontSize = 15.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("状态", fontSize = 12.sp, color = colors.textHint)
                        Text(
                            text = when (record.status) {
                                ExecutionStatus.COMPLETED -> "已完成"
                                ExecutionStatus.FAILED -> "失败"
                                ExecutionStatus.STOPPED -> "已停止"
                                ExecutionStatus.RUNNING -> "执行中"
                            },
                            fontSize = 14.sp,
                            color = when (record.status) {
                                ExecutionStatus.COMPLETED -> colors.success
                                ExecutionStatus.FAILED -> colors.error
                                ExecutionStatus.STOPPED -> colors.warning
                                ExecutionStatus.RUNNING -> colors.primary
                            }
                        )
                    }
                    Column {
                        Text("步骤数", fontSize = 12.sp, color = colors.textHint)
                        Text("${record.steps.size}", fontSize = 14.sp, color = colors.textPrimary)
                    }
                    Column {
                        Text("耗时", fontSize = 12.sp, color = colors.textHint)
                        Text(record.formattedDuration, fontSize = 14.sp, color = colors.textPrimary)
                    }
                }

                // 操作按钮
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 重复执行按钮
                    Button(
                        onClick = { onRerun(record.instruction) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "重复执行",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 保存为脚本按钮
                    if (onSaveAsScript != null && record.status == ExecutionStatus.COMPLETED) {
                        OutlinedButton(
                            onClick = { showSaveScriptDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.secondary
                            ),
                            border = BorderStroke(1.dp, colors.secondary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "📜 保存为脚本",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // 执行报告卡片
        ExecutionReportCard(record = record)

        // Tab 切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 时间线 Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 0) colors.primary
                        else colors.backgroundCard
                    )
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "执行时间线",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedTab == 0) Color.White else colors.textSecondary
                )
            }

            // 日志 Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 1) colors.primary
                        else colors.backgroundCard
                    )
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "执行日志",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedTab == 1) Color.White else colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 内容区域
        when (selectedTab) {
            0 -> {
                // 时间线列表
                if (record.steps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行步骤",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(record.steps) { step ->
                            TimelineItem(step = step, isLast = step == record.steps.lastOrNull())
                        }
                    }
                }
            }
            1 -> {
                // 日志列表
                val context = LocalContext.current
                if (record.logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无执行日志",
                            fontSize = 14.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 复制全部按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val allLogs = record.logs.joinToString("\n")
                                    copyToClipboard(context, allLogs, "已复制全部日志")
                                }
                            ) {
                                Text(
                                    text = "📋 复制全部",
                                    fontSize = 13.sp,
                                    color = colors.primary
                                )
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(record.logs) { log ->
                                LogItem(log = log, context = context)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineItem(
    step: ExecutionStep,
    isLast: Boolean
) {
    val colors = BaoziTheme.colors
    Row(modifier = Modifier.fillMaxWidth()) {
        // 时间线指示器
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 圆点
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when (step.outcome) {
                            "A" -> colors.success
                            "B" -> colors.warning
                            "?" -> colors.textHint // 进行中被取消
                            else -> colors.error
                        }
                    )
            )
            // 连接线
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(80.dp)
                        .background(colors.backgroundInput)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 步骤内容
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Step ${step.stepNumber}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary
                    )
                    Text(
                        text = step.action,
                        fontSize = 12.sp,
                        color = colors.secondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = step.description,
                    fontSize = 13.sp,
                    color = colors.textPrimary
                )
                if (step.thought.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = step.thought,
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 单条日志项 - 支持长按复制
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogItem(log: String, context: Context) {
    val colors = BaoziTheme.colors
    val logColor = when {
        log.contains("❌") -> colors.error
        log.contains("✅") -> colors.success
        log.contains("📋") || log.contains("🎬") -> colors.secondary
        log.contains("Step") || log.contains("=====") -> colors.primary
        log.contains("⛔") -> colors.error
        else -> colors.textSecondary
    }
    Text(
        text = log,
        fontSize = 12.sp,
        color = logColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { },
                onLongClick = {
                    copyToClipboard(context, log, "已复制")
                }
            )
            .padding(vertical = 2.dp)
    )
}

/**
 * 复制到剪贴板
 */
private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("roubao_log", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/**
 * 执行报告卡片 - 紧扣用户指令，复盘执行情况
 */
@Composable
fun ExecutionReportCard(record: ExecutionRecord) {
    val colors = BaoziTheme.colors

    // 生成执行报告内容
    val reportContent = generateExecutionReport(record)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态图标
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            when (record.status) {
                                ExecutionStatus.COMPLETED -> colors.success.copy(alpha = 0.15f)
                                ExecutionStatus.FAILED -> colors.error.copy(alpha = 0.15f)
                                ExecutionStatus.STOPPED -> colors.warning.copy(alpha = 0.15f)
                                ExecutionStatus.RUNNING -> colors.primary.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (record.status) {
                            ExecutionStatus.COMPLETED -> "✓"
                            ExecutionStatus.FAILED -> "✗"
                            ExecutionStatus.STOPPED -> "⏹"
                            ExecutionStatus.RUNNING -> "▶"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (record.status) {
                            ExecutionStatus.COMPLETED -> colors.success
                            ExecutionStatus.FAILED -> colors.error
                            ExecutionStatus.STOPPED -> colors.warning
                            ExecutionStatus.RUNNING -> colors.primary
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "执行报告",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = when (record.status) {
                            ExecutionStatus.COMPLETED -> "任务已完成"
                            ExecutionStatus.FAILED -> "任务执行失败"
                            ExecutionStatus.STOPPED -> "任务被中止"
                            ExecutionStatus.RUNNING -> "任务执行中"
                        },
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.textHint.copy(alpha = 0.2f))
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 报告内容
            Text(
                text = reportContent,
                fontSize = 14.sp,
                color = colors.textPrimary,
                lineHeight = 22.sp
            )

            // 如果有结果消息，显示
            if (record.resultMessage.isNotBlank() &&
                record.resultMessage != "任务完成" &&
                record.resultMessage != "已取消") {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when (record.status) {
                                ExecutionStatus.COMPLETED -> colors.success.copy(alpha = 0.1f)
                                ExecutionStatus.FAILED -> colors.error.copy(alpha = 0.1f)
                                else -> colors.warning.copy(alpha = 0.1f)
                            }
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = record.resultMessage,
                        fontSize = 13.sp,
                        color = when (record.status) {
                            ExecutionStatus.COMPLETED -> colors.success
                            ExecutionStatus.FAILED -> colors.error
                            else -> colors.warning
                        }
                    )
                }
            }
        }
    }
}

/**
 * 生成执行报告内容
 */
private fun generateExecutionReport(record: ExecutionRecord): String {
    val instruction = record.instruction
    val status = record.status
    val stepCount = record.steps.size.takeIf { it > 0 } ?: extractStepCountFromLogs(record.logs)
    val duration = record.formattedDuration

    // 分析执行日志，提取关键操作
    val keyActions = extractKeyActions(record.logs)

    val sb = StringBuilder()

    // 开头：紧扣用户指令
    sb.append("针对您的指令「$instruction」，")

    when (status) {
        ExecutionStatus.COMPLETED -> {
            sb.append("肉包已成功完成任务。\n\n")
            sb.append("📊 执行概况：共执行 $stepCount 个步骤，耗时 $duration。\n")
            if (keyActions.isNotEmpty()) {
                sb.append("\n🔑 关键操作：\n")
                keyActions.take(5).forEachIndexed { index, action ->
                    sb.append("${index + 1}. $action\n")
                }
            }
        }
        ExecutionStatus.FAILED -> {
            sb.append("任务执行过程中遇到问题未能完成。\n\n")
            sb.append("📊 执行概况：执行了 $stepCount 个步骤，耗时 $duration。\n")
            if (keyActions.isNotEmpty()) {
                sb.append("\n🔑 已完成的操作：\n")
                keyActions.take(3).forEachIndexed { index, action ->
                    sb.append("${index + 1}. $action\n")
                }
            }
            sb.append("\n💡 建议：可以检查网络连接、应用状态后重试。")
        }
        ExecutionStatus.STOPPED -> {
            sb.append("任务已被手动停止。\n\n")
            sb.append("📊 执行概况：停止前执行了 $stepCount 个步骤，耗时 $duration。\n")
            if (keyActions.isNotEmpty()) {
                sb.append("\n🔑 已完成的操作：\n")
                keyActions.take(3).forEachIndexed { index, action ->
                    sb.append("${index + 1}. $action\n")
                }
            }
        }
        ExecutionStatus.RUNNING -> {
            sb.append("任务正在执行中...\n\n")
            sb.append("📊 当前进度：已执行 $stepCount 个步骤。")
        }
    }

    return sb.toString().trim()
}

/**
 * 从日志中提取步骤数
 */
private fun extractStepCountFromLogs(logs: List<String>): Int {
    var maxStep = 0
    for (log in logs) {
        if (log.contains("Step") || log.contains("步骤")) {
            val match = Regex("""(?:Step|步骤)\s*(\d+)""").find(log)
            match?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it > maxStep) maxStep = it
            }
        }
    }
    return maxStep
}

/**
 * 从日志中提取关键操作
 */
private fun extractKeyActions(logs: List<String>): List<String> {
    val actions = mutableListOf<String>()

    for (log in logs) {
        when {
            // 应用启动
            log.contains("Launch") || log.contains("打开") -> {
                val appMatch = Regex("""(?:Launch|打开)[:\s]*[{]?(?:app[=:]\s*)?([^}\n,]+)""").find(log)
                appMatch?.groupValues?.getOrNull(1)?.let { app ->
                    actions.add("打开应用「${app.trim()}」")
                }
            }
            // 点击操作
            log.contains("Tap") && log.contains("动作:") -> {
                actions.add("执行点击操作")
            }
            // 输入操作
            (log.contains("Type") || log.contains("输入")) && log.contains("动作:") -> {
                val textMatch = Regex("""text[=:]\s*([^}\n,]+)""").find(log)
                textMatch?.groupValues?.getOrNull(1)?.let { text ->
                    val displayText = if (text.length > 20) text.take(20) + "..." else text
                    actions.add("输入文本「$displayText」")
                }
            }
            // 滑动操作
            log.contains("Swipe") || log.contains("滑动") -> {
                actions.add("执行滑动操作")
            }
            // 返回操作
            log.contains("Back") && log.contains("动作:") -> {
                actions.add("返回上一页")
            }
            // 完成
            log.contains("Finish") || log.contains("完成:") -> {
                val msgMatch = Regex("""(?:Finish|完成)[:\s]*(.+)""").find(log)
                msgMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() && it != "{}" }?.let {
                    actions.add("完成: ${it.trim()}")
                }
            }
        }
    }

    return actions.distinct()
}
