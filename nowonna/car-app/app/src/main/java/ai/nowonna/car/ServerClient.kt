package ai.nowonna.car

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 나온나 서버와 통신 (형식은 nowonna/server-lite/API.md)
 * 서버 주소는 빌드 설정(gradle.properties 의 nowonna.serverUrl)에서 온다.
 * 기본값은 에뮬레이터에서 이 맥의 server-lite (10.0.2.2:8787).
 */
object ServerClient {
    val baseUrl: String = BuildConfig.SERVER_URL

    /** 픽업을 시작할 때 서버가 주는 차량 전용 키. 위치·응답·종료 요청에 붙여서 보낸다. */
    private var driverToken: String? = null

    data class Created(val id: String, val link: String)
    data class LocationReply(
        val pendingChange: ChangeRequest?,
        val changeApplied: Boolean,
        val viewedAt: Long?,
        /** 받는 사람이 미리 남긴 다음 목적지 (탑승 후 C5에서 바로 안내) */
        val nextPlace: Place?,
    )

    /** 서버 응답. code = HTTP 상태 (연결 실패면 0) */
    private class Reply(val code: Int, val body: JSONObject?)

    sealed interface SyncResult {
        data class Ok(val reply: LocationReply) : SyncResult
        /** 링크가 만료됐거나 이미 끝난 픽업 (410 / 404) */
        data object Expired : SyncResult
        data object Failed : SyncResult
    }

    suspend fun createSession(
        contact: Contact,
        permission: SharePermission,
        place: Place?,
        profile: DriverProfile,
    ): Created? {
        driverToken = null
        val body = JSONObject()
            .put("driverLabel", profile.driverName.ifBlank { "운전자" })
            .put("contactName", contact.name)
            .put("contactPhone", contact.phone)
            .put("permission", permission.name)
            .put("place", place?.takeIf { it.hasLocation }?.let { placeJson(it) } ?: JSONObject.NULL)
            .put("notifyBeforeSec", profile.notifyBeforeSec)
            .put("vehicle", if (profile.hasVehicle) JSONObject().put("model", profile.carModel).put("color", profile.carColor).put("plate", profile.carPlate) else JSONObject.NULL)
        val r = call("POST", "/api/sessions", body, auth = false).body ?: return null
        driverToken = r.optString("driverToken").ifBlank { null }
        return Created(r.getString("id"), r.getString("link"))
    }

    suspend fun sendLocation(
        id: String, lat: Double, lng: Double, heading: Double,
        remainSec: Int?, remainMeters: Int?, state: String, hazard: Boolean,
    ): SyncResult {
        val r = call("POST", "/api/sessions/$id/location",
            JSONObject().put("lat", lat).put("lng", lng).put("heading", heading)
                .put("remainSec", remainSec ?: JSONObject.NULL).put("remainMeters", remainMeters ?: JSONObject.NULL)
                .put("state", state).put("hazard", hazard)
        )
        if (r.code == 410 || r.code == 404) return SyncResult.Expired
        val b = r.body ?: return SyncResult.Failed
        val pc = b.optJSONObject("pendingChange")
        return SyncResult.Ok(
            LocationReply(
                pendingChange = pc?.let {
                    ChangeRequest(
                        place = placeOf(it.getJSONObject("place"), "새 장소"),
                        message = it.optString("message", ""),
                        at = it.optLong("at", System.currentTimeMillis()),
                    )
                },
                changeApplied = pc?.optBoolean("applied", false) ?: false,
                viewedAt = if (b.isNull("viewedAt")) null else b.optLong("viewedAt"),
                nextPlace = b.optJSONObject("nextPlace")?.takeIf { it.has("lat") && it.has("lng") }?.let { placeOf(it, "다음 목적지") },
            )
        )
    }

    /** 차 → 만날 곳 도로 경로 [위도, 경도] 목록 (모의 주행용). 못 받으면 빈 목록 */
    suspend fun route(id: String): List<DoubleArray> {
        val b = call("GET", "/api/sessions/$id/route", null, auth = false).body ?: return emptyList()
        val arr = b.optJSONArray("path") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } }
    }

    suspend fun resolve(id: String, accepted: Boolean) { call("POST", "/api/sessions/$id/resolve", JSONObject().put("accepted", accepted)) }

    suspend fun end(id: String) {
        call("POST", "/api/sessions/$id/end", JSONObject())
        driverToken = null
    }

    private fun placeJson(p: Place) = JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng)

    private fun placeOf(p: JSONObject, fallbackName: String) =
        Place(p.optString("name").ifBlank { fallbackName }, p.optString("address", ""), p.getDouble("lat"), p.getDouble("lng"), Place.Source.RECIPIENT)

    private suspend fun call(method: String, path: String, body: JSONObject?, auth: Boolean = true): Reply = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(baseUrl + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 3000; c.readTimeout = 5000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (auth) driverToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            Reply(code, if (code in 200..299 && text != null) JSONObject(text) else null)
        }.getOrElse { Reply(0, null) }
    }
}
