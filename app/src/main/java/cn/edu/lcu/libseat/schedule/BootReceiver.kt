package cn.edu.lcu.libseat.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.edu.lcu.libseat.SeatApp
import cn.edu.lcu.libseat.service.TaskService

/** 重启或应用升级后闹钟会丢失，这里重新排一遍；守护模式也一并恢复。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val app = context.applicationContext as? SeatApp ?: return
        AlarmScheduler(context).syncAll(app.store.rules())

        if (app.store.guardEnabled) {
            val service = Intent(context, TaskService::class.java).apply {
                this.action = TaskService.ACTION_START_GUARD
            }
            runCatching { ContextCompat.startForegroundService(context, service) }
        }
    }
}
