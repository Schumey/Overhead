package de.schumey.overhead

/**
 * Colours for the illustrated aircraft: tail, belly band and engines in a tint associated with the airline.
 * These are plain colour schemes with a generic emblem, not airline logos.
 */
class Livery(val primary: Int, val accent: Int, val emblem: Int)

object Liveries {
    const val EMBLEM_GLOBE = 0
    const val EMBLEM_STAR = 1
    const val EMBLEM_STRIPES = 2
    const val EMBLEM_DISC = 3

    private fun c(rgb: Long) = (0xFF000000L or rgb).toInt()
    private fun l(primary: Long, accent: Long, emblem: Int) = Livery(c(primary), c(accent), emblem)

    private val known = mapOf(
        "DLH" to l(0x0A1E5A, 0xF9B000, EMBLEM_DISC), "GEC" to l(0x0A1E5A, 0xE30613, EMBLEM_DISC),
        "EWG" to l(0xB5005A, 0xFFFFFF, EMBLEM_DISC), "CFG" to l(0xF2A900, 0x4A4A4A, EMBLEM_DISC),
        "BAW" to l(0x1B2D5E, 0xD01E2E, EMBLEM_STRIPES), "VIR" to l(0xD0102B, 0xFFFFFF, EMBLEM_STAR),
        "AFR" to l(0x0B2559, 0xE2001A, EMBLEM_STRIPES), "KLM" to l(0x00A1DE, 0x0B2559, EMBLEM_DISC),
        "RYR" to l(0x073590, 0xF1C933, EMBLEM_DISC), "EZY" to l(0xFF6600, 0xFFFFFF, EMBLEM_STRIPES),
        "WZZ" to l(0xC6007E, 0xFFFFFF, EMBLEM_DISC), "VLG" to l(0xFFCC00, 0x4A4A4A, EMBLEM_DISC),
        "UAE" to l(0xD71921, 0xB79D5B, EMBLEM_STAR), "QTR" to l(0x5C0632, 0xA8A39A, EMBLEM_GLOBE),
        "ETD" to l(0xA68A52, 0x2B2B2B, EMBLEM_DISC), "THY" to l(0xE81932, 0xFFFFFF, EMBLEM_GLOBE),
        "SIA" to l(0x1C2B5E, 0xF0A800, EMBLEM_STAR), "CPA" to l(0x00645A, 0xFFFFFF, EMBLEM_STRIPES),
        "EVA" to l(0x006B4F, 0xE8A317, EMBLEM_GLOBE), "CAL" to l(0xB4005A, 0x1B2D5E, EMBLEM_STAR),
        "ANA" to l(0x1B4F9C, 0xFFFFFF, EMBLEM_STRIPES), "JAL" to l(0xD10024, 0xFFFFFF, EMBLEM_DISC),
        "UAL" to l(0x0A3C8E, 0xFFFFFF, EMBLEM_GLOBE), "DAL" to l(0xC8102E, 0x00245D, EMBLEM_STAR),
        "AAL" to l(0x0078D2, 0xC8102E, EMBLEM_STRIPES), "ACA" to l(0xD0102B, 0x4A4A4A, EMBLEM_STAR),
        "SWR" to l(0xE30613, 0xFFFFFF, EMBLEM_STAR), "AUA" to l(0xD0102B, 0xFFFFFF, EMBLEM_STRIPES),
        "BEL" to l(0x1B2D5E, 0xD0102B, EMBLEM_DISC), "SAS" to l(0x0B1F5C, 0xFFFFFF, EMBLEM_STRIPES),
        "FIN" to l(0x0B1560, 0xFFFFFF, EMBLEM_DISC), "NOZ" to l(0xD81939, 0xFFFFFF, EMBLEM_DISC),
        "NSZ" to l(0xD81939, 0xFFFFFF, EMBLEM_DISC), "IBE" to l(0xD0102B, 0xF1C933, EMBLEM_STRIPES),
        "TAP" to l(0x00964A, 0xD0102B, EMBLEM_STAR), "LOT" to l(0x11397E, 0xFFFFFF, EMBLEM_GLOBE),
        "AZA" to l(0x0B2559, 0x2E8B57, EMBLEM_DISC), "ITY" to l(0x0B2559, 0x2E8B57, EMBLEM_DISC),
        "SXS" to l(0xF2B600, 0x0B3C8E, EMBLEM_DISC), "PGT" to l(0x14284B, 0xF2B600, EMBLEM_DISC),
        "THA" to l(0x4F2D7F, 0xC9A24D, EMBLEM_STRIPES), "AIC" to l(0xE4252B, 0xF1A022, EMBLEM_DISC),
        "CCA" to l(0xD0102B, 0xFFFFFF, EMBLEM_STAR), "CES" to l(0x1B4F9C, 0xD0102B, EMBLEM_DISC),
        "CSN" to l(0x1B6FB5, 0xD0102B, EMBLEM_STAR), "KAL" to l(0x00A0DF, 0xFFFFFF, EMBLEM_GLOBE),
        "QFA" to l(0xE0001B, 0xFFFFFF, EMBLEM_STAR), "ETH" to l(0x078930, 0xFCDD09, EMBLEM_STAR),
        "MSR" to l(0x0B2559, 0xFFFFFF, EMBLEM_STAR), "ELY" to l(0x0B2559, 0xFFFFFF, EMBLEM_STAR),
        "TOM" to l(0xD40E14, 0xFFFFFF, EMBLEM_STRIPES), "TUI" to l(0xD40E14, 0xFFFFFF, EMBLEM_STRIPES),
        "FDX" to l(0x4D148C, 0xFF6600, EMBLEM_STRIPES), "UPS" to l(0x351C15, 0xFFB500, EMBLEM_STRIPES),
        "DHK" to l(0xFFCC00, 0xD40511, EMBLEM_STRIPES), "BCS" to l(0xFFCC00, 0xD40511, EMBLEM_STRIPES),
        "CLX" to l(0x0B2559, 0xE2001A, EMBLEM_DISC), "BOX" to l(0xFFCC00, 0xD40511, EMBLEM_DISC),
        "MSK" to l(0x2B5AA7, 0xFFFFFF, EMBLEM_STAR), "TAY" to l(0xFF6600, 0x4D148C, EMBLEM_DISC),
    )

    /** Pleasant fallback tints for airlines not in the table: same airline, same colours, every time. */
    private val palette = listOf(
        0x2B5AA7L, 0x8A2432L, 0x2E6F57L, 0x6A4C93L, 0xC26A1AL, 0x1F7A8CL, 0x7A5C2EL, 0x3C6E9EL, 0xA23B72L, 0x4F772DL,
    )

    /** Neutral colours for general aviation and helicopters. */
    val neutral = l(0x46607F, 0xB9C1CC, EMBLEM_DISC)

    fun forFlight(f: Flight): Livery {
        val icao = f.route?.airlineIcao
            ?: f.callsign.takeIf { FlightApi.looksLikeAirlineCallsign(it) }?.take(3)
            ?: return neutral
        known[icao]?.let { return it }
        val h = icao.hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }
        return l(palette[h % palette.size], 0xFFFFFF, (h / palette.size) % 4)
    }
}
