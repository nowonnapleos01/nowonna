package ai.nowonna.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 공유 중에만 켜지는 포그라운드 서비스.
 * 운전 중엔 Pleos 내비가 앞에 있어서 나온나는 뒤에 있는데, 안드로이드는 뒤에 있는 앱을 얼린다.
 * "나온나 공유 중" 상시 알림을 띄워 두는 동안은 위치 전송·요청 받기가 멈추지 않는다.
 * 운전자에게도 위치가 공유되고 있다는 걸 계속 보여준다.
 */
class PickupService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "상대"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "공유 중 표시", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("나온나 공유 중 · $name")
            .setContentText("${name}님이 차 위치와 도착 시간을 보고 있어요")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pickup_live"
        private const val ID = 1000
        private const val EXTRA_NAME = "name"

        fun start(context: Context, contactName: String) {
            context.startForegroundService(Intent(context, PickupService::class.java).putExtra(EXTRA_NAME, contactName))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PickupService::class.java))
        }
    }
}
