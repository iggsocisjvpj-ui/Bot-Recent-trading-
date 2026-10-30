package com.candle.assistant

import android.content.Context
import kotlin.math.exp

/** تعلم تدريجي محافظ من صفقة واحدة مؤكدة يربط المستخدم نتيجتها بالإشارة التي دخل عليها. */
object Learner {
    const val N = 10
    private const val MAX_PENDING_AGE_MS = 15 * 60 * 1000L
    private const val LEARNING_RATE = 0.015
    private fun prefs(c: Context) = c.getSharedPreferences("learn", Context.MODE_PRIVATE)

    fun weights(c: Context): DoubleArray {
        val d = doubleArrayOf(0.45, 0.35, 0.10, 0.40, 0.08, 0.06, 0.22, 0.18, 0.30, 0.28)
        val p = prefs(c).getString("w", null)?.split(",")
        if (p != null && p.size == N) for (i in 0 until N) {
            val v = p[i].toDoubleOrNull()
            if (v != null && v.isFinite()) d[i] = v.coerceIn(-1.2, 1.2)
        }
        return d
    }

    fun bias(c: Context): Double = prefs(c).getFloat("b", 0f).toDouble().coerceIn(-1.0, 1.0)

    fun setPending(c: Context, o: Output) {
        if (o.side == 0 || o.x.size != N || o.x.any { !it.isFinite() }) return
        prefs(c).edit()
            .putString("pend", o.side.toString() + ";" + o.x.joinToString(","))
            .putLong("pend_at", System.currentTimeMillis())
            .apply()
    }

    /** لا يسجل ولا يتعلم إلا إذا سبق ضغط المستخدم «دخلت»؛ يمنع ربط نتيجة قديمة بإشارة جديدة. */
    fun record(c: Context, win: Boolean): Boolean {
        val p = prefs(c)
        val pending = p.getString("pend", null) ?: return false
        val age = System.currentTimeMillis() - p.getLong("pend_at", 0L)
        p.edit().remove("pend").remove("pend_at").apply()
        if (age < 0L || age > MAX_PENDING_AGE_MS) return false
        val parts = pending.split(";")
        if (parts.size != 2) return false
        val side = parts[0].toIntOrNull() ?: return false
        val x = parts[1].split(",").map { it.toDoubleOrNull() ?: Double.NaN }
        if (side !in listOf(-1, 1) || x.size != N || x.any { !it.isFinite() }) return false

        val st = c.getSharedPreferences("stats", Context.MODE_PRIVATE)
        val key = if (win) "w" else "l"
        st.edit().putInt(key, st.getInt(key, 0) + 1).apply()

        val yUp = if (side == 1) win else !win
        val y = if (yUp) 1.0 else 0.0
        val w = weights(c)
        var b = bias(c)
        var z = b
        for (i in 0 until N) z += w[i] * x[i]
        z = z.coerceIn(-20.0, 20.0)
        val pred = 1.0 / (1.0 + exp(-z))
        val error = y - pred
        for (i in 0 until N) w[i] = (w[i] + LEARNING_RATE * error * x[i]).coerceIn(-1.2, 1.2)
        b = (b + LEARNING_RATE * error).coerceIn(-1.0, 1.0)
        p.edit().putString("w", w.joinToString(",")).putFloat("b", b.toFloat())
            .putInt("samples", p.getInt("samples", 0) + 1).apply()
        return true
    }

    fun sampleCount(c: Context): Int = prefs(c).getInt("samples", 0)

    fun reset(c: Context) { prefs(c).edit().clear().apply() }
}
