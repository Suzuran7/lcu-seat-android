package cn.edu.lcu.libseat

import android.app.Application
import cn.edu.lcu.libseat.core.SeatStore
import cn.edu.lcu.libseat.schedule.AlarmScheduler
import cn.edu.lcu.libseat.service.Notifier

class SeatApp : Application() {

    lateinit var store: SeatStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = SeatStore(this)
        Notifier.createChannels(this)
        // 每次启动都按最新任务重排闹钟，避免卸载重装/数据恢复后漏排。
        AlarmScheduler(this).syncAll(store.rules())
    }

    companion object {
        lateinit var instance: SeatApp
            private set
    }
}
