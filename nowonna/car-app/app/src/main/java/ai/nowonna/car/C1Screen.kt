/*
 * C1 · 픽업 시작 화면 ("누구를 데리러 갈까요?")
 * 만날 곳(내비 목적지·최근·즐겨찾기·받는 사람이 정하게) → 받는 사람(최근 통화·연락처) → 권한(보기/제안/자동 반영)을 고르고
 * [공유하고 출발]을 누르면 Pickup.start()로 픽업이 시작된다. 앱 전체에서 쓰는 색(Nw)과 공용 버튼도 여기 있다.
 */
package ai.nowonna.car

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

// ---------- 색 (기획서 PPT 다크 테마와 동일) ----------
object Nw {
    val Bg = Color(0xFF0A0D12)
    val Panel = Color(0xFF11161D)
    val Card = Color(0xFF1A212B)
    val CardHi = Color(0xFF222A36)
    val Line = Color(0xFF2A3340)
    val Amber = Color(0xFFFFB224)
    val OnAmber = Color(0xFF2A1A00)
    val AmberDim = Color(0xFF2A2010)
    val Green = Color(0xFF2FD39A)
    val Blue = Color(0xFF4DA3FF)
    val Text = Color(0xFFE6EAF0)
    val Sub = Color(0xFFA9B4C4)
    val Faint = Color(0xFF6B778A)
}

// ---------- 연락처 ----------
data class Contact(val name: String, val phone: String, val tint: Color)

/** 개발 빌드 + 휴대폰 미연결(에뮬레이터)일 때만 쓰는 시연용 연락처 */
private val demoContacts = listOf(
    Contact("김민수", "010-0000-0001", Color(0xFF3A4A66)),
    Contact("이지은", "010-0000-0002", Color(0xFF4B3A66)),
    Contact("박서연", "010-0000-0003", Color(0xFF2F5548)),
)

/** C1에 보여줄 연락처: 연결된 휴대폰의 최근 통화·즐겨찾기 → 없으면 (개발 빌드에서만) 시연용 */
private fun contactsToShow(): List<Contact> = when {
    Phone.contacts.isNotEmpty() -> Phone.contacts.toList()
    BuildConfig.DEV_TOOLS -> demoContacts
    else -> emptyList()
}

enum class SharePermission(val label: String, val desc: String) {
    VIEW("보기", "차 위치와 도착 시간만 봐요"),
    SUGGEST("제안", "장소를 바꾸자고 하면 내가 승인해요"),
    AUTO("자동 반영", "상대가 바꾸면 내비에 바로 반영돼요"),
}

/** "받는 사람이 정하게" 선택지 */
val recipientChooses = Place("받는 사람이 정하게", "링크에서 만날 곳을 찍어요", 0.0, 0.0, Place.Source.RECIPIENT)

/**
 * C1 · 픽업 시작 (정차 중에만)
 * 어디서 → 누구를 → 권한 → [공유하고 출발]
 * 장소는 Pleos 내비에서 그대로 가져온다: 지금 목적지 · 최근 · 즐겨찾기. 지도는 내비가 맡는다.
 */
@Composable
fun C1PickupStart(
    car: CarSignals,
    profile: DriverProfile,
    onShareAndGo: (Place, Contact, SharePermission) -> Unit,
    onOpenSdkCheck: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    LaunchedEffect(Unit) { car.refreshPlaces() }

    val navDest = car.naviDestination
    val places = buildList {
        navDest?.let { add(it) }
        // 좌표가 없는 장소(등록만 되고 위치가 비어 있는 즐겨찾기 등)는 뺀다
        addAll(car.bookmarks.filter { it.hasLocation })
        addAll(car.recents.filter { r -> r.hasLocation && r.name != navDest?.name })
        add(recipientChooses)
    }

    var place by remember { mutableStateOf<Place?>(null) }
    var contact by remember { mutableStateOf<Contact?>(null) }
    var permission by remember { mutableStateOf(SharePermission.VIEW) }
    // C6: 통화가 끝나면 뜨는 제안. 연결된 휴대폰의 통화 기록에 새 통화가 생기면 Phone.endedCall이 바뀐다.
    var callFrom by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(Phone.endedCall) {
        Phone.endedCall?.let { callFrom = it; Phone.endedCall = null }
    }
    val contacts = contactsToShow()

    // 처음엔 내비의 지금 목적지를 골라 둔다 (운전자가 이미 정한 곳)
    LaunchedEffect(navDest) { if (place == null || place?.source == Place.Source.NAVI) place = navDest ?: place }
    // 받는 사람이 정하면 '보기'는 의미가 없다
    LaunchedEffect(place) { if (place?.source == Place.Source.RECIPIENT && permission == SharePermission.VIEW) permission = SharePermission.AUTO }

    // 기어가 P로 확인될 때만 설정할 수 있다 (못 읽으면 잠금)
    val parked = car.parked

    Box(Modifier.fillMaxSize().background(Nw.Bg).padding(24.dp)) {
        Column(Modifier.fillMaxSize()) {
            // 머리말
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GearPill(parked, gearKnown = car.gear != null)
                    Spacer(Modifier.height(10.dp))
                    Text("누구를 데리러 갈까요?", color = Nw.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                if (BuildConfig.DEV_TOOLS) {
                    Text("통화 종료 시연", color = Nw.Faint.copy(alpha = 0.6f), fontSize = 13.sp,
                        modifier = Modifier.clickable { callFrom = contacts.firstOrNull() ?: demoContacts[0] }.padding(8.dp))
                    Text("SDK 확인", color = Nw.Faint.copy(alpha = 0.6f), fontSize = 13.sp,
                        modifier = Modifier.clickable { onOpenSdkCheck() }.padding(8.dp))
                }
                Spacer(Modifier.width(8.dp))
                VehicleChip(profile, onOpenProfile)
            }

            // 내비에서 방금 목적지를 정했다면
            AnimatedVisibility(
                visible = car.destinationChangedAt != null && navDest != null,
                enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()
            ) {
                NewDestinationBanner(navDest) { place = navDest; car.clearDestinationPrompt() }
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.weight(1f).alpha(if (parked) 1f else 0.35f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // 1. 어디서
                Column(Modifier.weight(1.1f).fillMaxHeight()) {
                    SectionLabel("1", "어디서 만날까요?")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(places) { p -> PlaceRow(p, selected = p == place, enabled = parked) { place = p } }
                        if (navDest == null) item { NaviHint() }
                    }
                }

                // 2·3. 누구를 · 권한 · 출발
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    SectionLabel("2", "누구에게 보낼까요?")
                    if (contacts.isEmpty()) ContactsHint()
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        contacts.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { c ->
                                    ContactChip(c, selected = contact == c, enabled = parked, modifier = Modifier.weight(1f)) {
                                        contact = if (contact == c) null else c
                                    }
                                }
                                if (pair.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                    SectionLabel("3", "어디까지 맡길까요?")
                    PermissionSelector(permission, enabled = parked, disableView = place?.source == Place.Source.RECIPIENT) { permission = it }

                    Spacer(Modifier.weight(1f))
                    val ready = parked && contact != null && place != null
                    val label = when {
                        car.gear == null -> "기어 상태를 확인하는 중이에요"
                        !parked -> "P에 놓으면 공유할 수 있어요"
                        place == null -> "만날 곳을 골라 주세요"
                        contact == null -> "보낼 사람을 골라 주세요"
                        place!!.source == Place.Source.RECIPIENT -> "${contact!!.name}에게 장소 정하기 보내기"
                        else -> "공유하고 출발"
                    }
                    PrimaryButton(label, enabled = ready) { onShareAndGo(place!!, contact!!, permission) }
                }
            }
        }

        // 통화 후 제안은 정차 중일 때만 (주행 중이면 정차할 때까지 기다렸다가 뜬다)
        callFrom?.takeIf { parked }?.let { c ->
            C6AfterCall(
                contact = c,
                // 수락: 장소는 상대가 링크에서 정하고, 정한 곳이 내비에 바로 들어간다
                onAccept = { callFrom = null; onShareAndGo(recipientChooses, c, SharePermission.AUTO) },
                onClose = { callFrom = null },
            )
        }
    }
}

// ---------- 구성 요소 ----------

@Composable
fun GearPill(parked: Boolean, gearKnown: Boolean = true) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (parked) Nw.Card else Nw.AmberDim)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PBadge(size = 24, fontSize = 13, bg = if (parked) Nw.CardHi else Nw.Amber, fg = if (parked) Nw.Text else Nw.OnAmber)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                parked -> "정차 중 · 설정할 수 있어요"
                !gearKnown -> "기어 확인 중 · P가 확인되면 설정할 수 있어요"
                else -> "주행 중 · 정차하면 설정할 수 있어요"
            },
            color = if (parked) Nw.Sub else Nw.Amber, fontSize = 15.sp
        )
    }
}

/** 머리말 오른쪽: 등록한 차 (받는 사람 화면의 "차 찾기"에 쓰임) */
@Composable
private fun VehicleChip(profile: DriverProfile, onClick: () -> Unit) {
    val empty = !profile.hasVehicle
    Text(
        if (empty) "내 차 등록 ›" else "내 차 · ${profile.vehicleLabel} ›",
        color = if (empty) Nw.Amber else Nw.Sub, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (empty) Nw.AmberDim else Nw.Card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun ContactsHint() {
    Text("차에 휴대폰을 연결하면 최근 통화와 즐겨찾기 연락처가 여기에 떠요",
        color = Nw.Faint, fontSize = 16.sp, modifier = Modifier.padding(8.dp))
}

@Composable
private fun SectionLabel(num: String, title: String) {
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Nw.CardHi), contentAlignment = Alignment.Center) {
            Text(num, color = Nw.Sub, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Nw.Sub, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlaceRow(p: Place, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Nw.AmberDim else Nw.Card, label = "placeBg")
    Row(
        Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .border(2.dp, if (selected) Nw.Amber else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, iconBg, iconFg) = when (p.source) {
            Place.Source.NAVI -> Triple("P", Nw.Amber, Nw.OnAmber)
            Place.Source.BOOKMARK -> Triple(if (p.name == "집") "⌂" else "★", Nw.CardHi, Nw.Text)
            Place.Source.RECENT -> Triple("↺", Nw.CardHi, Nw.Sub)
            Place.Source.RECIPIENT -> Triple("✎", Color(0xFF12302A), Nw.Green)
        }
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            Text(icon, color = iconFg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = Nw.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                tagOf(p)?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = if (p.source == Place.Source.NAVI) Nw.Amber else Nw.Faint, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Nw.Bg).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            val sub = if (p.source == Place.Source.RECIPIENT) p.address else p.address.ifBlank { "%.4f, %.4f".format(p.lat, p.lng) }
            Text(sub, color = Nw.Sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) CheckDot()
    }
}

private fun tagOf(p: Place) = when (p.source) {
    Place.Source.NAVI -> "지금 내비 목적지"
    Place.Source.RECENT -> "최근"
    Place.Source.BOOKMARK -> "즐겨찾기"
    Place.Source.RECIPIENT -> null
}

@Composable
private fun NaviHint() {
    Text("내비에서 목적지를 정하면 여기에 바로 떠요", color = Nw.Faint, fontSize = 16.sp, modifier = Modifier.padding(8.dp))
}

@Composable
private fun NewDestinationBanner(dest: Place?, onUse: () -> Unit) {
    Row(
        Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nw.AmberDim)
            .border(1.dp, Nw.Amber.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("방금 내비에 '${dest?.name}'을(를) 정했어요. 이곳으로 픽업할까요?", color = Nw.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text("이곳으로", color = Nw.OnAmber, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Nw.Amber).clickable { onUse() }.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun ContactChip(c: Contact, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Nw.AmberDim else Nw.Card, label = "chipBg")
    Row(
        modifier.height(80.dp).clip(RoundedCornerShape(20.dp)).background(bg)
            .border(2.dp, if (selected) Nw.Amber else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c, 46)
        Spacer(Modifier.width(12.dp))
        Text(c.name, color = Nw.Text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        if (selected) CheckDot()
    }
}

@Composable
fun Avatar(c: Contact, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) {
        Text(initialOf(c.name), color = Nw.Text, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckDot() {
    Box(Modifier.size(32.dp).clip(CircleShape).background(Nw.Amber), contentAlignment = Alignment.Center) {
        Text("✓", color = Nw.OnAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PermissionSelector(value: SharePermission, enabled: Boolean, disableView: Boolean, onChange: (SharePermission) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Nw.Panel).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SharePermission.entries.forEach { p ->
                val on = p == value
                val usable = enabled && !(disableView && p == SharePermission.VIEW)
                Box(
                    Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (on) Nw.CardHi else Color.Transparent)
                        .border(if (on) 1.dp else 0.dp, if (on) Nw.Amber else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(enabled = usable) { onChange(p) }
                        .alpha(if (usable || on) 1f else 0.35f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(p.label, color = if (on) Nw.Text else Nw.Sub, fontSize = 20.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Text(value.desc, color = Nw.Sub, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp))
    }
}

@Composable
fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) Nw.Amber else Nw.Card, label = "btnBg")
    Box(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(42.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Nw.OnAmber else Nw.Faint, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PBadge(size: Int, fontSize: Int, bg: Color, fg: Color) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(bg), contentAlignment = Alignment.Center) {
        Text("P", color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

/** 남은 시간 → "오후 8:31" */
fun arrivalClock(remainSec: Int): String {
    val c = Calendar.getInstance().apply { add(Calendar.SECOND, remainSec) }
    val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val ampm = if (c.get(Calendar.AM_PM) == Calendar.AM) "오전" else "오후"
    return "$ampm $h:%02d".format(c.get(Calendar.MINUTE))
}

/** 프로필 글자: 세 글자 이름은 가운데(김민수 → 민), 그 외는 첫 글자 */
fun initialOf(name: String): String = if (name.length == 3) name[1].toString() else name.take(1)
