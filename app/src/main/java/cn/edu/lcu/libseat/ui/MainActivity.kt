package cn.edu.lcu.libseat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import cn.edu.lcu.libseat.SeatApp
import cn.edu.lcu.libseat.service.TaskService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
        setContent {
            SeatTheme {
                AppRoot()
            }
        }
        restoreGuard()
    }

    /** 用户上次开着守护模式时，进入 App 后立刻恢复，避免界面状态与实际不符。 */
    private fun restoreGuard() {
        val app = application as? SeatApp ?: return
        if (!app.store.guardEnabled) return
        val intent = Intent(this, TaskService::class.java).apply {
            action = TaskService.ACTION_START_GUARD
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    companion object {
        private const val REQUEST_NOTIFICATION = 100
    }
}
