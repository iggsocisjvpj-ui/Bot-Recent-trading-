package com.candle.assistant

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/** side: 1 شراء، -1 بيع، 0 لا دخول */
data class Output(val text: String, val color: Int, val side: Int, val x: DoubleArray, val p: Double)

object Signal {
    private fun sign(v: Double) = if (v > 0) 1.0 else if (v < 0) -1.0 else 0.0

    private fun patterns(c: List<Candle>, atr: Double): Double {
        val n = c.size
        val a = c[n - 3]; val b = c[n - 2]; val d = c[n - 1]
        fun body(x: Candle) = abs(x.close - x.open)
        fun bull(x: Candle) = x.close > x.open
        fun bear(x: Candle) = x.close < x.open
        var v = 0.0
        if (bear(b) && bull(d) && d.open <= b.close && d.close >= b.open && body(d) > body(b)) v += 1.0
        if (bull(b) && bear(d) && d.open >= b.close && d.close <= b.open && body(d) > body(b)) v -= 1.0
        val bd = maxOf(body(d), atr * 0.05)
        val lower = minOf(d.open, d.close) - d.low
        val upper = d.high - maxOf(d.open, d.close)
        val downBefore = c[n - 6].close > d.close
        val upBefore = c[n - 6].close < d.close
        if (lower >= 2 * bd && upper <= bd * 0.7 && downBefore) v += 0.8
        if (upper >= 2 * bd && lower <= bd * 0.7 && upBefore) v -= 0.8
        if (bear(a) && body(a) > atr * 0.5 && body(b) < body(a) * 0.4 && bull(d) && d.close > (a.open + a.close) / 2) v += 1.0
        if (bull(a) && body(a) > atr * 0.5 && body(b) < body(a) * 0.4 && bear(d) && d.close < (a.open + a.close) / 2) v -= 1.0
        if (bull(a) && bull(b) && bull(d) && b.close > a.close && d.close > b.close) v += 0.5
        if (bear(a) && bear(b) && bear(d) && b.close < a.close && d.close < b.close) v -= 0.5
        return v.coerceIn(-1.0, 1.0)
    }

    private fun supportResistance(c: List<Candle>, atr: Double): Double {
        val n = c.size
        val close = c[n - 1].close
        var nearRes = Double.MAX_VALUE
        var nearSup = Double.MAX_VALUE
        for (i in maxOf(3, n - 60)..(n - 4)) {
            var isHigh = true; var isLow = true
            for (j in (i - 3)..(i + 3)) {
                if (j == i) continue
                if (c[j].high >= c[i].high) isHigh = false
                if (c[j].low <= c[i].low) isLow = false
            }
            if (isHigh && c[i].high > close) nearRes = minOf(nearRes, c[i].high - close)
            if (isLow && c[i].low < close) nearSup = minOf(nearSup, close - c[i].low)
        }
        val thr = 0.6 * atr
        var v = 0.0
        if (nearSup <= thr) v += 0.8
        if (nearRes <= thr) v -= 0.8
        return v
    }

    fun compute(c: List<Candle>, tf: Int, minConf: Int, sens: Int, w: DoubleArray, bias: Double): Output {
        val none = DoubleArray(Learner.N)
        if (c.size < 40) {
            return Output("بيانات غير كافية (${c.size} شمعة، المطلوب 40+)\nقرّب الشارت أو عدّل الحدود", Color.DKGRAY, 0, none, 0.5)
        }
        if (c.any { !it.open.isFinite() || !it.close.isFinite() || !it.high.isFinite() ||
                    !it.low.isFinite() || it.high < maxOf(it.open, it.close) ||
                    it.low > minOf(it.open, it.close) || it.high < it.low }) {
            return Output("تعذر الوثوق بقراءة الشموع\\nتحقق من منطقة الشارت والألوان", Color.DKGRAY, 0, none, 0.5)
        }
        val n = c.size
        val closes = c.map { it.close }
        val atr = Ind.atr(c, 14).coerceAtLeast(1e-6)
        val e9 = Ind.ema(closes, 9); val e21 = Ind.ema(closes, 21); val e50 = Ind.ema(closes, 50)
        val rsi = Ind.rsi(closes, 14)
        val adxv = Ind.adx(c, 14)
        val adx = adxv[0]; val pdi = adxv[1]; val mdi = adxv[2]

        val v = DoubleArray(Learner.N)
        // 0 اتجاه المتوسطات
        v[0] = 0.5 * tanh((e9[n - 1] - e21[n - 1]) / atr * 2) + 0.3 * tanh((e21[n - 1] - e50[n - 1]) / atr) +
            0.2 * (if (closes[n - 1] > e9[n - 1]) 1.0 else -1.0)
        // 1 المشتق الأول والثاني
        val d1 = e9[n - 1] - e9[n - 2]
        val d2 = d1 - (e9[n - 2] - e9[n - 3])
        v[1] = 0.7 * tanh(d1 / atr * 4) + 0.3 * tanh(d2 / atr * 8)
        // 2 RSI (ارتداد)
        v[2] = ((50 - rsi) / 30).coerceIn(-1.0, 1.0)
        // 3 MACD
        val e12 = Ind.ema(closes, 12); val e26 = Ind.ema(closes, 26)
        val macd = DoubleArray(n) { e12[it] - e26[it] }
        val sig = Ind.ema(macd.asList(), 9)
        val hist = macd[n - 1] - sig[n - 1]
        val histPrev = macd[n - 2] - sig[n - 2]
        v[3] = (0.7 * tanh(hist / atr * 5) + 0.3 * sign(hist - histPrev)).coerceIn(-1.0, 1.0)
        // 4 ستوكاستك
        fun k3(i: Int) = (0..2).map { Ind.stochK(c, i - it, 14) }.average()
        val k = k3(n - 1)
        val dd = (0..2).map { k3(n - 1 - it) }.average()
        v[4] = when {
            k < 20 -> 0.5 + (if (k > dd) 0.5 else 0.0)
            k > 80 -> -(0.5 + (if (k < dd) 0.5 else 0.0))
            else -> ((k - dd) / 20).coerceIn(-0.3, 0.3)
        }
        // 5 بولنجر
        val bbNow = Ind.bb(closes, n - 1)
        val sdNow = bbNow[1]
        val pb = if (sdNow < 1e-9) 0.5 else (closes[n - 1] - (bbNow[0] - 2 * sdNow)) / (4 * sdNow)
        v[5] = ((0.5 - pb) * 2.2).coerceIn(-1.0, 1.0)
        val bwNow = 4 * sdNow / atr
        var bwSum = 0.0; var bwCnt = 0
        for (e in maxOf(19, n - 31) until n - 1) { bwSum += 4 * Ind.bb(closes, e)[1] / atr; bwCnt++ }
        val squeeze = bwCnt >= 5 && bwNow < 0.6 * (bwSum / bwCnt)
        // 6 الشموع اليابانية
        v[6] = patterns(c, atr)
        // 7 الدعم والمقاومة
        v[7] = supportResistance(c, atr)
        // 8 ميل الانحدار الخطي
        v[8] = tanh(Ind.slope(closes, 15) / atr * 5)
        // 9 اتجاه DI
        v[9] = (pdi - mdi) / maxOf(pdi + mdi, 1e-9) * (if (adx > 20) 1.0 else 0.4)

        val trendF = setOf(0, 1, 3, 8, 9)
        val revF = setOf(2, 4, 5)
        val x = DoubleArray(Learner.N)
        for (i in 0 until Learner.N) {
            val m = when {
                adx >= 25 -> if (i in trendF) 1.3 else if (i in revF) 0.4 else 1.0
                adx < 18 -> if (i in trendF) 0.5 else if (i in revF) 1.3 else 1.2
                else -> 1.0
            }
            x[i] = v[i] * m
        }
        var z = bias
        for (i in 0 until Learner.N) z += w[i] * x[i]
        val pUp = 1 / (1 + exp(-z))
        val up = pUp >= 0.5
        val s = if (up) 1.0 else -1.0
        val pDir = if (up) pUp else 1 - pUp
        var agree = 0; var against = 0
        for (i in 0 until Learner.N) {
            if (x[i] * s > 0.15) agree++
            if (x[i] * s < -0.15) against++
        }
        val need = (7 - sens).coerceIn(2, 6)
        val regime = if (adx >= 25) "اتجاه" else if (adx < 18) "عرضي" else "انتقالي"
        val info = "السوق: $regime | ADX ${adx.toInt()} | RSI ${rsi.toInt()}\nتوافق $agree/10 | ضد $against"
        // هذه درجة توافق داخلية غير معايرة إحصائياً وليست احتمال ربح.
        val conf = (pDir * 100).toInt()

        val reason: String? = when {
            adx < 14 && squeeze && abs(x[6]) < 0.5 && abs(x[7]) < 0.5 -> "سوق ضيق بلا زخم"
            conf < minConf -> "الثقة $conf% أقل من الحد $minConf%"
            agree < need -> "توافق ضعيف بين المؤشرات"
            against > 2 -> "تعارض بين المؤشرات"
            else -> null
        }
        if (reason != null) return Output("لا تدخل — $reason\n$info", Color.DKGRAY, 0, x, pDir)

        val candles = if (adx >= 30) 3 else if (adx >= 20) 2 else 1
        val dur = candles * tf
        val head = if (up) "شراء ▲" else "بيع ▼"
        val col = if (up) Color.rgb(0, 140, 70) else Color.rgb(200, 40, 40)
        return Output("$head  ثقة $conf%\nالمدة: $dur ثانية ($candles شمعة)\n$info", col, if (up) 1 else -1, x, pDir)
    }
}
