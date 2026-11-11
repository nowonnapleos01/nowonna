/*
 * C2 · 공유 중 화면 (링크 보냄 → 링크 확인 → 나갈 타이밍 → 도착 → 탑승 단계 표시)
 * 화면은 상태를 보여주기만 하고, 도착·탑승 판단은 차량 신호(CarSignals)와 Pickup이 한다.
 * 도착하면 C4, 탑승하면 C5로 바뀐다. 개발 빌드에서만 보이는 시연 버튼(DevLink)도 여기 있다.
 */
package ai.nowonna.car

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** 공유 중인 픽업 1건 */
data class PickupSession(
    val place: Place,
    val contact: Contact,
    val permission: SharePermission,
    val sharedAt: Long = System.currentTimeMillis(),
    val notifyBeforeSec: Int = 180, // 도착 3분 전 알림
    val serverId: String? = null,   // 서버 세션 id (생성 전엔 null)
    val link: String? = null,       // 받는 사람에게 보내는 링크
)

/**
 * C2 · 공유 중 (주행 중에도 보는 화면)
 * 길 안내는 Pleos 내비가 한다. 나온나는 "상대가 지금 어디까지 알고 있는지"만 보여준다.
 * 운전자가 할 일은 없다. 단계는 차량 신호로 자동으로 넘어간다.
 */
@Composable
fun C2Sharing(
    car: CarSignals,
    session: PickupSession,
    onGoNext: (Place) -> Unit,
    onEnd: () -> Unit,
) {
    // 장소 변경 요청·자동 반영·링크 확인은 화면 밖 Pickup이 관리한다 (내비가 앞에 있어도 동작)
    val request = Pickup.request
    val autoApplied = Pickup.autoApplied
    val viewedAt = Pickup.viewedAt

    val parked = car.parked // 기어를 못 읽으면 잠금
    val sec = car.remainSec
    val waitingForPlace = session.place.source == Place.Source.RECIPIENT && !session.place.hasLocation

    // 남은 시간이 알림 기준 아래로 내려간 순간 = 나갈 타이밍 알림 발송
    var notifiedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(sec) {
        if (notifiedAt == null && sec != null && sec in 1..session.notifyBeforeSec) {
            notifiedAt = System.currentTimeMillis()
            car.note("나갈 타이밍 알림 → ${session.contact.name} (남은 ${sec}초)")
        }
    }
    LaunchedEffect(car.arrivedAt) { if (car.arrivedAt != null) car.note("도착 알림 → ${session.contact.name}") }
    // 탑승(조수석·뒷문 열림→닫힘) → 받는 사람 웹에 '탑승'을 알리고 공유를 바로 끝낸다
    LaunchedEffect(car.boardedAt) { if (car.boardedAt != null) Pickup.onBoarded() }

    val boarded = car.boardedAt != null
    val arrived = car.arrivedAt != null

    // 정차(P) + 만날 곳 50m 이내면 도착으로 본다 (내비 도착 이벤트와 함께 사용)
    LaunchedEffect(car.gear, car.remainMeters) {
        // 0은 "경로를 아직 못 받음"이라 뺀다 (경로가 잡히기 전엔 남은 거리가 0으로 온다)
        val near = (car.remainMeters ?: -1) in 1..ARRIVE_RADIUS_M
        if (!arrived && !waitingForPlace && car.parked && near) car.markArrived("P + ${car.remainMeters}m")
    }

    // 받는 사람이 미리 남긴 다음 목적지 (서버에서 받음)
    val nextPlace = Pickup.nextPlace
    val demoBoard = { car.simulateBoard() }

    if (boarded) {
        // 지금 차 위치 → 다음 목적지 거리 (작은 글씨로 보여준다)
        val lat = car.lat; val lng = car.lng
        val distance = if (nextPlace != null && lat != null && lng != null)
            distanceLabel(Place("", "", lat, lng, Place.Source.NAVI), nextPlace) else null
        C5Boarded(session, nextPlace, distance, onGoNext = onGoNext, onDone = onEnd)
        return
    }
    if (arrived) {
        C4Arrived(car, session, Pickup.profile, onEnd) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                DevLink("탑승 시연") { demoBoard() }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Nw.Bg).padding(24.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {

            // ---------- 왼쪽: 상대와 진행 단계 ----------
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                    .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(28.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(session.contact, 64)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.contact.name, color = Nw.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                boarded -> "탑승했어요 · 공유가 끝났어요"
                                else -> "${session.permission.label} 권한으로 공유 중"
                            },
                            color = if (boarded) Nw.Green else Nw.Sub, fontSize = 17.sp
                        )
                    }
                    LivePill(boarded)
                }
                Spacer(Modifier.height(28.dp))

                val notifyDetail = when {
                    notifiedAt != null -> "${clock(notifiedAt!!)} 보냄 · 도착 ${session.notifyBeforeSec / 60}분 전"
                    sec != null && sec > 0 -> "약 ${((sec - session.notifyBeforeSec).coerceAtLeast(0) + 59) / 60}분 뒤 자동으로 보내요"
                    else -> "도착 ${session.notifyBeforeSec / 60}분 전에 자동으로 보내요"
                }
                val steps = buildList {
                    add(Step("링크 보냄", clock(session.sharedAt), StepState.DONE))
                    add(Step("링크 확인", viewedAt?.let { "${clock(it)} · ${session.contact.name}이(가) 열어봤어요" } ?: "열어보면 여기에 표시돼요",
                        if (viewedAt != null) StepState.DONE else StepState.WAIT))
                    if (waitingForPlace) add(Step("만날 곳 정하기", "${session.contact.name}이(가) 링크에서 정하는 중", StepState.NOW))
                    add(Step("나갈 타이밍 알림", notifyDetail, if (notifiedAt != null) StepState.DONE else if (waitingForPlace) StepState.WAIT else StepState.NOW))
                    add(Step("도착", car.arrivedAt?.let { "${clock(it)} · '도착했어요' 보냄" } ?: "도착하면 자동으로 알려요",
                        if (arrived) StepState.DONE else StepState.WAIT))
                    add(Step("탑승", car.boardedAt?.let { "${clock(it)} · 문 열림→닫힘으로 확인" } ?: "타고 문을 닫으면 공유가 자동으로 끝나요",
                        if (boarded) StepState.DONE else StepState.WAIT))
                }
                Timeline(steps)
            }

            // ---------- 오른쪽: 만날 곳과 도착 시간 ----------
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                        .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(28.dp)
                ) {
                    Text("만날 곳", color = Nw.Sub, fontSize = 17.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PBadge(size = 44, fontSize = 22, bg = if (waitingForPlace) Nw.CardHi else Nw.Amber, fg = if (waitingForPlace) Nw.Sub else Nw.OnAmber)
                        Spacer(Modifier.width(14.dp))
                        Text(if (waitingForPlace) "정하는 중…" else session.place.name, color = Nw.Text, fontSize = 28.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))

                    when {
                        boarded -> BigStatus("탑승 완료", "좋은 이동 되세요", Nw.Green)
                        arrived -> BigStatus("도착", "${session.contact.name}에게 알렸어요", Nw.Amber)
                        waitingForPlace -> BigStatus("대기 중", "정해지면 내비에 바로 들어가요", Nw.Sub)
                        sec != null && sec > 0 -> {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${(sec + 59) / 60}", color = Nw.Text, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                                Text("분", color = Nw.Text, fontSize = 30.sp, modifier = Modifier.padding(bottom = 16.dp, start = 4.dp))
                            }
                            Text("${arrivalClock(sec)} 도착 · %.1fkm".format((car.remainMeters ?: 0) / 1000.0), color = Nw.Sub, fontSize = 22.sp)
                        }
                        else -> BigStatus("경로 찾는 중", "내비에서 안내가 시작돼요", Nw.Sub)
                    }
                }

                Spacer(Modifier.height(16.dp))
                // 버튼: 정차 중이거나 끝났을 때만
                Box(Modifier.alpha(if (parked || boarded) 1f else 0.3f)) {
                    if (boarded) PrimaryButton("완료", enabled = true, onClick = onEnd)
                    else SecondaryButton(if (parked) "공유 끝내기" else "주행 중에는 잠겨 있어요", enabled = parked, onClick = onEnd)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    DevLink(if (car.mockDriving) "모의 주행 멈춤" else "모의 주행") { Pickup.toggleMockDrive() }
                    DevLink("변경 요청 시연") { Pickup.receive(demoChangeRequest(session.place)) }
                    DevLink("도착 시연") { car.simulateArrive() }
                    DevLink("탑승 시연") { demoBoard() }
                }
            }
        }

        request?.let { r ->
            C3ChangeRequest(
                request = r, contact = session.contact, permission = session.permission, current = session.place,
                onAccept = { Pickup.accept() },
                onKeep = { if (Pickup.request == r) Pickup.keep() },
            )
        }
        autoApplied?.let { p -> AutoAppliedToast(session.contact, p) { Pickup.autoApplied = null } }
    }
}

/** 데모용 요청: 지금 장소 근처의 다른 출구 */
private fun demoChangeRequest(current: Place): ChangeRequest {
    val base = if (current.hasLocation) current else Place("삼성역 5번 출구", "", 37.50885, 127.06310, Place.Source.NAVI)
    return ChangeRequest(
        place = Place("삼성역 6번 출구", "강남구 대치동", base.lat - 0.0015, base.lng + 0.0012, Place.Source.RECIPIENT),
        message = "6번 출구 쪽에 있어요",
    )
}

// ---------- 진행 단계 ----------

private enum class StepState { DONE, NOW, WAIT }
private data class Step(val title: String, val detail: String, val state: StepState)

@Composable
private fun Timeline(steps: List<Step>) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseA"
    )
    steps.forEachIndexed { i, s ->
        Row {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val dotColor by animateColorAsState(if (s.state == StepState.DONE) Nw.Green else Color.Transparent, label = "dot")
                val dot = Modifier.size(34.dp).clip(CircleShape)
                when (s.state) {
                    StepState.DONE -> Box(dot.background(dotColor), contentAlignment = Alignment.Center) {
                        Text("✓", color = Nw.Bg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    StepState.NOW -> Box(dot.border(3.dp, Nw.Amber.copy(alpha = pulse), CircleShape).background(Nw.AmberDim))
                    StepState.WAIT -> Box(dot.border(2.dp, Nw.Line, CircleShape))
                }
                if (i < steps.lastIndex) {
                    Box(Modifier.width(3.dp).height(46.dp).background(if (s.state == StepState.DONE) Nw.Green else Nw.Line))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.padding(top = 2.dp)) {
                Text(s.title, color = if (s.state == StepState.WAIT) Nw.Faint else Nw.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(s.detail, color = if (s.state == StepState.WAIT) Nw.Faint else Nw.Sub, fontSize = 16.sp)
            }
        }
    }
}

// ---------- 도우미 ----------

@Composable
private fun LivePill(ended: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (ended) Color(0xFF12302A) else Nw.AmberDim)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ended) Nw.Green else Nw.Amber))
        Spacer(Modifier.width(8.dp))
        Text(if (ended) "종료" else "공유 중", color = if (ended) Nw.Green else Nw.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigStatus(title: String, sub: String, color: Color) {
    Text(title, color = color, fontSize = 56.sp, fontWeight = FontWeight.Bold)
    Text(sub, color = Nw.Sub, fontSize = 22.sp)
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(38.dp)).background(Nw.Card)
            .border(1.dp, Nw.Line, RoundedCornerShape(38.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Nw.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

/** 시연 버튼: 개발 빌드에서만 보인다 (제출용 release 빌드에서는 숨김) */
@Composable
private fun DevLink(label: String, onClick: () -> Unit) {
    if (!BuildConfig.DEV_TOOLS) return
    Text(label, color = Nw.Faint.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.clickable { onClick() }.padding(8.dp))
}

/** 도착으로 보는 반경 (기획서 4쪽: 50m 안 + P) */
const val ARRIVE_RADIUS_M = 50

private fun clock(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val ampm = if (c.get(Calendar.AM_PM) == Calendar.AM) "오전" else "오후"
    return "$ampm $h:%02d".format(c.get(Calendar.MINUTE))
}
