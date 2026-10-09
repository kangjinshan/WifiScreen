package com.kanayama.wifiscreen

/** H.264 syntax values, not Android MediaFormat enum values. Null means not signalled. */
data class AvcColor(val primaries: Int? = null, val transfer: Int? = null,
    val matrix: Int? = null, val fullRange: Boolean? = null)

data class AvcSps(val id: Int, val profile: Int, val level: Int, val chromaFormat: Int,
    val bitDepthLuma: Int, val bitDepthChroma: Int, val separateColorPlane: Boolean,
    val frameNumBits: Int, val pocType: Int, val pocBits: Int, val deltaPocAlwaysZero: Boolean,
    val frameOnly: Boolean, val macroblocks: Int, val color: AvcColor)
data class AvcPps(val id: Int, val spsId: Int, val bottomFieldPoc: Boolean, val sliceGroups: Int)
data class AvcSlice(val firstMb: Int, val idr: Boolean, val frameNum: Int, val ppsId: Int,
    val idrPicId: Int?, val poc: Int, val deltaBottom: Int, val deltaPoc0: Int, val deltaPoc1: Int)

/** Bounded, best-effort metadata parsing. Unsupported syntax never changes the decoder input. */
object AvcParameters {
    /** A repair starts with one complete access unit, not a sequence of separately flagged slices. */
    fun joinIdr(units: List<ByteArray>): ByteArray {
        require(units.isNotEmpty())
        val size = units.sumOf { it.size.toLong() }
        require(size <= LegacyAvc.MAX_PAYLOAD)
        val result = ByteArray(size.toInt())
        var offset = 0
        for (unit in units) {
            require(unit.size > 4 && unit.take(4) == listOf<Byte>(0, 0, 0, 1) && unit[4].toInt() and 31 == 5)
            unit.copyInto(result, offset); offset += unit.size
        }
        return result
    }
    private class Bits(unit: ByteArray) {
        private val bytes: ByteArray
        private var position = 0
        init {
            require(unit.size in 6..LegacyAvc.MAX_PAYLOAD && unit.take(4) == listOf<Byte>(0, 0, 0, 1))
            val result = ByteArray(unit.size - 5)
            var count = 0
            var zeros = 0
            for (i in 5 until unit.size) {
                val value = unit[i].toInt() and 255
                if (zeros >= 2 && value == 3) {
                    require(i + 1 < unit.size && unit[i + 1].toInt() and 255 <= 3)
                    zeros = 0
                    continue
                }
                result[count++] = unit[i]
                zeros = if (value == 0) zeros + 1 else 0
            }
            bytes = result.copyOf(count)
        }
        fun read(count: Int): Int {
            require(count in 0..31 && position.toLong() + count <= bytes.size.toLong() * 8)
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        fun flag() = read(1) == 1
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; require(zeros < 31) }
            return ((1L shl zeros) - 1 + read(zeros)).also { require(it <= Int.MAX_VALUE) }.toInt()
        }
        fun se(): Int { val code = ue(); return if (code and 1 == 0) -(code / 2) else code / 2 + 1 }
    }

    fun sps(unit: ByteArray): AvcSps? = runCatching {
        require(unit[4].toInt() and 31 == 7)
        val b = Bits(unit)
        val profile = b.read(8); b.read(8); val level = b.read(8)
        val id = b.ue().also { require(it <= 31) }
        var chroma = 1; var lumaDepth = 8; var chromaDepth = 8; var separate = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = b.ue().also { require(it in 0..3) }
            if (chroma == 3) separate = b.flag()
            lumaDepth = 8 + b.ue().also { require(it <= 6) }
            chromaDepth = 8 + b.ue().also { require(it <= 6) }
            b.flag()
            if (b.flag()) repeat(if (chroma == 3) 12 else 8) { index ->
                if (b.flag()) {
                    var last = 8; var next = 8
                    repeat(if (index < 6) 16 else 64) {
                        if (next != 0) next = ((last.toLong() + b.se()) and 255).toInt()
                        if (next != 0) last = next
                    }
                }
            }
        } else require(profile in setOf(66, 77, 88))
        val frameBits = 4 + b.ue().also { require(it <= 12) }
        val pocType = b.ue().also { require(it <= 2) }
        var pocBits = 0; var deltaZero = false
        if (pocType == 0) pocBits = 4 + b.ue().also { require(it <= 12) }
        if (pocType == 1) {
            deltaZero = b.flag(); b.se(); b.se()
            repeat(b.ue().also { require(it <= 255) }) { b.se() }
        }
        b.ue(); b.flag()
        val widthMbs = 1 + b.ue().also { require(it < 256) }
        val heightUnits = 1 + b.ue().also { require(it < 256) }
        val frameOnly = b.flag()
        if (!frameOnly) b.flag()
        b.flag()
        if (b.flag()) repeat(4) { b.ue() }
        var color = AvcColor()
        if (b.flag()) {
            if (b.flag() && b.read(8) == 255) { b.read(16); b.read(16) }
            if (b.flag()) b.flag()
            if (b.flag()) {
                b.read(3)
                val full = b.flag()
                color = if (b.flag()) AvcColor(b.read(8), b.read(8), b.read(8), full)
                    else AvcColor(fullRange = full)
            }
        }
        AvcSps(id, profile, level, chroma, lumaDepth, chromaDepth, separate, frameBits,
            pocType, pocBits, deltaZero, frameOnly, widthMbs * heightUnits * if (frameOnly) 1 else 2, color)
    }.getOrNull()

    fun pps(unit: ByteArray): AvcPps? = runCatching {
        require(unit[4].toInt() and 31 == 8)
        val b = Bits(unit)
        val id = b.ue().also { require(it <= 255) }
        val sps = b.ue().also { require(it <= 31) }
        b.flag()
        AvcPps(id, sps, b.flag(), 1 + b.ue().also { require(it <= 7) })
    }.getOrNull()

    fun slice(unit: ByteArray, sps: AvcSps?, pps: AvcPps?): AvcSlice? = runCatching {
        require(sps != null && pps != null && pps.spsId == sps.id)
        // Recovery only supports ordinary progressive AVC without FMO/ASO or separate planes.
        require(sps.frameOnly && !sps.separateColorPlane && pps.sliceGroups == 1)
        val type = unit[4].toInt() and 31
        require(type == 1 || type == 5)
        require(unit[4].toInt() and 0x80 == 0)
        require(type != 5 || unit[4].toInt() and 0x60 != 0)
        val b = Bits(unit)
        val first = b.ue().also { require(it < sps.macroblocks) }
        b.ue().also { require(it <= 9 && (type != 5 || it % 5 == 2)) }
        val ppsId = b.ue().also { require(it == pps.id) }
        val frameNum = b.read(sps.frameNumBits)
        val idrId = if (type == 5) b.ue().also { require(it <= 65535 && frameNum == 0) } else null
        var poc = 0; var bottom = 0; var delta0 = 0; var delta1 = 0
        if (sps.pocType == 0) {
            poc = b.read(sps.pocBits)
            if (pps.bottomFieldPoc) bottom = b.se()
        } else if (sps.pocType == 1 && !sps.deltaPocAlwaysZero) {
            delta0 = b.se()
            if (pps.bottomFieldPoc) delta1 = b.se()
        }
        AvcSlice(first, type == 5, frameNum, ppsId, idrId, poc, bottom, delta0, delta1)
    }.getOrNull()
}
