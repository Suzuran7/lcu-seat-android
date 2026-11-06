package cn.edu.lcu.libseat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.lcu.libseat.core.Account
import cn.edu.lcu.libseat.core.CaptchaSolver
import androidx.compose.material3.OutlinedTextField

@Composable
fun AccountsScreen(vm: AppViewModel) {
    var editing by remember { mutableStateOf<Account?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(
                onClick = { editing = Account() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加账号") }
        }

        if (vm.accounts.isEmpty()) {
            item {
                Panel {
                    Hint("还没有账号。添加后点一次「登录测试」，程序会用验证码自动登录并把会话保存在本机，之后定时任务直接复用，不必反复登录。")
                }
            }
        }

        items(vm.accounts, key = { it.id }) { account ->
            AccountCard(vm = vm, account = account, onEdit = { editing = account })
        }

        item {
            Panel(title = "多账号说明") {
                Hint("每个账号有独立的登录会话与任务，互不影响。取消预约只会取消该账号名下的进行中预约。")
                Hint("账号密码保存在本机加密存储中，不会上传到任何第三方服务器。")
            }
        }
    }

    editing?.let { draft ->
        AccountDialog(
            initial = draft,
            onDismiss = { editing = null },
            onSave = {
                vm.saveAccount(it)
                editing = null
            },
        )
    }

    vm.loginCaptcha?.let { ui ->
        LoginCaptchaDialog(vm = vm, ui = ui)
    }
}

/** 自动识别失败时的兜底：看清 4 位字符手动输入，一次就能把会话保存下来。 */
@Composable
private fun LoginCaptchaDialog(vm: AppViewModel, ui: LoginCaptchaUi) {
    AlertDialog(
        onDismissRequest = { vm.dismissLoginCaptcha() },
        title = { Text("输入登录验证码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "账号 ${ui.accountLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "自动识别没通过，请输入图片里的 4 位字符（只能是小写字母或数字）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Image(
                    bitmap = ui.image.asImageBitmap(),
                    contentDescription = "登录验证码",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp),
                    contentScale = ContentScale.Fit,
                )
                OutlinedTextField(
                    value = vm.loginCaptchaAnswer,
                    onValueChange = { value ->
                        vm.loginCaptchaAnswer = value
                            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                            .lowercase()
                            .take(4)
                    },
                    label = { Text("验证码") },
                    placeholder = { Text("4 位小写字母或数字") },
                    singleLine = true,
                    isError = vm.loginCaptchaAnswer.isNotEmpty() &&
                        !CaptchaSolver.isValidLoginCode(vm.loginCaptchaAnswer),
                    supportingText = {
                        Text("${vm.loginCaptchaAnswer.length}/4 · 仅限小写字母与数字")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.error.isNotBlank()) {
                    Text(
                        text = ui.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { vm.refreshLoginCaptcha() }) { Text("看不清，换一张") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.submitLoginCaptcha() },
                enabled = CaptchaSolver.isValidLoginCode(vm.loginCaptchaAnswer),
            ) { Text("登录") }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissLoginCaptcha() }) { Text("取消") }
        },
    )
}

@Composable
private fun AccountCard(vm: AppViewModel, account: Account, onEdit: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(account.display, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = account.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 只显示长度不显示内容，方便核对有没有少输/多输字符。
                Text(
                    text = "密码已保存，共 ${account.password.length} 位",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(if (account.hasSession) "已保存会话" else "未登录") },
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.loginAccount(account.id) }) { Text("登录测试") }
            OutlinedButton(onClick = { vm.queryReservations(account.id) }) { Text("预约记录") }
            OutlinedButton(onClick = { vm.cancelReservations(account.id) }) { Text("取消预约") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = { vm.removeAccount(account.id) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AccountDialog(
    initial: Account,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加账号" else "编辑账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("备注名", label, { label = it }, placeholder = "例如 我 / 室友")
                Field("学号（登录账号）", username, { username = it })
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("账号密码仅保存在本机加密存储中，用于自动登录。")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.trim(),
                            username = username.trim(),
                            password = password,
                        )
                    )
                },
                enabled = username.isNotBlank() && password.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
