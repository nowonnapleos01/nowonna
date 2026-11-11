package ai.nowonna.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 픽업 1건의 상태와 서버 동기화. 화면(Activity)과 분리돼 있다.
 * 운전 중에는 Pleos 내비가 앞에 있고 나온나는 뒤에 있으므로,
 * 위치 전송과 요청 받기는 화면이 없어도 계속 돌아야 한다.
 * 내비가 앞에 있을 때 온 변경 요청은 헤드업 알림(승인/유지 버튼)으로 보여준다.
 */
object Pickup {
    private const val CHANNEL = "pickup_requests"
    private const val NOTI_REQUEST = 1001
    private const val NOTI_APPLIED = 1002

    lateinit var car: CarSignals
        private set
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var loop: Job? = null
    private var lastChangeAt = 0L

    var session by mutableStateOf<PickupSession?>(null)
        private set
    /** 운전자 답을 기다리는 변경 요청 (C3 카드 / 헤드업 알림) */
    var request by mutableStateOf<ChangeRequest?>(null)
        private set
    /** 자동 반영됐다는 알림 (C2 위 토스트) */
    var autoApplied by mutableStateOf<Place?>(null)
    /** 받는 사람이 링크를 처음 연 시각 */
    var viewedAt by mutableStateOf<Long?>(null)
        private set
    /** 받는 사람이 미리 남긴 다음 목적지 (서버에서 받음, 탑승 후 C5) */
    var nextPlace by mutableStateOf<Place?>(null)
        private set
    /** 서버에 공유 종료를 이미 알렸는지 (탑승하면 C5 화면은 남기고 공유만 먼저 끝낸다) */
    var sharingEnded by mutableStateOf(false)
        private set
    /** 운전자·차 정보 (한 번 등록) */
    var profile by mutableStateOf(DriverProfile())
        private set
    /** 앱 화면이 앞에 있는지 (뒤에 있으면 알림으로 보여준다) */
    var inForeground = false

    /** 앱 시작 시 한 번. 화면이 다시 만들어져도 SDK 연결과 픽업 상태는 유지된다. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        profile = ProfileStore.load(app)
        Phone.init(app)
        car = CarSignals(app).also { it.start() }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "픽업 요청", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "받는 사람의 장소 변경 요청"
        })
    }

    fun saveProfile(p: DriverProfile) {
        profile = p
        ProfileStore.save(app, p)
    }

    // ---------- 시작 · 종료 ----------

    fun start(place: Place, contact: Contact, permission: SharePermission) {
        car.beginPickup()
        // 받는 사람이 정하는 경우엔 정해질 때까지 경로를 걸지 않는다
        if (place.hasLocation) car.requestRoute(place.lat, place.lng, place.name)
        val p = profile
        session = PickupSession(place, contact, permission, notifyBeforeSec = p.notifyBeforeSec)
        PickupService.start(app, contact.name) // 내비가 앞에 있어도 멈추지 않게
        request = null; autoApplied = null; viewedAt = null; nextPlace = null; sharingEnded = false; lastChangeAt = 0L
        scope.launch {
            val created = ServerClient.createSession(contact, permission, place.takeIf { it.hasLocation }, p)
            if (created == null) { car.note("서버 연결 실패: ${ServerClient.baseUrl} 이 켜져 있는지 확인하세요"); return@launch }
            session = session?.copy(serverId = created.id, link = created.link)
            car.note("받는 사람 링크: ${created.link}")
            startSync(created.id)
        }
    }

    /** 화면까지 모두 닫는다 (C2 공유 끝내기 · C4 끝내기 · C5 완료) */
    fun end() {
        stopSharing()
        session = null
        nextPlace = null
        car.endPickup()
    }

    /**
     * 위치 공유만 끝낸다: 서버에 종료 → 링크 만료·위치 삭제, 상시 알림 끄기.
     * 탑승하면 이걸 먼저 부르고 C5(다음 목적지) 화면은 남겨 둔다.
     */
    private fun stopSharing() {
        if (sharingEnded) return
        sharingEnded = true
        val id = session?.serverId
        loop?.cancel(); loop = null
        clearRequest()
        PickupService.stop(app)
        if (id != null) scope.launch { ServerClient.end(id) }
    }

    /** 탑승 감지 → 받는 사람 웹에 '탑승'을 한 번 보내고 공유를 끝낸다 */
    fun onBoarded() {
        val s = session ?: return
        if (sharingEnded) return
        car.note("탑승 → 공유 자동 종료")
        val id = s.serverId
        val lat = car.lat; val lng = car.lng
        if (id != null && lat != null && lng != null) {
            loop?.cancel(); loop = null
            scope.launch {
                ServerClient.sendLocation(id, lat, lng, car.heading, car.remainSec, car.remainMeters, "BOARDED", car.hazardOn)
                stopSharing()
            }
        } else stopSharing()
    }

    /** 탑승 후 받는 사람이 남긴 다음 목적지로 */
    fun goNext(next: Place) {
        end()
        car.requestRoute(next.lat, next.lng, next.name)
        openNavi() // 안내를 걸고 바로 내비 화면으로
    }

    /** Pleos 내비 화면을 앞으로 띄운다 */
    private fun openNavi() {
        val intent = app.packageManager.getLaunchIntentForPackage(NAVI_PACKAGE)
            ?: Intent().setClassName(NAVI_PACKAGE, "$NAVI_PACKAGE.MainActivity")
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { car.note("내비 화면 열기 실패: ${it.message}") }
    }

    private const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"

    // ---------- 서버 동기화 (5초마다, 모의 주행 중엔 1초마다) ----------

    private fun startSync(id: String) {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val lat = car.lat; val lng = car.lng
                if (lat != null && lng != null) {
                    val state = when { car.boardedAt != null -> "BOARDED"; car.arrivedAt != null -> "ARRIVED"; else -> "DRIVING" }
                    when (val res = ServerClient.sendLocation(id, lat, lng, car.heading, car.remainSec, car.remainMeters, state, car.hazardOn)) {
                        is ServerClient.SyncResult.Expired -> {
                            car.note("링크가 만료됐어요 → 공유 종료")
                            end()
                            return@launch
                        }
                        is ServerClient.SyncResult.Failed -> car.note("서버 전송 실패 (${ServerClient.baseUrl})")
                        is ServerClient.SyncResult.Ok -> onReply(res.reply)
                    }
                }
                delay(if (car.mockDriving) 1000 else 5000) // 모의 주행 중엔 웹에서 부드럽게 움직이도록 1초마다
            }
        }
    }

    private fun onReply(r: ServerClient.LocationReply) {
        if (r.viewedAt != null && viewedAt == null) {
            viewedAt = r.viewedAt
            car.note("${session?.contact?.name}이(가) 링크를 열었어요")
        }
        if (r.nextPlace != nextPlace) {
            r.nextPlace?.let { car.note("다음 목적지 받음: ${it.name}") }
            nextPlace = r.nextPlace
        }
        val pc = r.pendingChange
        if (pc != null && pc.at != lastChangeAt) {
            lastChangeAt = pc.at
            if (r.changeApplied) applyDirectly(pc, fromServer = true) else receive(pc)
        }
    }

    // ---------- 장소 변경 ----------

    /** 서버나 시연 버튼에서 들어온 변경 요청 */
    fun receive(r: ChangeRequest) {
        val s = session ?: return
        if (sharingEnded) return
        if (s.permission == SharePermission.AUTO) { applyDirectly(r, fromServer = false); return }
        car.note("장소 변경 요청: ${r.place.name} \"${r.message}\"")
        request = r // 새 요청이 오면 이전 것을 대신한다 (운전자는 항상 최신 요청만 본다)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, CHANGE_REQUEST_TIMEOUT_MS.toLong())
        if (!inForeground) notifyRequest(s, r)
    }

    fun accept() {
        val r = request ?: return
        car.note("변경 승인 → ${r.place.name}")
        changePlace(r.place)
        clearRequest()
        session?.serverId?.let { id -> scope.launch { ServerClient.resolve(id, accepted = true); restartMockIfDriving() } }
    }

    fun keep() {
        val r = request ?: return
        car.note("변경 안 함 (원래 장소 유지): ${r.place.name}")
        clearRequest()
        session?.serverId?.let { id -> scope.launch { ServerClient.resolve(id, accepted = false) } }
    }

    private val timeout = Runnable { keep() } // 10초 무응답 → 유지

    private fun applyDirectly(r: ChangeRequest, fromServer: Boolean) {
        val s = session ?: return
        car.note("바로 반영: ${s.contact.name} → ${r.place.name}")
        changePlace(r.place)
        autoApplied = r.place
        if (fromServer) s.serverId?.let { id -> scope.launch { ServerClient.resolve(id, accepted = true); restartMockIfDriving() } }
        else restartMockIfDriving()
        if (!inForeground) notifyApplied(s, r.place)
    }

    private fun changePlace(p: Place) {
        car.requestRoute(p.lat, p.lng, p.name)
        session = session?.copy(place = p)
    }

    // ---------- 모의 주행 (개발 빌드 · 에뮬레이터 시연용) ----------

    /** 시연 버튼: 지금 위치 → 만날 곳 도로 경로를 서버에서 받아 따라 달린다. 한 번 더 누르면 멈춤 */
    fun toggleMockDrive() {
        if (car.mockDriving) { car.stopMockDrive(); return }
        startMockDrive()
    }

    /**
     * 모의 주행 중 만날 곳이 바뀌면 달리던 자리에서 새 장소로 다시 출발.
     * 서버가 새 장소를 알아야 새 경로를 주므로, 서버에 승인(resolve)을 보낸 뒤에 부른다.
     */
    private fun restartMockIfDriving() { if (car.mockDriving) startMockDrive() }

    private fun startMockDrive() {
        val s = session ?: return
        val id = s.serverId ?: run { car.note("모의 주행: 아직 서버 연결 전이에요"); return }
        if (!s.place.hasLocation) { car.note("모의 주행: 만날 곳이 정해지면 달릴 수 있어요"); return }
        val lat = car.lat; val lng = car.lng
        if (lat == null || lng == null) { car.note("모의 주행: 차 위치를 아직 못 받았어요"); return }
        scope.launch {
            // 서버가 지금 위치에서 경로를 찾도록 위치를 먼저 보낸다
            val state = if (car.arrivedAt != null) "ARRIVED" else "DRIVING"
            val res = ServerClient.sendLocation(id, lat, lng, car.heading, car.remainSec, car.remainMeters, state, car.hazardOn)
            if (res is ServerClient.SyncResult.Ok) onReply(res.reply)
            val road = ServerClient.route(id)
            val place = session?.place ?: return@launch
            if (road.isEmpty()) car.note("모의 주행: 도로 경로를 못 받아서 직선으로 가요")
            car.startMockDrive(listOf(doubleArrayOf(lat, lng)) + road + listOf(doubleArrayOf(place.lat, place.lng)))
        }
    }

    private fun clearRequest() {
        main.removeCallbacks(timeout)
        request = null
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(NOTI_REQUEST)
    }

    // ---------- 헤드업 알림 (내비가 앞에 있을 때) ----------

    private fun notifyRequest(s: PickupSession, r: ChangeRequest) {
        val title = "${if (s.permission == SharePermission.SUGGEST) "장소 변경 제안" else "장소 수정 요청"} · ${s.contact.name}"
        val text = buildString {
            if (r.message.isNotBlank()) append("\"${r.message}\" ")
            append("→ ${r.place.name} (지금 장소에서 ${distanceLabel(s.place, r.place)})")
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(CHANGE_REQUEST_TIMEOUT_MS.toLong())
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, "이번만 변경", action(Actions.ACCEPT))
            .addAction(0, "유지", action(Actions.KEEP))
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTI_REQUEST, n)
    }

    private fun notifyApplied(s: PickupSession, p: Place) {
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${s.contact.name}이(가) 만날 곳을 바꿨어요")
            .setContentText("${p.name} · 내비에 바로 반영했어요")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(6000)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTI_APPLIED, n)
    }

    private fun action(a: String) = PendingIntent.getBroadcast(
        app, a.hashCode(), Intent(app, Actions::class.java).setAction(a),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openApp() = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 알림의 [이번만 변경] / [유지] 버튼 */
    class Actions : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACCEPT -> Pickup.accept()
                KEEP -> Pickup.keep()
            }
        }
        companion object {
            const val ACCEPT = "ai.nowonna.car.ACCEPT_CHANGE"
            const val KEEP = "ai.nowonna.car.KEEP_PLACE"
        }
    }
}
