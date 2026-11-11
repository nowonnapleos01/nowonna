/*
 * C4 · 도착 (정차하면 받는 사람에게 자동으로 알림)
 * C5 · 탑승 (공유 종료 → 받는 사람이 남긴 목적지로 안내할지 묻고 바로 내비로)
 * C6 · 통화 후 픽업 제안 ("방금 통화한 분께 픽업 장소를 정하게 할까요?")
 */
package ai.nowonna.car

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GreenDim = Color(0xFF12302A)

// =====================================================================
// C4 · 도착 — 정차하면 받는 사람에게 자동으로 알리고, 운전자는 기다리기만
// =====================================================================
@Composable
fun C4Arrived(car: CarSignals, session: PickupSession, profile: DriverProfile, onEnd: () -> Unit, devLinks: @Composable () -> Unit) {
    val name = session.contact.name
    val hazard = car.hazardOn
    val carLabel = profile.vehicleLabel
    Row(Modifier.fillMaxSize().background(Nw.Bg).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1.2f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(32.dp)
        ) {
            StatusTag("✓  도착 알림 보냄", Nw.Green, GreenDim)
            Spacer(Modifier.height(18.dp))
            Text("${name}님을 기다리는 중이에요", color = Nw.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("타고 문을 닫으면 탑승으로 알고 공유를 끝내요", color = Nw.Sub, fontSize = 20.sp)
            Spacer(Modifier.height(26.dp))

            // 받는 사람 화면에 보이는 것: 정차 위치 + (비상등을 켜면) "비상등 켠 차" + 차종·색상
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nw.AmberDim)
                    .border(1.dp, Nw.Amber.copy(alpha = 0.5f), RoundedCornerShape(22.dp)).padding(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PBadge(size = 60, fontSize = 30, bg = Nw.Amber, fg = Nw.OnAmber)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        when {
                            hazard && carLabel.isNotBlank() -> "비상등 켠 $carLabel"
                            hazard -> "비상등 켠 차로 알리는 중"
                            carLabel.isNotBlank() -> "$carLabel · 정차 위치를 보여주는 중"
                            else -> "정차 위치를 보여주는 중"
                        },
                        color = Nw.Amber, fontSize = 23.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (hazard) "${name}님 화면에 '도착, 비상등 켠 차량이에요'가 떠 있어요"
                        else "${name}님 화면에 차 위치와 '도착했어요'가 떠 있어요" +
                            if (car.hazardSupported) " · 비상등을 켜면 함께 알려요" else "",
                        color = Nw.Sub, fontSize = 18.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            val context = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f)) {
                    BigButton("${name}님께 전화", primary = false) {
                        // 차량 전화 앱으로 연결 (번호는 서버 연동 후 실제 연락처)
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${session.contact.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { car.note("전화 앱 열기 실패: ${it.message}") }
                    }
                }
                Box(Modifier.weight(0.6f)) { BigButton("끝내기", primary = false, onClick = onEnd) }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                    .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(32.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text("도착", color = Nw.Green, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                Text("${session.place.name} · P", color = Nw.Sub, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            devLinks()
        }
    }
}

// =====================================================================
// C5 · 탑승 — 공유를 끝내고, 받는 사람이 남긴 다음 목적지로 바로 안내
// =====================================================================
@Composable
fun C5Boarded(
    session: PickupSession,
    next: Place?,
    /** 지금 차 위치에서 다음 목적지까지 거리 ("3.2km") */
    distance: String?,
    onGoNext: (Place) -> Unit,
    onDone: () -> Unit,
) {
    val name = session.contact.name

    Row(Modifier.fillMaxSize().background(Nw.Bg).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(
            Modifier.weight(1.2f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(32.dp)
        ) {
            StatusTag("✓  탑승 완료", Nw.Green, GreenDim)
            Spacer(Modifier.height(18.dp))
            Text("${name}님이 탔어요.\n위치 공유를 끝냈어요", color = Nw.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
            Spacer(Modifier.height(24.dp))

            if (next != null) {
                // 받는 사람이 링크에서 미리 남긴 목적지 → 운전자에게 한 번만 물어보고 바로 내비로
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nw.Card).padding(24.dp)) {
                    Text("${name}님이 요청한 목적지로\n안내를 시작할까요?", color = Nw.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(listOfNotNull(next.name, distance).joinToString(" · "), color = Nw.Sub, fontSize = 18.sp)
                }
            } else {
                Text("링크는 만료됐고 위치 기록도 지웠어요", color = Nw.Sub, fontSize = 20.sp)
            }

            Spacer(Modifier.weight(1f))
            if (next != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1.4f)) {
                        Box(
                            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(30.dp)).background(Nw.Amber).clickable { onGoNext(next) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("안내 시작", color = Nw.OnAmber, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.weight(1f)) { BigButton("취소", primary = false, onClick = onDone) }
                }
            } else {
                BigButton("완료", primary = true, onClick = onDone)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Nw.Panel)
                .border(1.dp, Nw.Line, RoundedCornerShape(28.dp)).padding(32.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text("탑승", color = Nw.Green, fontSize = 88.sp, fontWeight = FontWeight.Bold)
            Text("함께 출발해요", color = Nw.Sub, fontSize = 24.sp)
        }
    }
}

// =====================================================================
// C6 · 통화 후 픽업 제안 — 전화로 부탁받았을 때, 장소는 상대가 링크에서 정하게
// =====================================================================
@Composable
fun C6AfterCall(contact: Contact, onAccept: () -> Unit, onClose: () -> Unit) {
    val name = contact.name
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(32.dp)).background(Nw.Panel)
                .border(1.dp, Nw.Line, RoundedCornerShape(32.dp)).padding(40.dp)
        ) {
            StatusTag("방금 ${name}님과 통화했어요", Nw.Amber, Nw.AmberDim)
            Spacer(Modifier.height(18.dp))
            Text("${name}님에게 픽업 위치를\n정하게 할까요?", color = Nw.Text, fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 54.sp)
            Spacer(Modifier.height(12.dp))
            Text("수락하면 ${name}님에게 링크가 가고, 찍은 곳으로 바로 안내해요.", color = Nw.Sub, fontSize = 21.sp)
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1.6f)) { BigButton("수락", primary = true, height = 110, onClick = onAccept) }
                Box(Modifier.weight(1f)) { BigButton("닫기", primary = false, height = 110, onClick = onClose) }
            }
        }
    }
}

// ---------- 공통 ----------

@Composable
private fun StatusTag(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(bg).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun BigButton(label: String, primary: Boolean, height: Int = 96, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(30.dp))
            .background(if (primary) Nw.Amber else Nw.Card)
            .border(1.dp, if (primary) Color.Transparent else Nw.Line, RoundedCornerShape(30.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) Nw.OnAmber else Nw.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}
