package ai.nowonna.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 차량 신호 모음: Pleos Vehicle SDK + NaviHelper SDK 를 한곳에서 다룬다.
 *
 * - 기어(P 여부), 문(탑승 감지), 계기판 경고등(비상등)  ← Vehicle SDK
 * - 현재 위치, 남은 시간·거리, 목적지 설정, 도착 이벤트  ← NaviHelper SDK
 *
 * 화면(Compose)은 여기 값을 그대로 읽는다 (mutableStateOf 라서 값이 바뀌면 화면이 다시 그려진다).
 * values / logs 는 개발 빌드의 "SDK 확인" 화면에 보여주는 원시 값과 이벤트 기록이다.
 */
class CarSignals(context: Context) {

    val values = mutableStateMapOf<String, String>()
    val logs = mutableStateListOf<String>()

    // 화면에서 바로 쓰는 값
    var gear by mutableStateOf<VehicleGear?>(null)
        private set
    /**
     * 설정 화면을 열어도 되는 상태 = 기어가 P로 확인됐을 때만.
     * 기어를 못 읽으면(null) 주행 중일 수도 있으니 잠근다.
     */
    val parked get() = gear == VehicleGear.GEAR_PARK
    /** 비상등: 계기판 경고등에서 좌·우 방향지시등이 동시에 켜져 있으면 비상등으로 본다 */
    var hazardOn by mutableStateOf(false)
        private set
    /** 경고등 정보를 한 번이라도 받았는지 (못 받으면 비상등 감지를 못 하는 차) */
    var hazardSupported by mutableStateOf(false)
        private set
    var remainSec by mutableStateOf<Int?>(null)
        private set
    var remainMeters by mutableStateOf<Int?>(null)
        private set

    // 차 현재 위치 (Pickup이 서버로 보내 받는 사람 웹에 표시)
    var lat by mutableStateOf<Double?>(null)
        private set
    var lng by mutableStateOf<Double?>(null)
        private set
    var heading by mutableStateOf(0.0)
        private set

    // Pleos 내비에서 읽어 온 장소들 (C1 목적지 카드)
    var naviDestination by mutableStateOf<Place?>(null)
        private set
    val recents = mutableStateListOf<Place>()
    val bookmarks = mutableStateListOf<Place>()
    /** 운전자가 내비에서 목적지를 새로 정한 시각. C1이 "이 목적지 공유할까요?"를 띄우는 신호 */
    var destinationChangedAt by mutableStateOf<Long?>(null)
        private set

    // 픽업 세션 상태 (C2): 도착·탑승 시각
    var pickupActive = false
        private set
    var arrivedAt by mutableStateOf<Long?>(null)
        private set
    var boardedAt by mutableStateOf<Long?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private val navi = NaviHelper(context.applicationContext)

    private val doorAreas = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    /** 탑승으로 보는 문: 조수석·뒷문. 운전석(ROW_1_LEFT)은 운전자가 잠깐 내릴 수 있어서 뺀다. */
    private val boardingDoors = setOf(DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
    /** 지금 열려 있는 문 (메인 스레드에서만 바꾼다) */
    private val openDoors = mutableSetOf<DoorValue>()

    // ---------- 리스너 ----------

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            main.post { this@CarSignals.gear = gear }
            set("기어", gear?.name ?: "null")
        }
        override fun onFailed(e: Exception) = log("기어 리스너 실패: ${e.message}")
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            set("방향지시등", value?.name ?: "null")
            log("방향지시등: ${value?.name}")
        }
        override fun onFailed(e: Exception) = log("방향지시등 리스너 실패: ${e.message}")
    }

    private val doorListeners = doorAreas.associateWith { area ->
        object : DoorPositionListener {
            // 콜백에는 어느 문인지 정보가 없고, 에뮬레이터에서는 모든 문 변화가 첫 리스너로만 오기도 한다.
            // 그래서 문 이벤트가 오면 네 문을 모두 다시 읽어서 어느 문이 바뀌었는지 직접 확인한다.
            override fun onPositionUpdated(pos: Int?) = refreshDoors()
            override fun onFailed(e: Exception) = log("문 ${area.name} 리스너 실패: ${e.message}")
        }
    }

    /**
     * 탑승 = 도착한 뒤 조수석·뒷문이 "열렸다가 닫힘".
     * 열리자마자 끝내면 짐만 싣거나 문을 잘못 열어도 공유가 끝나 버린다.
     */
    private fun onDoor(area: DoorValue, pos: Int?) {
        val open = (pos ?: 0) > 0
        val wasOpen = area in openDoors
        if (open) openDoors.add(area) else openDoors.remove(area)
        if (open == wasOpen) return
        if (area !in boardingDoors) { log("문 ${if (open) "열림" else "닫힘"}: ${area.name} (운전석 → 탑승으로 안 봄)"); return }
        log("문 ${if (open) "열림" else "닫힘"}: ${area.name}")
        if (!open && pickupActive && arrivedAt != null && boardedAt == null) {
            log("탑승 감지: ${area.name} 열림 → 닫힘")
            boardedAt = System.currentTimeMillis()
        }
    }

    private fun refreshDoors() = safe("문 조회") {
        val door = vehicle.getDoor()
        doorAreas.forEach { area ->
            door.getCurrentDoorPosition(DoorArea(area), { pos ->
                set("문 ${area.name}", pos?.toString() ?: "null")
                main.post { onDoor(area, pos) }
            }, { log("문 ${area.name} 조회 실패: ${it.message}") })
        }
    }

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(info: Telltales?) { info?.let { onTelltales(it) } }
        override fun onFailed(e: Exception) = log("경고등 리스너 실패: ${e.message}")
    }

    private fun onTelltales(info: Telltales) {
        fun lit(t: TelltaleType) = info.telltales.any { it.type == t && it.state != TelltaleState.OFF }
        val on = lit(TelltaleType.TURN_SIGNAL_LEFT) && lit(TelltaleType.TURN_SIGNAL_RIGHT)
        set("비상등", if (on) "켜짐" else "꺼짐")
        main.post {
            hazardSupported = true
            if (on != hazardOn) log("비상등 ${if (on) "켜짐" else "꺼짐"}")
            hazardOn = on
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            set("현재 위치", "%.5f, %.5f (방향 %.0f°)".format(info.latitude, info.longitude, info.heading))
            main.post { if (!mockHold) { lat = info.latitude; lng = info.longitude; heading = info.heading } }
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            set("목적지", "${info.name} (%.5f, %.5f)".format(info.latitude, info.longitude))
            main.post {
                if (!mockHold) { remainSec = info.remainTime; remainMeters = info.remainDistance }
                naviDestination = if (info.latitude == 0.0 && info.longitude == 0.0) null
                else Place(info.name ?: "목적지", info.address ?: "", info.latitude, info.longitude, Place.Source.NAVI)
            }
            set("남은 시간 / 거리", "${info.remainTime}초 / ${info.remainDistance}m")
        }

        override fun onRouteStateInfo(info: RouteStateInfo) =
            set("경로 안내 중", "${info.isRouting} (${info.naviMode})")

        override fun onTripInfo(info: TripInfo) = set("Trip ETA / 거리", "${info.eta} / ${info.distance}m")

        override fun onRoutingCompleted() { log("경로 탐색 완료"); refreshNavi() }
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) { log("경로 안내 시작"); refreshNavi() }
        override fun onDestinationChanged() {
            log("목적지 변경됨")
            main.post { if (!pickupActive) destinationChangedAt = System.currentTimeMillis() }
            refreshNavi()
        }

        override fun onRecentDestinationInfo(list: List<ai.pleos.playground.navi.data.RecentDestinationInfo>) {
            set("최근 목적지", "${list.size}개 " + list.take(3).joinToString { it.name ?: "?" })
            main.post {
                recents.clear()
                recents.addAll(list.map { Place(it.name ?: "이름 없음", it.address ?: "", it.latitude, it.longitude, Place.Source.RECENT) })
            }
        }

        override fun onBookmarkInfo(list: List<ai.pleos.playground.navi.data.BookmarkInfo>) {
            set("즐겨찾기", "${list.size}개 " + list.take(3).joinToString { "${it.name}(${it.category})" })
            main.post {
                bookmarks.clear()
                bookmarks.addAll(list.map { Place(bookmarkLabel(it.name, it.category?.name), it.address ?: "", it.latitude, it.longitude, Place.Source.BOOKMARK) })
            }
        }
        override fun onRouteCancelled() = log("경로 취소됨")
        override fun onRouteEnded() = log("경로 안내 종료")

        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            log("목적지 도착: ${info.name} (소요 ${info.duration}초, ${info.distance}m)  → 도착 알림 후보")
            main.post { if (pickupActive && arrivedAt == null) arrivedAt = System.currentTimeMillis() }
        }

        override fun onError(code: NaviErrorCode) = log("Navi 오류: $code")
    }

    // ---------- 시작 / 종료 ----------

    fun start() {
        startVehicle()
        startNavi()
    }

    // 기어: Pleos SDK의 기어 API는 "Android 표준 GEAR_SELECTION 속성을 쓰라"며 deprecated 표시돼 있지만,
    // Pleos Connect 2.x 에뮬레이터에서 실제로 값이 들어오는 건 이 API라서 그대로 쓴다.
    @Suppress("DEPRECATION")
    private fun startVehicle() {
        try {
            vehicle.initialize()
            log("Vehicle 초기화 성공")
        } catch (e: Exception) {
            log("Vehicle 초기화 실패: ${e.message}")
            return
        }

        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ g -> main.post { this.gear = g }; set("기어", g?.name ?: "null") }, { log("기어 조회 실패: ${it.message}") })
        safe("기어 리스너") { gear.registerGearSelection(gearListener) }

        val turn = vehicle.getTurnSignal()
        turn.getCurrentTurnSignalState({ set("방향지시등", it?.name ?: "null") }, { log("방향지시등 조회 실패: ${it.message}") })
        safe("방향지시등 리스너") { turn.registerTurnSignalState(turnSignalListener) }

        val door = vehicle.getDoor()
        doorListeners.forEach { (area, listener) ->
            door.getCurrentDoorPosition(DoorArea(area), { pos ->
                set("문 ${area.name}", pos?.toString() ?: "null")
                main.post { if ((pos ?: 0) > 0) openDoors.add(area) } // 시작할 때 이미 열려 있던 문
            }, { log("문 ${area.name} 조회 실패: ${it.message}") })
            safe("문 ${area.name} 리스너") { door.registerDoorPosition(DoorArea(area), listener) }
        }

        vehicle.getDrivingMode().getCurrentDrivingModeState(
            { set("주행 모드", it?.name ?: "null") }, { log("주행 모드 조회 실패: ${it.message}") })

        // 비상등은 전용 API가 없어서, 계기판 경고등(Telltale)의 좌·우 방향지시등으로 판단한다.
        refreshTelltales()
        safe("경고등 리스너") { vehicle.getSafety().registerTelltales(telltaleListener) }
    }

    private fun startNavi() {
        navi.initialize(
            {
                log("NaviHelper 초기화 성공")
                navi.addListener(naviListener)
                refreshNavi()
                refreshPlaces()
            },
            { log("NaviHelper 초기화 실패: ${it.message}") }
        )
    }

    @Suppress("DEPRECATION") // 기어 리스너 해제 (위 startVehicle 설명 참고)
    fun stop() {
        main.removeCallbacks(poll)
        safe("Vehicle 해제") {
            vehicle.getGear().unregisterGearSelection(gearListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            doorListeners.forEach { (area, l) -> vehicle.getDoor().unregisterDoorPosition(DoorArea(area), l) }
            vehicle.getSafety().unregisterTelltales(telltaleListener)
            vehicle.release()
        }
        safe("Navi 해제") {
            navi.removeListener(naviListener)
            navi.release()
        }
    }

    // ---------- 버튼 동작 ----------

    /** 개발 빌드 "SDK 확인" 화면용: 서울역으로 경로 요청 (requestRoute 동작 확인) */
    fun requestTestRoute() = requestRoute(37.55465, 126.97060, "서울역")

    fun beginPickup() {
        stopMockDrive()
        pickupActive = true
        arrivedAt = null; boardedAt = null; remainSec = null; remainMeters = null
    }

    fun endPickup() {
        stopMockDrive()
        pickupActive = false
        main.removeCallbacks(poll)
        safe("경로 취소") { navi.cancelRoute() }
        log("픽업 종료: 공유 링크 만료")
    }

    /** 정차(P) + 만날 곳 가까이 → 도착 */
    fun markArrived(reason: String) {
        if (!pickupActive || arrivedAt != null) return
        log("도착 감지: $reason")
        arrivedAt = System.currentTimeMillis()
    }

    /** 시연 버튼용 (개발 빌드에서만 보임): 에뮬레이터에서 실제로 운전하지 않고 다음 단계로 */
    fun simulateArrive() = markArrived("시연 버튼")
    fun simulateBoard() { if (pickupActive && boardedAt == null) { if (arrivedAt == null) arrivedAt = System.currentTimeMillis(); boardedAt = System.currentTimeMillis() } }

    /** 픽업 장소를 차량 내비 목적지로 설정 */
    fun requestRoute(lat: Double, lng: Double, name: String) = safe("경로 요청") {
        navi.requestRoute(RouteInfo(lat, lng, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
        log("경로 요청 보냄: $name")
        startPolling()
    }

    // 픽업 중에는 5초마다 내비에서 위치·남은 시간을 다시 읽는다 (Pickup이 이 값을 서버로 보낸다)
    private val poll = object : Runnable {
        override fun run() { refreshNavi(); main.postDelayed(this, 5000) }
    }

    private fun startPolling() {
        main.removeCallbacks(poll)
        main.postDelayed(poll, 1500)
    }

    fun refreshNavi() = safe("Navi 조회") {
        navi.getCurrentLocationInfo()
        navi.getDestinationInfo()
        navi.getRouteStateInfo()
    }

    /** C1을 열 때: 내비의 현재 목적지 + 최근 목적지 + 즐겨찾기 */
    fun refreshPlaces() = safe("장소 조회") {
        navi.getDestinationInfo()
        navi.getRecentDestinationInfo()
        navi.getBookmarkInfo()
    }

    fun clearDestinationPrompt() { destinationChangedAt = null }

    fun refreshTelltales() {
        vehicle.getSafety().getTelltalesInfo(
            { set("경고등(Telltale)", it.toString().take(300)); onTelltales(it) },
            { log("경고등 조회 실패: ${it.message}") }
        )
    }

    // ---------- 모의 주행 (개발 빌드 · 에뮬레이터 시연용) ----------
    // Pleos 에뮬레이터는 내비 위치가 한 곳에 고정돼 차가 움직이지 않는다.
    // 모의 주행 중에는 내비 위치·남은 시간 대신, 받은 도로 경로를 따라 1초마다 움직인 값을 쓴다.
    // 화면·서버 전송·도착 처리는 실제 주행과 똑같이 이 값을 읽는다.

    var mockDriving by mutableStateOf(false)
        private set
    /** 모의 주행 위치를 쓰는 중: 도착해서 멈춘 뒤에도 픽업이 끝날 때까지 그 자리를 유지한다 (안 그러면 내비 고정 위치로 튄다) */
    private var mockHold = false
    private var mockPath: List<DoubleArray> = emptyList() // [위도, 경도]
    private var mockCum = DoubleArray(0) // 출발점부터 각 점까지 거리(m)
    private var mockRun = 0.0
    /** 1초에 움직이는 거리: 시연이 너무 길지 않게 전체를 약 90초에 달린다 (짧으면 시속 54km 그대로) */
    private var mockStep = MOCK_SPEED_MPS

    private val mockTick = object : Runnable {
        override fun run() { stepMock(); if (mockDriving) main.postDelayed(this, 1000) }
    }

    /** path: 지금 위치 → 만날 곳 도로 경로 (메인 스레드에서 부른다) */
    fun startMockDrive(path: List<DoubleArray>) {
        if (path.size < 2) { log("모의 주행: 경로가 없어요"); return }
        mockPath = path
        mockCum = DoubleArray(path.size)
        for (i in 1 until path.size) mockCum[i] = mockCum[i - 1] + distM(path[i - 1], path[i])
        mockRun = 0.0
        mockStep = maxOf(MOCK_SPEED_MPS, mockCum.last() / MOCK_MAX_SEC)
        mockDriving = true; mockHold = true
        main.removeCallbacks(mockTick)
        stepMock()
        main.postDelayed(mockTick, 1000)
        log("모의 주행 시작: %.1fkm · 약 %d초".format(mockCum.last() / 1000, (mockCum.last() / mockStep).toInt()))
    }

    fun stopMockDrive() {
        if (!mockHold) return
        mockDriving = false; mockHold = false
        main.removeCallbacks(mockTick)
        log("모의 주행 멈춤")
        refreshNavi() // 내비 값으로 되돌린다
    }

    private fun stepMock() {
        val total = mockCum.last()
        mockRun = minOf(total, mockRun + mockStep)
        var i = 1
        while (i < mockPath.size - 1 && mockCum[i] < mockRun) i++
        val a = mockPath[i - 1]; val b = mockPath[i]
        val seg = mockCum[i] - mockCum[i - 1]
        val f = if (seg > 0) ((mockRun - mockCum[i - 1]) / seg).coerceIn(0.0, 1.0) else 1.0
        lat = a[0] + (b[0] - a[0]) * f
        lng = a[1] + (b[1] - a[1]) * f
        if (seg > 0) heading = bearing(a, b)
        val left = total - mockRun
        remainMeters = left.toInt()
        // 남은 시간은 실제 도로 속도(시속 54km)로 계산: 화면 속 이동만 빠르고, 나갈 타이밍·도착 알림은 실제와 같은 시점에 뜬다
        remainSec = (left / MOCK_SPEED_MPS).toInt()
        if (left < 1) {
            mockDriving = false
            main.removeCallbacks(mockTick)
            log("모의 주행 도착")
            markArrived("모의 주행 도착")
        }
    }

    // ---------- 도우미 ----------

    private fun set(key: String, value: String) {
        main.post { values[key] = value }
    }

    fun note(msg: String) = log(msg)

    private fun log(msg: String) {
        android.util.Log.i("Nowonna", msg) // adb logcat -s Nowonna
        val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        main.post {
            logs.add(0, "$time  $msg")
            if (logs.size > 100) logs.removeAt(logs.lastIndex)
        }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("$what 실패: ${e.message}")
        }
    }
}

/** 즐겨찾기는 이름이 비어 있고 종류(HOME/OFFICE)만 오는 경우가 있다 */
internal fun bookmarkLabel(name: String?, category: String?): String = when {
    !name.isNullOrBlank() -> name
    category == "HOME" -> "집"
    category == "OFFICE" -> "회사"
    else -> "즐겨찾기"
}

/** 모의 주행 속도 (m/s). 15 = 시속 54km */
const val MOCK_SPEED_MPS = 15.0
/** 모의 주행은 길어도 이 시간(초) 안에 도착한다 */
const val MOCK_MAX_SEC = 90.0

internal fun distM(a: DoubleArray, b: DoubleArray): Double {
    val r = Math.PI / 180
    val h = Math.pow(Math.sin((b[0] - a[0]) * r / 2), 2.0) +
        Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.pow(Math.sin((b[1] - a[1]) * r / 2), 2.0)
    return 2 * 6371000 * Math.asin(Math.sqrt(h))
}

internal fun bearing(a: DoubleArray, b: DoubleArray): Double {
    val r = Math.PI / 180
    val y = Math.sin((b[1] - a[1]) * r) * Math.cos(b[0] * r)
    val x = Math.cos(a[0] * r) * Math.sin(b[0] * r) - Math.sin(a[0] * r) * Math.cos(b[0] * r) * Math.cos((b[1] - a[1]) * r)
    return (Math.atan2(y, x) / r + 360) % 360
}
