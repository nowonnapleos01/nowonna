package ai.nowonna.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 차에 연결된 휴대폰의 연락처·최근 통화 (Android Automotive는 블루투스로 휴대폰 연락처·통화 기록을 동기화한다).
 * - contacts: C1 "누구에게 보낼까요?" 목록 (최근 통화 → 즐겨찾기 연락처 순)
 * - endedCall: 방금 끝난 통화 상대. C1이 C6 "픽업 장소를 정하게 할까요?"를 띄우는 신호
 *
 * 권한(READ_CALL_LOG, READ_CONTACTS)이 없거나 휴대폰이 연결되지 않았으면 비어 있다.
 */
object Phone {
    private const val MAX_CONTACTS = 4
    /** 통화가 끝나고 기록이 늦게 들어와도 이 시간 안이면 "방금 통화"로 본다 */
    private const val RECENT_CALL_MS = 2 * 60_000L

    val contacts = mutableStateListOf<Contact>()
    var endedCall by mutableStateOf<Contact?>(null)
    var permitted by mutableStateOf(false)
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var lastSeenCallAt = 0L

    private val tints = listOf(Color(0xFF3A4A66), Color(0xFF4B3A66), Color(0xFF2F5548), Color(0xFF5A4630), Color(0xFF3F4F5C))

    fun init(context: Context) {
        app = context.applicationContext
        refresh()
    }

    /** 권한을 받은 뒤 다시 부른다 */
    fun refresh() {
        if (!::app.isInitialized) return
        permitted = granted(Manifest.permission.READ_CALL_LOG) && granted(Manifest.permission.READ_CONTACTS)
        if (!permitted) return
        loadContacts()
        if (observer == null) {
            // 앱을 켜기 전 통화는 "방금 통화"로 보지 않는다
            lastSeenCallAt = latestCall()?.date ?: System.currentTimeMillis()
            observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = onCallLogChanged()
            }.also { runCatching { app.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, it) } }
        }
    }

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** 통화 기록에 새 줄이 생김 = 통화가 끝남 */
    private fun onCallLogChanged() {
        val c = latestCall() ?: return
        if (c.date <= lastSeenCallAt) return
        lastSeenCallAt = c.date
        loadContacts()
        val answered = c.type == CallLog.Calls.INCOMING_TYPE || c.type == CallLog.Calls.OUTGOING_TYPE
        val recent = System.currentTimeMillis() - (c.date + c.durationSec * 1000L) < RECENT_CALL_MS
        if (answered && c.durationSec > 0 && recent) {
            endedCall = contactOf(c.name, c.number)
        }
    }

    private data class Call(val number: String, val name: String?, val type: Int, val date: Long, val durationSec: Long)

    private fun latestCall(): Call? = runCatching {
        app.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { cur ->
            if (!cur.moveToFirst()) null
            else Call(cur.getString(0) ?: "", cur.getString(1), cur.getInt(2), cur.getLong(3), cur.getLong(4))
        }
    }.getOrNull()

    private fun loadContacts() {
        val list = LinkedHashMap<String, Contact>() // 전화번호 → 연락처 (중복 제거)
        runCatching {
            app.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { cur ->
                while (cur.moveToNext() && list.size < MAX_CONTACTS) {
                    val number = cur.getString(0)
                    if (!number.isNullOrBlank()) list.getOrPut(normalize(number)) { contactOf(cur.getString(1), number) }
                }
            }
        }
        if (list.size < MAX_CONTACTS) runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} DESC, ${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC"
            )?.use { cur ->
                while (cur.moveToNext() && list.size < MAX_CONTACTS) {
                    val number = cur.getString(1)
                    if (!number.isNullOrBlank()) list.getOrPut(normalize(number)) { contactOf(cur.getString(0), number) }
                }
            }
        }
        contacts.clear()
        contacts.addAll(list.values)
    }

    /** 통화 기록에 이름이 없으면 연락처에서 찾고, 그래도 없으면 번호를 이름으로 쓴다 */
    private fun contactOf(name: String?, number: String): Contact {
        val n = name?.takeIf { it.isNotBlank() } ?: lookupName(number) ?: number
        return Contact(n, number, tints[(normalize(number).hashCode() and 0x7fffffff) % tints.size])
    }

    private fun lookupName(number: String): String? = runCatching {
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun normalize(number: String) = number.filter { it.isDigit() }
}
