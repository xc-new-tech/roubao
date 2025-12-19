package com.roubao.autopilot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.data.PlaybackState
import com.roubao.autopilot.data.PlaybackStatus
import com.roubao.autopilot.data.Script
import com.roubao.autopilot.ui.theme.BaoziTheme

@Composable
fun ScriptsScreen(
    scripts: List<Script>,
    playbackState: PlaybackState,
    onScriptClick: (Script) -> Unit,
    onPlayScript: (Script) -> Unit,
    onStopScript: () -> Unit,
    onDeleteScript: (String) -> Unit
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
                    text = "脚本",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "共 ${scripts.size} 个脚本",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        // 播放状态卡片
        if (playbackState.isPlaying) {
            PlaybackStatusCard(
                playbackState = playbackState,
                onStop = onStopScript
            )
        }

        if (scripts.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📜",
                        fontSize = 64.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无脚本",
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "在执行记录中可以将成功的任务保存为脚本",
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                }
            }
        } else {
            // 脚本列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = scripts,
                    key = { it.id }
                ) { script ->
                    ScriptCard(
                        script = script,
                        isPlaying = playbackState.currentScript?.id == script.id,
                        onClick = { onScriptClick(script) },
                        onPlay = { onPlayScript(script) },
                        onDelete = { onDeleteScript(script.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackStatusCard(
    playbackState: PlaybackState,
    onStop: () -> Unit
) {
    val colors = BaoziTheme.colors
    val script = playbackState.currentScript ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放动画指示
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = playbackState.message,
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                if (script.loopConfig.enabled) {
                    Text(
                        text = "第 ${playbackState.currentLoop} 轮 / ${if (script.loopConfig.loopCount == 0) "∞" else script.loopConfig.loopCount}",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                }
            }

            // 停止按钮
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("停止", fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ScriptCard(
    script: Script,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = BaoziTheme.colors
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = { Text("删除脚本", color = colors.textPrimary) },
            text = { Text("确定要删除脚本「${script.name}」吗？", color = colors.textSecondary) },
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
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) colors.primary.copy(alpha = 0.1f) else colors.backgroundCard
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 脚本图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.secondary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📜",
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (script.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = script.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${script.actionCount} 步",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = script.formattedCreatedAt,
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    if (script.loopConfig.enabled) {
                        Text(
                            text = "·",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "循环",
                            tint = colors.secondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (script.loopConfig.loopCount == 0) "∞" else "${script.loopConfig.loopCount}",
                            fontSize = 12.sp,
                            color = colors.secondary
                        )
                    }
                }
            }

            // 播放按钮
            IconButton(
                onClick = onPlay,
                enabled = !isPlaying
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = if (isPlaying) colors.textHint else colors.primary
                )
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
