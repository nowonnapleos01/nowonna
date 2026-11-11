/*
 * C3 · 장소 변경 요청 카드: 받는 사람이 다른 장소를 제안하면 내비 위에 [이번만 변경] / [유지]로 묻는다.
 * 10초 안에 답하지 않으면 원래 장소를 유지한다 (CHANGE_REQUEST_TIMEOUT_MS).
 */
package ai.nowonna.car

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val Coral = Color(0xFFFF6B5A)
private val CoralDim = Color(0xFF3A1A16)

/** 받는 사람이 보낸 장소 변경 (보기 권한 → 수정 요청, 제안 권한 → 제안) */
data class ChangeRequest(
    val place: Place,
    val message: String,
    val at: Long = System.currentTimeMillis(),
)

/** 운전자가 답하지 않으면 원래 장소를 유지하는 시간 */
const val CHANGE_REQUEST_TIMEOUT_MS = 10_000

/**
 * C3 · 장소 변경 요청 (C2 위에 뜨는 카드)
 * 한 번 탭으로 승인. 10초 동안 아무것도 안 하면 원래 장소 유지 → 운전 중에도 신경 쓸 필요 없다.
 */
@Composable
fun C3ChangeRequest(
    request: ChangeRequest,
    contact: Contact,
    permission: SharePermission,
    current: Place,
    onAccept: () -> Unit,
    onKeep: () -> Unit,
) {
    val progress = remember(request.at) { Animatable(1f) }
    LaunchedEffect(request.at) {
        progress.animateTo(0f, tween(CHANGE_REQUEST_TIMEOUT_MS, easing = LinearEasing))
        onKeep() // 시간이 다 되면 유지
    }
    val secondsLeft = (progress.value * CHANGE_REQUEST_TIMEOUT_MS / 1000f).roundToInt().coerceAtLeast(0)
    val kind = if (permission == SharePermission.SUGGEST) "장소 변경 제안" else "장소 수정 요청"

    // 뒤를 어둡게
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 880.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(32.dp)).background(Nw.Panel)
                .border(1.dp, Nw.Line, RoundedCornerShape(32.dp)).padding(36.dp)
        ) {
            // 누가
            Row(
                Modifier.clip(RoundedCornerShape(18.dp)).background(CoralDim).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Coral))
                Spacer(Modifier.width(10.dp))
                Text("$kind · ${contact.name}", color = Coral, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))

            // 메시지 (없으면 장소 이름)
            Text(
                if (request.message.isNotBlank()) "\"${request.message}\"" else "${request.place.name}(으)로 와 주세요",
                color = Nw.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(18.dp))

            // 어디로 · 얼마나
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("→ ${request.place.name}", strong = true)
                Chip("지금 장소에서 ${distanceLabel(current, request.place)}")
            }

            Spacer(Modifier.height(26.dp))
            // 남은 시간
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${secondsLeft}초 뒤 원래 장소로 유지", color = Nw.Sub, fontSize = 19.sp)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Nw.Card)) {
                    Box(Modifier.fillMaxWidth(progress.value).height(6.dp).background(Nw.Sub))
                }
            }
            Spacer(Modifier.height(22.dp))

            // 한 번 탭
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.weight(1.4f).height(110.dp).clip(RoundedCornerShape(30.dp)).background(Nw.Amber).clickable { onAccept() },
                    contentAlignment = Alignment.Center
                ) { Text("이번만 변경", color = Nw.OnAmber, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(30.dp)).background(Nw.Card)
                        .border(1.dp, Nw.Line, RoundedCornerShape(30.dp)).clickable { onKeep() },
                    contentAlignment = Alignment.Center
                ) { Text("유지", color = Nw.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 자동 반영 권한일 때: 승인 없이 바로 바뀌었다는 알림 */
@Composable
fun AutoAppliedToast(contact: Contact, place: Place, onDone: () -> Unit) {
    LaunchedEffect(place) { kotlinx.coroutines.delay(5000); onDone() }
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Nw.CardHi).border(1.dp, Nw.Amber.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(horizontal = 26.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PBadge(size = 40, fontSize = 20, bg = Nw.Amber, fg = Nw.OnAmber)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("${contact.name}이(가) 만날 곳을 바꿨어요", color = Nw.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${place.name} · 내비에 바로 반영했어요", color = Nw.Sub, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Chip(text: String, strong: Boolean = false) {
    Text(
        text, color = if (strong) Nw.Text else Nw.Sub, fontSize = 22.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Nw.Card).padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

/** 두 장소 사이 직선거리 ("200m", "1.2km") */
fun distanceLabel(a: Place, b: Place): String {
    if (!a.hasLocation || !b.hasLocation) return "새 장소"
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
    val m = 2 * r * atan2(sqrt(h), sqrt(1 - h))
    return if (m < 1000) "${(m / 10).roundToInt() * 10}m" else "%.1fkm".format(m / 1000)
}
