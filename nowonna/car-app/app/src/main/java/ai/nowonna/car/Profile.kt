package ai.nowonna.car

import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 운전자·차 정보. 한 번 등록해 두면 픽업마다 서버로 함께 보낸다.
 * 받는 사람 웹의 "차 찾기"(차종·색상)와 나갈 타이밍 알림 시점에 쓰인다.
 */
data class DriverProfile(
    val driverName: String = "",
    val carModel: String = "",
    val carColor: String = "",
    val notifyBeforeSec: Int = 180,
    /** 번호판: 도착했을 때 받는 사람이 차를 확인하는 데 쓴다 (시연용 임의 번호) */
    val carPlate: String = DEMO_PLATE,
) {
    val hasVehicle get() = carModel.isNotBlank() || carColor.isNotBlank()
    /** "흰색 아반떼" */
    val vehicleLabel get() = listOf(carColor, carModel).filter { it.isNotBlank() }.joinToString(" ")
}

object ProfileStore {
    private const val FILE = "driver_profile"

    fun load(context: Context): DriverProfile {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return DriverProfile(
            driverName = p.getString("driverName", "") ?: "",
            carModel = p.getString("carModel", "") ?: "",
            carColor = p.getString("carColor", "") ?: "",
            notifyBeforeSec = p.getInt("notifyBeforeSec", 180),
            carPlate = p.getString("carPlate", null)?.takeIf { it.isNotBlank() } ?: DEMO_PLATE,
        )
    }

    fun save(context: Context, d: DriverProfile) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("driverName", d.driverName.trim())
            .putString("carModel", d.carModel.trim())
            .putString("carColor", d.carColor.trim())
            .putInt("notifyBeforeSec", d.notifyBeforeSec)
            .putString("carPlate", d.carPlate.trim())
            .apply()
    }
}

/** 나갈 타이밍 알림 선택지 (초) */
private val notifyOptions = listOf(60, 180, 300, 600)

/**
 * 내 차 정보 · 정차(P) 중에만 고칠 수 있다. 글자 입력이 있어서 주행 중에는 잠근다.
 */
@Composable
fun ProfileScreen(initial: DriverProfile, parked: Boolean, onSave: (DriverProfile) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf(initial.driverName) }
    var model by remember { mutableStateOf(initial.carModel) }
    var color by remember { mutableStateOf(initial.carColor) }
    var notify by remember { mutableStateOf(initial.notifyBeforeSec) }

    Box(Modifier.fillMaxSize().background(Nw.Bg).padding(24.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GearPill(parked)
                    Spacer(Modifier.height(10.dp))
                    Text("내 차 정보", color = Nw.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("받는 사람 화면에 차종·색상이 보여서 차를 쉽게 찾아요", color = Nw.Sub, fontSize = 17.sp)
                }
            }
            Spacer(Modifier.height(24.dp))

            Row(Modifier.weight(1f).alpha(if (parked) 1f else 0.35f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Field("내 이름 (받는 사람에게 보여요)", name, "예: 민준", parked) { name = it }
                    Field("차종", model, "예: 아반떼", parked) { model = it }
                    Field("색상", color, "예: 흰색", parked) { color = it }
                }
                Column(Modifier.weight(1f)) {
                    Text("나갈 타이밍 알림", color = Nw.Sub, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        notifyOptions.forEach { sec ->
                            val on = sec == notify
                            Box(
                                Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (on) Nw.AmberDim else Nw.Card)
                                    .border(2.dp, if (on) Nw.Amber else Color.Transparent, RoundedCornerShape(16.dp))
                                    .clickable(enabled = parked) { notify = sec },
                                contentAlignment = Alignment.Center
                            ) { Text("${sec / 60}분 전", color = if (on) Nw.Text else Nw.Sub, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Text("도착 ${notify / 60}분 전에 받는 사람에게 \"지금 나오세요\"를 보내요", color = Nw.Sub, fontSize = 16.sp,
                        modifier = Modifier.padding(start = 6.dp, top = 10.dp))
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(if (parked) "저장" else "P에 놓으면 고칠 수 있어요", enabled = parked) {
                                onSave(initial.copy(driverName = name, carModel = model, carColor = color, notifyBeforeSec = notify))
                            }
                        }
                        Box(Modifier.width(180.dp)) { BackButton(onBack) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Nw.Sub, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value, onValueChange = { onChange(it.take(20)) }, enabled = enabled, singleLine = true,
            placeholder = { Text(hint, color = Nw.Faint, fontSize = 20.sp) },
            textStyle = TextStyle(color = Nw.Text, fontSize = 22.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Nw.Amber, unfocusedBorderColor = Nw.Line, cursorColor = Nw.Amber,
                focusedContainerColor = Nw.Card, unfocusedContainerColor = Nw.Card, disabledContainerColor = Nw.Card,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(42.dp)).background(Nw.Card)
            .border(1.dp, Nw.Line, RoundedCornerShape(42.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("닫기", color = Nw.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
}

/** 시연용 임의 번호판 (실제 차 번호가 아님) */
const val DEMO_PLATE = "12가 3456"
