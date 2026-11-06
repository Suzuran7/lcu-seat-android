package cn.edu.lcu.libseat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.lcu.libseat.core.RoomInfo
import cn.edu.lcu.libseat.core.Rule
import cn.edu.lcu.libseat.core.TaskAction
import cn.edu.lcu.libseat.core.TimeKit
import java.util.UUID

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun RulesScreen(vm: AppViewModel) {
    var editing by remember { mutableStateOf<Rule?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(
                onClick = {
                    editing = Rule(
                        id = UUID.randomUUID().toString(),
                        accountId = vm.accounts.firstOrNull()?.id.orEmpty(),
                        name = "",
                    )
                },
                enabled = vm.accounts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.accounts.isEmpty()) "请先添加账号" else "新建定时任务") }
        }

        if (vm.rules.isEmpty()) {
            item {
                Panel {
                    Hint("还没有任务。任务会在设定时间自动登录、按顺序尝试你指定的座位并提交预约，成功或失败都会发通知。")
                    Hint("「取消预约」任务可以在每天固定时间自动撤销该账号名下的进行中预约。")
                }
            }
        }

        items(vm.rules, key = { it.id }) { rule ->
            RuleCard(vm = vm, rule = rule, onEdit = { editing = rule })
        }

        item {
            Panel(title = "定时说明") {
                Hint("时间按北京时间计算。到达触发时间后按「重试间隔」反复尝试，直到成功或超过「最长重试」分钟数。")
                Hint("若手机在凌晨进入深度休眠，请到「记录」页开启守护模式并允许后台运行。")
            }
        }
    }

    editing?.let { draft ->
        RuleEditor(
            vm = vm,
            initial = draft,
            onDismiss = { editing = null },
            onSave = {
                vm.saveRule(it)
                editing = null
            },
        )
    }
}

private fun ruleSummary(vm: AppViewModel, rule: Rule): String {
    val account = vm.accounts.firstOrNull { it.id == rule.accountId }?.display ?: "账号已删除"
    val days = if (rule.weekdays.size >= 7) {
        "每天"
    } else {
        rule.weekdays.sorted().joinToString("") { WEEK_LABELS.getOrElse(it - 1) { "?" } }
    }
    val action = if (rule.action == TaskAction.BOOK) "预约" else "取消"
    val window = if (rule.action == TaskAction.BOOK) {
        " · 今天 ${rule.start}-${rule.end}"
    } else {
        ""
    }
    val seats = if (rule.seatNames.isNotEmpty()) rule.seatNames.joinToString("、") else "自动选座"
    return "$account · $days ${rule.triggerTime} $action\n${rule.roomName.ifBlank { "未选房间" }} $seats$window"
}

@Composable
private fun RuleCard(vm: AppViewModel, rule: Rule, onEdit: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.display, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = ruleSummary(vm, rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = { vm.toggleRule(rule, it) },
            )
        }
        Hint("下次执行：${TimeKit.nextTriggerText(rule)}")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.runRuleNow(rule.id) }) { Text("立即执行") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = { vm.removeRule(rule.id) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RuleEditor(
    vm: AppViewModel,
    initial: Rule,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var showRoomPicker by remember { mutableStateOf(false) }
    val isBook = draft.action == TaskAction.BOOK

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        text = if (initial.name.isBlank() && initial.roomName.isBlank()) "新建任务" else "编辑任务",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(
                        onClick = {
                            val problem = validate(vm, draft)
                            if (problem != null) {
                                vm.message = problem
                            } else {
                                onSave(draft)
                            }
                        },
                    ) { Text("保存") }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Field(
                        label = "任务名称",
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        placeholder = "例如 早八自习",
                    )

                    Text("使用账号", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        options = vm.accounts.map { it.id to it.display },
                        selected = draft.accountId,
                        onSelect = { draft = draft.copy(accountId = it) },
                    )

                    Text("任务类型", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        options = listOf(
                            TaskAction.BOOK to "预约座位",
                            TaskAction.CANCEL to "取消预约",
                        ),
                        selected = draft.action,
                        onSelect = { draft = draft.copy(action = it) },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(
                            label = "触发时间",
                            value = draft.triggerTime,
                            onValueChange = { draft = draft.copy(triggerTime = it) },
                            placeholder = "07:00",
                            modifier = Modifier.weight(1f),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Hint("每天在此时刻执行")
                            Hint("建议比放座时间早 1 分钟")
                        }
                    }

                    Text("执行日", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WEEK_LABELS.forEachIndexed { index, label ->
                            val iso = index + 1
                            FilterChip(
                                selected = iso in draft.weekdays,
                                onClick = {
                                    draft = draft.copy(
                                        weekdays = if (iso in draft.weekdays) {
                                            draft.weekdays - iso
                                        } else {
                                            (draft.weekdays + iso).sorted()
                                        }
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }

                    if (isBook) {
                        Text("房间", style = MaterialTheme.typography.labelLarge)
                        KeyValue("已选房间", draft.roomName.ifBlank { "未选择" })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showRoomPicker = true }) { Text("选择房间") }
                            if (vm.rooms.isEmpty()) {
                                TextButton(onClick = { vm.loadSeatMap() }) { Text("加载房间列表") }
                            }
                        }

                        Text("优先座位", style = MaterialTheme.typography.labelLarge)
                        KeyValue(
                            "已选座位",
                            draft.seatNames.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "未指定（房间内自动）",
                        )
                        OutlinedButton(
                            onClick = {
                                if (draft.roomId <= 0) {
                                    vm.message = "请先选择房间，再去座位图挑座位"
                                } else {
                                    vm.saveRule(draft)
                                    vm.startPickingForRule(draft.id, draft)
                                    onDismiss()
                                }
                            },
                        ) { Text("去座位图挑选座位") }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Field(
                                label = "开始时间",
                                value = draft.start,
                                onValueChange = { draft = draft.copy(start = it) },
                                placeholder = "09:00 或 now",
                                modifier = Modifier.weight(1f),
                            )
                            Field(
                                label = "结束时间",
                                value = draft.end,
                                onValueChange = { draft = draft.copy(end = it) },
                                placeholder = "22:00",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Hint("开始时间填 now 表示从现在开始，仅对今天有效。")

                        SwitchRow(
                            label = "指定座位都不可用时自动选空位",
                            checked = draft.autoFallback,
                            onChange = { draft = draft.copy(autoFallback = it) },
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(
                            label = "重试间隔（秒）",
                            value = draft.intervalSec.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { draft = draft.copy(intervalSec = it) }
                            },
                            numeric = true,
                            modifier = Modifier.weight(1f),
                        )
                        Field(
                            label = "最长重试（分钟）",
                            value = draft.maxMinutes.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { draft = draft.copy(maxMinutes = it) }
                            },
                            numeric = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    SwitchRow(
                        label = "执行前获取 CPU 唤醒锁",
                        checked = draft.keepAwake,
                        onChange = { draft = draft.copy(keepAwake = it) },
                    )

                    Hint("失败会按间隔一直重试，直到成功或超过最长重试时间；会话失效会自动重新登录。")
                    Column(modifier = Modifier.heightIn(min = 24.dp)) {}
                }
            }
        }
    }

    if (showRoomPicker) {
        RoomPickerDialog(
            vm = vm,
            onDismiss = { showRoomPicker = false },
            onPick = { room ->
                draft = draft.copy(
                    roomId = room.id,
                    roomName = room.name,
                    buildingId = room.buildingId,
                    buildingName = room.buildingName,
                    floor = room.floor,
                )
                showRoomPicker = false
            },
        )
    }
}

private fun validate(vm: AppViewModel, rule: Rule): String? {
    if (vm.accounts.none { it.id == rule.accountId }) return "请选择账号"
    if (rule.weekdays.isEmpty()) return "至少选择一个执行日"
    if (!TimeKit.isValidHhmm(rule.triggerTime)) return "触发时间格式应为 HH:mm，例如 07:00"
    if (rule.action == TaskAction.BOOK) {
        if (rule.roomId <= 0) return "请选择房间"
        if (!rule.end.matches(Regex("""\d{2}:\d{2}"""))) return "结束时间格式应为 HH:mm"
        if (!rule.start.equals("now", true) && !TimeKit.isValidHhmm(rule.start)) {
            return "开始时间格式应为 HH:mm 或 now"
        }
        if (!rule.start.equals("now", true) &&
            TimeKit.toMinuteId(rule.start).toInt() >= TimeKit.toMinuteId(rule.end).toInt()
        ) {
            return "结束时间必须晚于开始时间"
        }
    }
    return null
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
