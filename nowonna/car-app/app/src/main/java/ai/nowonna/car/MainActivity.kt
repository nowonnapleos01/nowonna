/*
 * 나온나 차량 앱 진입점. 화면은 이 Activity 하나이고, 픽업 상태(Pickup.session 등)에 따라 C1~C6 화면을 바꿔 끼운다.
 * 개발 빌드에는 Pleos SDK 값을 그대로 보여주는 "SDK 확인" 화면이 함께 있다.
 */
package ai.nowonna.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0A0D12)
private val Card = Color(0xFF161C24)
private val Amber = Color(0xFFFFB224)
private val TextMain = Color(0xFFE6EAF0)
private val TextSub = Color(0xFFA9B4C4)

/**
 * 나온나 차량 앱의 유일한 화면(Activity). 상태에 따라 화면을 바꿔 끼운다.
 *
 *   픽업 없음      → C1 픽업 시작 (+ C6 통화 후 제안, 내 차 정보)
 *   픽업 진행 중   → C2 공유 중 → C3 변경 요청 카드 → C4 도착 → C5 탑승
 *   개발 빌드 전용 → SDK 확인 화면 (차량·내비 원시 값과 이벤트 기록)
 *
 * 픽업 상태와 서버 동기화는 화면과 분리된 [Pickup] 이 맡는다.
 */
class MainActivity : ComponentActivity() {

    private lateinit var car: CarSignals

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pickup.init(this)
        car = Pickup.car
        requestNeededPermissions()

        setContent {
            var showSdkCheck by remember { mutableStateOf(false) }
            var showProfile by remember { mutableStateOf(false) }
            val session = Pickup.session
            when {
                showSdkCheck && BuildConfig.DEV_TOOLS -> SdkCheckScreen(car, onBack = { showSdkCheck = false })
                showProfile && session == null -> ScaledUi(1.3f) {
                    ProfileScreen(
                        initial = Pickup.profile, parked = car.parked,
                        onSave = { Pickup.saveProfile(it); showProfile = false },
                        onBack = { showProfile = false },
                    )
                }
                session == null -> ScaledUi(1.3f) {
                    C1PickupStart(
                        car = car,
                        profile = Pickup.profile,
                        onShareAndGo = { place, contact, perm -> Pickup.start(place, contact, perm) },
                        onOpenSdkCheck = { showSdkCheck = true },
                        onOpenProfile = { showProfile = true },
                    )
                }
                else -> ScaledUi(1.3f) {
                    C2Sharing(car = car, session = session, onGoNext = { Pickup.goNext(it) }, onEnd = { Pickup.end() })
                }
            }
        }
    }

    /**
     * 알림(헤드업 요청 카드), 연락처·최근 통화(C1 목록, C6 통화 후 제안).
     * 거절해도 앱은 돌아간다: 알림 대신 화면 카드, 연락처 대신 안내 문구.
     */
    private fun requestNeededPermissions() {
        val wanted = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.READ_PHONE_STATE)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    /** 권한 창에서 답하면 연락처·통화 기록을 다시 읽는다 */
    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { Phone.refresh() }

    override fun onResume() { super.onResume(); Pickup.inForeground = true; Phone.refresh() }
    override fun onPause() { Pickup.inForeground = false; super.onPause() }
    // 차량·내비 연결(CarSignals)은 화면이 다시 만들어져도 유지한다 → onDestroy에서 해제하지 않는다
}

/** 차량 화면이 넓어서 C1 전체를 키운다 (dp·글자 모두) */
@Composable
private fun ScaledUi(scale: Float, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale),
        content = content
    )
}

@Composable
private fun SdkCheckScreen(car: CarSignals, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 왼쪽: 현재 값 + 버튼
        Column(Modifier.weight(1f)) {
            Text("나온나 · SDK 확인", color = Amber, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton("서울역으로 경로 요청") { car.requestTestRoute() }
                BigButton("내비 정보 새로고침") { car.refreshNavi() }
                BigButton("경고등 새로고침") { car.refreshTelltales() }
                BigButton("← C1") { onBack() }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.fillMaxWidth().background(Card).padding(16.dp)) {
                items(car.values.entries.sortedBy { it.key }.toList()) { (k, v) ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(k, color = TextSub, fontSize = 18.sp, modifier = Modifier.width(180.dp))
                        Text(v, color = TextMain, fontSize = 18.sp)
                    }
                }
            }
        }
        // 오른쪽: 이벤트 기록
        Column(Modifier.weight(1f)) {
            Text("이벤트 기록", color = TextSub, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxSize().background(Card).padding(16.dp)) {
                items(car.logs) { line ->
                    Text(line, color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF2A1A00))
    ) {
        Text(label, fontSize = 18.sp, modifier = Modifier.padding(8.dp))
    }
}
