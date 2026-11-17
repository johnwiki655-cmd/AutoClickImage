package com.example.autoclickimage

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var etThr: EditText
    private lateinit var etInt: EditText
    private lateinit var etMax: EditText
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private fun dir() = File(filesDir, "templates").apply { mkdirs() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        setContentView(ScrollView(this).apply { addView(col) })

        info = TextView(this).apply { textSize = 16f }
        col.addView(info)
        fun btn(t: String, f: () -> Unit) = col.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        fun field(label: String, v: String): EditText {
            col.addView(TextView(this).apply { text = label })
            return EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v) }.also { col.addView(it) }
        }

        btn("1. Thêm ảnh mẫu (chọn nhiều ảnh)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, 1)
        }
        btn("Xóa tất cả ảnh mẫu") { dir().listFiles()?.forEach { it.delete() }; refresh() }
        etThr = field("Độ giống tối thiểu (%, 60-99)", prefs.getInt("thr", 85).toString())
        etInt = field("Chu kỳ quét (ms)", prefs.getLong("interval", 700).toString())
        etMax = field("Số lần click tối đa (0 = không giới hạn)", prefs.getInt("max", 0).toString())
        btn("2. Bật Trợ năng (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("3. BẮT ĐẦU") { start() }
        btn("DỪNG") { startService(Intent(this, CaptureService::class.java).setAction("STOP")) }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun accOn() = ClickService.instance != null

    private fun refresh() {
        info.text = "Ảnh mẫu: ${dir().listFiles()?.size ?: 0}\nTrợ năng: ${if (accOn()) "ĐÃ BẬT" else "CHƯA BẬT"}"
    }

    private fun start() {
        if (!accOn()) { Toast.makeText(this, "Hãy bật Trợ năng trước", Toast.LENGTH_LONG).show(); return }
        if (dir().listFiles().isNullOrEmpty()) { Toast.makeText(this, "Chưa có ảnh mẫu", Toast.LENGTH_LONG).show(); return }
        prefs.edit()
            .putInt("thr", (etThr.text.toString().toIntOrNull() ?: 85).coerceIn(50, 99))
            .putLong("interval", (etInt.text.toString().toLongOrNull() ?: 700).coerceAtLeast(100))
            .putInt("max", etMax.text.toString().toIntOrNull() ?: 0).apply()
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == 1) {
            val uris = mutableListOf(data.data).apply {
                data.clipData?.let { c -> clear(); for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            }.filterNotNull()
            uris.forEachIndexed { i, u ->
                contentResolver.openInputStream(u)?.use { ins ->
                    File(dir(), "${System.currentTimeMillis()}_$i.img").outputStream().use { ins.copyTo(it) }
                }
            }
            refresh()
        } else if (req == 2) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra("code", res).putExtra("data", data))
            Toast.makeText(this, "Đã bắt đầu! Hãy mở app/game cần click.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}
