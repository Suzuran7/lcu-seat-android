package cn.edu.lcu.libseat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val TABS = listOf("账号", "定时任务", "座位图", "记录")

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.pendingRuleId) {
        if (vm.pendingRuleId != null) tab = 2
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("图书馆自动选座", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = vm.busy ?: statusText(vm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.busy != null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        TabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) },
                )
            }
        }

        vm.message?.let { text ->
            MessageBanner(text = text, onDismiss = vm::clearMessage)
        }

        Box(modifier = Modifier.weight(1f).navigationBarsPadding()) {
            when (tab) {
                0 -> AccountsScreen(vm)
                1 -> RulesScreen(vm)
                2 -> SeatMapScreen(vm)
                else -> LogsScreen(vm)
            }
        }
    }
}

private fun statusText(vm: AppViewModel): String {
    val enabled = vm.rules.count { it.enabled }
    val guard = if (vm.guardEnabled) " · 守护中" else ""
    return "${vm.accounts.size} 个账号 · $enabled 个任务$guard"
}

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
