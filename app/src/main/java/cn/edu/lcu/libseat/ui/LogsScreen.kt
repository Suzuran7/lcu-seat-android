package cn.edu.lcu.libseat.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.edu.lcu.libseat.core.LogEntry
import cn.edu.lcu.libseat.core.TimeKit

@Composable
fun LogsScreen(vm: AppViewModel) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Panel(title = "后台与唤醒") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("守护模式", style = MaterialTheme.typography.bodyMedium)
                        Hint("开启后前台服务持续持有 CPU 唤醒锁")
                    }
                    Switch(checked = vm.guardEnabled, onCheckedChange = { vm.setGuard(it) })
                }
                Hint(
                    if (vm.guardEnabled) {
                        "当前：CPU 保持唤醒，凌晨定时任务不会被系统休眠打断。建议插着充电时使用。"
                    } else {
                        "当前：系统可能进入深度休眠。若任务在凌晨经常不执行，请开启守护模式。"
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    )
                                }
                            }
                        },
                    ) { Text(if (vm.canScheduleExactAlarms()) "精确闹钟已允许" else "允许精确闹钟") }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        },
                    ) { Text("忽略电池优化") }
                }
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    },
                ) { Text("通知设置") }
            }
        }

        item {
            Panel(title = "下次执行") {
                val enabled = vm.rules.filter { it.enabled }
                if (enabled.isEmpty()) {
                    Hint("没有启用中的任务")
                } else {
                    enabled.sortedBy { TimeKit.nextTriggerText(it) }.forEach { rule ->
                        Row {
                            Text(
                                text = TimeKit.nextTriggerText(rule),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "  ${rule.display}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "执行记录",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { vm.refreshLogs() }) { Text("刷新") }
                TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
            }
        }

        if (vm.logs.isEmpty()) {
            item {
                Panel { Hint("暂无执行记录。任务执行后会在这里看到每一步结果。") }
            }
        }

        items(vm.logs) { entry ->
            LogCard(entry)
        }
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (entry.ok) Color(0xFF2E7D5B) else Color(0xFFB3261E))
            )
            Text(
                text = "  ${entry.ruleName}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = TimeKit.prettyTime(entry.time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.account.isNotBlank()) {
            Text(
                text = "账号 ${entry.account}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
