package ai.nowonna.car

/** 픽업 장소 하나. Pleos 내비(현재 목적지·최근·즐겨찾기)에서 가져오거나, 받는 사람이 웹에서 정한다. */
data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val source: Source,
) {
    val hasLocation get() = !(lat == 0.0 && lng == 0.0)

    enum class Source { NAVI, RECENT, BOOKMARK, RECIPIENT }
}
