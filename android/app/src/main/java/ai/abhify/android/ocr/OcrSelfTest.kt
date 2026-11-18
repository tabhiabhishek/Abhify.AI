package ai.abhify.android.ocr

import ai.abhify.android.AppGraph
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Synthetic test images with known text (no user data): used by the OCR self-test and the instrumented benchmark. */
object OcrSamples {
    val RESUME = listOf(
        "Priya Sharma", "priya.sharma@example.com  +91 98765 43210", "EXPERIENCE",
        "Data Analyst, Northwind Traders  Jan 2021 - Present", "Built Power BI dashboards used by 40 managers",
        "Reduced report preparation time by 35%", "EDUCATION", "B.Tech Computer Science, 2020", "SKILLS", "Python, SQL, Excel, Power BI, Tableau")

    /** Renders [lines] as black text on white, like a screenshot. [size] px font, [width] px image width. */
    fun render(lines: List<String>, width: Int = 1080, size: Float = 40f, rotateDeg: Float = 0f, noise: Boolean = false): Bitmap {
        val lh = (size * 1.6f).toInt()
        val h = lh * (lines.size + 2)
        val b = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.WHITE)
        if (rotateDeg != 0f) c.rotate(rotateDeg, width / 2f, h / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.SANS_SERIF }
        lines.forEachIndexed { i, l -> c.drawText(l, 48f, (lh * (i + 1)).toFloat(), p) }
        if (noise) {                                                                     // photo-like speckle
            val r = java.util.Random(7); val dot = Paint().apply { color = Color.GRAY }
            repeat(width * h / 400) { c.drawPoint(r.nextInt(width).toFloat(), r.nextInt(h).toFloat(), dot) }
        }
        return b
    }
}

/** OCR self-test: recognizes a synthetic resume image on this phone and reports the character error rate and time. */
object OcrSelfTest {
    suspend fun run(g: AppGraph): String = g.withOcr {
        val img = OcrSamples.render(OcrSamples.RESUME)
        val engine = MlKitOcr()
        try {
            val t0 = System.nanoTime()
            val r = engine.recognize(img)
            val ms = (System.nanoTime() - t0) / 1e6
            val cer = ExtractionPipeline.cer(OcrSamples.RESUME.joinToString("\n"), r.text)
            "${engine.name}: ${"%.0f".format(ms)} ms for a ${img.width}×${img.height} image · character error rate ${"%.1f".format(cer * 100)}% " +
                "(synthetic sample, not your documents) · ${r.lines.size} lines" + if (cer > 0.1) " · ⚠ higher than expected" else " · ✓ working"
        } finally { engine.close(); img.recycle() }
    }
}
