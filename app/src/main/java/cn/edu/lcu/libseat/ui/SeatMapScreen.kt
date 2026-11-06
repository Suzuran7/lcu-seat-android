package cn.edu.lcu.libseat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.lcu.libseat.core.LayoutCell
import cn.edu.lcu.libseat.core.RoomInfo
import cn.edu.lcu.libseat.core.RoomLayout
import cn.edu.lcu.libseat.core.SeatItem
import cn.edu.lcu.libseat.core.TimeKit
import kotlin.math.roundToInt

private val SeatFreeColor = Color(0xFFCDECCF)
private val SeatUsedColor = Color(0xFFF8C9C2)
private val SeatAwayColor = Color(0xFFFBE3B0)
private val SeatFullColor = Color(0xFFCBD5E6)
private val SeatDisabledColor = Color(0xFFEDEFF3)
private val DeskColor = Color(0xFFDDE3EE)

@Composable
fun SeatMapScreen(vm: AppViewModel) {
    var showRooms by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (vm.layout == null) vm.loadSeatMap()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (vm.pendingRuleId != null) {
                PickBanner(vm)
            }

            if (vm.accounts.size > 1) {
                ChoiceChips(
                    options = vm.accounts.map { it.id to it.display },
                    selected = vm.accountForSeatMap,
                    onSelect = {
                        vm.setSeatMapAccount(it)
                        vm.loadSeatMap()
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "今天",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { showRooms = true },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                ) { Text(vm.roomName.ifBlank { "选择房间" }, maxLines = 1, fontSize = 12.sp) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = vm.windowStart,
                    onValueChange = { vm.windowStart = it },
                    label = { Text("开始", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = vm.windowEnd,
                    onValueChange = { vm.windowEnd = it },
                    label = { Text("结束", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(96.dp),
                )
                Button(onClick = { vm.loadSeatMap(false) }) { Text("刷新") }
            }

            Legend()

            vm.layout?.let { current ->
                val free = current.seats.count { it.usable && it.status == "FREE" }
                Text(
                    text = "${current.roomName}：共 ${current.seats.size} 个座位，空闲 $free 个（${TimeKit.todayIso()}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val current = vm.layout
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (vm.seatLoading) "正在加载座位图…" else "选择房间后显示座位图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SeatGrid(vm = vm, layout = current)
            }
            if (vm.seatLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        vm.selectedSeat?.let { seat ->
            SelectedSeatPanel(vm = vm, seat = seat)
        }
    }

    if (showRooms) {
        RoomPickerDialog(
            vm = vm,
            onDismiss = { showRooms = false },
            onPick = { room ->
                vm.selectRoom(room)
                showRooms = false
            },
        )
    }

    vm.captcha?.let { captcha ->
        CaptchaDialog(vm = vm, captcha = captcha)
    }
}

@Composable
private fun PickBanner(vm: AppViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "为任务挑选座位（已选 ${vm.pickedSeats.size} 个）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = vm.pickedSeats.joinToString("、") { it.second }.ifBlank { "点座位加入优先级列表" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.applyPickedToRule() }) { Text("保存到任务") }
                TextButton(onClick = { vm.cancelPicking() }) { Text("取消") }
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem(SeatFreeColor, "空闲")
        LegendItem(SeatUsedColor, "使用中")
        LegendItem(SeatAwayColor, "暂离")
        LegendItem(SeatFullColor, "已约满")
        LegendItem(SeatDisabledColor, "停用")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SeatGrid(vm: AppViewModel, layout: RoomLayout) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll)
            .horizontalScroll(hScroll)
            .padding(6.dp),
    ) {
        Column {
            for (row in 0 until layout.rows) {
                Row {
                    for (col in 0 until layout.cols) {
                        val index = row * layout.cols + col
                        SeatCell(vm = vm, cell = layout.cells.getOrNull(index))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatCell(vm: AppViewModel, cell: LayoutCell?) {
    val seat = cell?.seat
    val base = Modifier.size(36.dp).padding(2.dp)

    if (seat == null) {
        val color = if (cell?.kind == "desk") DeskColor else Color.Transparent
        Box(modifier = base.clip(RoundedCornerShape(4.dp)).background(color))
        return
    }

    val picked = vm.pickedSeats.any { it.first == seat.id }
    val selected = vm.selectedSeat?.id == seat.id
    val background = when {
        !seat.enabled || seat.status == "STOP" || seat.status == "DISABLED" -> SeatDisabledColor
        seat.status == "FREE" -> SeatFreeColor
        seat.status == "IN_USE" -> SeatUsedColor
        seat.status == "AWAY" -> SeatAwayColor
        seat.status == "FULL" || seat.status == "BOOKED" -> SeatFullColor
        else -> SeatDisabledColor
    }

    Box(
        modifier = base
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
            .clickable { vm.selectSeat(seat) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seat.name.takeLast(3),
            fontSize = 8.sp,
            maxLines = 1,
            color = Color(0xFF2A2E35),
        )
        if (picked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun SelectedSeatPanel(vm: AppViewModel, seat: SeatItem) {
    val picked = vm.pickedSeats.any { it.first == seat.id }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "座位 ${seat.name} · ${seat.statusText}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = vm.selectedSeatNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vm.selectedSeatNote.startsWith("✅")) {
                            Color(0xFF2E7D5B)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = { vm.clearSelectedSeat() }) { Text("关闭") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.pendingRuleId != null) {
                    OutlinedButton(onClick = { vm.togglePickSeat(seat) }) {
                        Text(if (picked) "移出任务" else "加入任务")
                    }
                } else {
                    OutlinedButton(onClick = { vm.startInteractiveBooking(seat) }) { Text("手动预约") }
                }
            }
        }
    }
}

@Composable
fun RoomPickerDialog(vm: AppViewModel, onDismiss: () -> Unit, onPick: (RoomInfo) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择房间") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (vm.rooms.isEmpty()) {
                    Text(
                        text = "还没有房间列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.loadSeatMap() }) { Text("从服务器加载") }
                }
                vm.rooms.forEach { room ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(room) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${room.buildingName} · ${room.floor} · 编号 ${room.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CaptchaDialog(vm: AppViewModel, captcha: CaptchaUi) {
    Dialog(
        onDismissRequest = { vm.dismissCaptcha() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("点选验证码", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "请按顺序点击图中的 ${captcha.count} 个文字（已点 ${captcha.points.size}/${captcha.count}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("提示", style = MaterialTheme.typography.bodySmall)
                    Image(
                        bitmap = captcha.word.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.height(34.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                val ratio = captcha.background.width.toFloat() / captcha.background.height.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(captcha.token) {
                            detectTapGestures { offset ->
                                val scale = 330f / size.width.coerceAtLeast(1)
                                vm.addTap(
                                    (offset.x * scale).roundToInt(),
                                    (offset.y * scale).roundToInt(),
                                )
                            }
                        },
                ) {
                    Image(
                        bitmap = captcha.background.asImageBitmap(),
                        contentDescription = "验证码背景",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        captcha.points.forEach { point ->
                            val x = point.x / 330f * size.width
                            val y = point.y / 330f * size.width
                            drawCircle(Color(0xFFFF3B30), radius = 13f, center = Offset(x, y))
                            drawCircle(Color.White, radius = 7f, center = Offset(x, y))
                            drawCircle(Color(0xFFFF3B30), radius = 3f, center = Offset(x, y))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.undoLastTap() }) { Text("撤销") }
                    TextButton(onClick = { vm.startInteractiveBooking(captcha.seat) }) { Text("换一张") }
                    Button(onClick = { vm.submitCaptcha() }) { Text("提交预约") }
                }
                Text(
                    text = "点击位置须与提示字一一对应；服务器校验通过后立即提交预约。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
