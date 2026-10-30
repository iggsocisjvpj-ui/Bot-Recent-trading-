package com.candle.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 1001
    private lateinit var stats: TextView
    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun label(t: String, size: Float = 15f) {
            root.addView(TextView(this).apply { text = t; textSize = size; setPadding(0, 24, 0, 4) })
        }
        fun field(key: String, title: String, def: Int) {
            label(title)
            val e = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(p.getInt(key, def).toString())
            }
            fields[key] = e
            root.addView(e)
        }
        fun button(t: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = t; setOnClickListener { action() } })
        }

        label("محلل الشموع المتقدم", 24f)
        label("افتح الشارت في Pocket Option بالشموع اليابانية، وقرّبه حتى تظهر 40 إلى 60 شمعة. " +
            "حدّد أين يبدأ الشارت وأين ينتهي (كنسبة من ارتفاع الشاشة) بحيث لا تدخل أزرار شراء/بيع ضمنه.")
        field("top", "بداية الشارت من أعلى الشاشة %", 12)
        field("bottom", "نهاية الشارت من أعلى الشاشة %", 60)
        field("tf", "مدة الشمعة بالثواني (60 = دقيقة)", 60)
        field("conf", "الحد الأدنى للثقة % (50 إلى 95)", 60)
        field("sens", "الحساسية (1 صارم وبطيء، 5 حساس وسريع)", 3)
        button("ابدأ التحليل") { start() }
        button("إيقاف التحليل") {
            stopService(Intent(this, CaptureService::class.java))
            Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show()
        }
        label("سجل نتيجة الصفقة (يمكنك التسجيل من النافذة العائمة أيضاً):")
        button("ربحت ✔") { Learner.record(this, true); refreshStats() }
        button("خسرت ✘") { Learner.record(this, false); refreshStats() }
        button("تصفير الإحصاءات والتعلم") {
            Learner.reset(this)
            getSharedPreferences("stats", Context.MODE_PRIVATE).edit().clear().apply()
            refreshStats()
        }
        stats = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 0) }
        root.addView(stats)
        label("تنبيه: الثقة المعروضة درجة تقديرية من النموذج وليست احتمالاً مضموناً. لا يوجد نظام يضمن نسبة ربح، والقرار والنقر بيدك. " +
            "جرّب بالحساب التجريبي وراقب النسبة الفعلية لـ 50 صفقة على الأقل قبل أي مال حقيقي.", 13f)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun start() {
        val ed = getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
        for ((k, e) in fields) ed.putInt(k, e.text.toString().toIntOrNull() ?: 0)
        val top = (fields["top"]?.text?.toString()?.toIntOrNull() ?: 12).coerceIn(0, 90)
        val bottom = (fields["bottom"]?.text?.toString()?.toIntOrNull() ?: 60).coerceIn(top + 5, 100)
        val tf = (fields["tf"]?.text?.toString()?.toIntOrNull() ?: 60).coerceIn(1, 3600)
        val conf = (fields["conf"]?.text?.toString()?.toIntOrNull() ?: 60).coerceIn(50, 95)
        val sens = (fields["sens"]?.text?.toString()?.toIntOrNull() ?: 3).coerceIn(1, 5)
        ed.putInt("top", top).putInt("bottom", bottom).putInt("tf", tf)
            .putInt("conf", conf).putInt("sens", sens).apply()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات» ثم ارجع واضغط ابدأ", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    private fun refreshStats() {
        val s = getSharedPreferences("stats", Context.MODE_PRIVATE)
        val w = s.getInt("w", 0)
        val l = s.getInt("l", 0)
        val n = w + l
        val rate = if (n == 0) 0 else w * 100 / n
        stats.text = "الصفقات المسجلة: $n   |   ربح: $w   |   خسارة: $l   |   النسبة الفعلية: $rate%\n" +
            "عينات التعلم: ${Learner.sampleCount(this)}\n" +
            "مهم: اضغط «دخلت» لكل صفقة قبل تسجيل الربح/الخسارة. لا تسجل نتيجة بلا صفقة معلّقة."
    }
}
