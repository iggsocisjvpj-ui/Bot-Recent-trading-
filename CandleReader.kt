package com.candle.assistant

import kotlin.math.max

/** OHLC نسبي مستخرج بصرياً؛ لا يمثل سعراً حقيقياً ما لم تتم معايرته بمحور الأسعار. */
data class Candle(val open: Double, val close: Double, val high: Double, val low: Double)
data class CandleReadResult(
    val candles: List<Candle>,
    val detectedColumns: Int,
    val acceptedCandles: Int,
    val quality: Double,
    val message: String
)

/** قارئ بصري محافظ: يجزّئ الأعمدة الملونة ثم يجمع الأعمدة المتجاورة التي تمثل جسم/فتيل الشمعة. */
object CandleReader {
    private data class Column(val kind: Int, val ys: IntArray)

    private fun classify(c: Int): Int {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        // عتبات اللون مع شرط تشبع/فرق لتقليل التقاط الخلفية والخطوط.
        return when {
            g > 125 && g - r > 42 && g - b > 18 && r < 175 -> 1
            r > 145 && r - g > 48 && r - b > 28 && g < 175 -> 2
            else -> 0
        }
    }

    fun read(px: IntArray, w: Int, h: Int): CandleReadResult {
        if (w <= 0 || h <= 0 || px.size < w * h) {
            return CandleReadResult(emptyList(), 0, 0, 0.0, "حجم صورة غير صالح")
        }
        val cols = ArrayList<Column>(w)
        var active = 0
        for (x in 0 until w) {
            val green = ArrayList<Int>()
            val red = ArrayList<Int>()
            for (y in 0 until h) {
                when (classify(px[y * w + x])) {
                    1 -> green.add(y)
                    2 -> red.add(y)
                }
            }
            val chosen = if (green.size >= red.size) green else red
            val kind = if (green.size >= red.size) 1 else 2
            if (chosen.size >= 2) {
                cols.add(Column(kind, chosen.toIntArray()))
                active++
            } else cols.add(Column(0, IntArray(0)))
        }

        val maxGap = max(1, w / 500)
        val out = ArrayList<Candle>()
        var x = 0
        var acceptedPixels = 0
        while (x < w) {
            if (cols[x].kind == 0) { x++; continue }
            val start = x
            val kind = cols[x].kind
            var end = x
            var gap = 0
            x++
            while (x < w) {
                if (cols[x].kind == kind) { end = x; gap = 0; x++ }
                else if (cols[x].kind == 0 && gap < maxGap) { gap++; x++ }
                else break
            }
            val width = end - start + 1
            if (width > max(18, w / 12)) continue // غالباً مساحة ملونة/مؤشر وليست شمعة.
            val counts = IntArray(h)
            var pixels = 0
            for (cx in start..end) {
                if (cols[cx].kind != kind) continue
                for (yy in cols[cx].ys) { counts[yy]++; pixels++ }
            }
            if (pixels < 4) continue
            var hi = 0
            while (hi < h && counts[hi] == 0) hi++
            var lo = h - 1
            while (lo >= 0 && counts[lo] == 0) lo--
            if (hi >= lo) continue

            // جسم الشمعة عادة أعرض/أكثر كثافة من الفتيل. نبحث عن أقوى مقطع كثيف.
            val peak = counts.maxOrNull() ?: 0
            val threshold = max(1, (peak * 0.55).toInt())
            var bestA = -1; var bestB = -1; var runA = -1
            for (yy in hi..lo) {
                if (counts[yy] >= threshold) {
                    if (runA < 0) runA = yy
                    if (bestA < 0 || yy - runA > bestB - bestA) { bestA = runA; bestB = yy }
                } else runA = -1
            }
            if (bestA < 0) continue
            val bodyTop = bestA.toDouble()
            val bodyBottom = bestB.toDouble()
            if (bodyBottom - bodyTop < 1.0) continue
            val high = -hi.toDouble()
            val low = -lo.toDouble()
            val open: Double
            val close: Double
            if (kind == 1) { open = -bodyBottom; close = -bodyTop }
            else { open = -bodyTop; close = -bodyBottom }
            val c = Candle(open, close, high, low)
            if (c.high >= max(c.open, c.close) && c.low <= minOf(c.open, c.close)) {
                out.add(c); acceptedPixels += pixels
            }
        }
        val density = if (active == 0) 0.0 else out.size.toDouble() / active
        val quality = (density * 100.0).coerceIn(0.0, 100.0)
        val msg = when {
            out.size < 20 -> "لم يتم تمييز شموع كافية؛ اضبط القص والتكبير والألوان"
            quality < 3.0 -> "جودة التقسيم منخفضة؛ راجع منطقة الشارت"
            else -> "تم رصد ${out.size} شمعة تقريبية"
        }
        return CandleReadResult(out, active, out.size, quality, msg)
    }
}
