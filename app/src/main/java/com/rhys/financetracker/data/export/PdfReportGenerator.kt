package com.rhys.financetracker.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.model.PageOrientation
import com.rhys.financetracker.domain.report.Report
import com.rhys.financetracker.domain.report.ReportRow
import com.rhys.financetracker.domain.report.ReportSection
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a [Report] to A4 PDF.
 *
 * The layout rules that keep printed output readable:
 *  * text that would overflow its column is wrapped, never clipped, so no
 *    figure is ever cut off;
 *  * a section heading that would land at the very bottom of a page moves to
 *    the next one, so a heading is never orphaned from its rows;
 *  * every page carries the report title, the period and a page number, so a
 *    printout that gets separated can be put back together.
 */
@Singleton
class PdfReportGenerator @Inject constructor() {

    private companion object {
        // A4 at 72 points per inch, the unit PdfDocument works in.
        const val A4_WIDTH_PORTRAIT = 595
        const val A4_HEIGHT_PORTRAIT = 842

        const val MARGIN = 40f
        const val LINE_HEIGHT = 16f
        const val SECTION_GAP = 18f

        const val TITLE_SIZE = 20f
        const val SUBTITLE_SIZE = 11f
        const val HEADING_SIZE = 13f
        const val BODY_SIZE = 10.5f
        const val FOOTER_SIZE = 8.5f

        const val SWATCH_SIZE = 8f
    }

    fun pageWidth(orientation: PageOrientation): Int = when (orientation) {
        PageOrientation.PORTRAIT -> A4_WIDTH_PORTRAIT
        PageOrientation.LANDSCAPE -> A4_HEIGHT_PORTRAIT
    }

    fun pageHeight(orientation: PageOrientation): Int = when (orientation) {
        PageOrientation.PORTRAIT -> A4_HEIGHT_PORTRAIT
        PageOrientation.LANDSCAPE -> A4_WIDTH_PORTRAIT
    }

    /**
     * Writes [report] to [output].  The stream is not closed here — the caller
     * owns it, because it may be a `content://` stream that needs flushing in a
     * particular order.
     */
    fun write(report: Report, output: OutputStream, orientation: PageOrientation) {
        val document = PdfDocument()
        try {
            val renderer = PageRenderer(document, report, orientation)
            renderer.renderHeader()
            report.sections.forEach { renderer.renderSection(it) }
            renderer.finish()
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    /** Holds the cursor and paints while walking through a report. */
    private inner class PageRenderer(
        private val document: PdfDocument,
        private val report: Report,
        private val orientation: PageOrientation,
    ) {
        private val width = pageWidth(orientation)
        private val height = pageHeight(orientation)
        private val contentWidth = width - MARGIN * 2

        private val titlePaint = paint(TITLE_SIZE, Typeface.DEFAULT_BOLD, Color.rgb(27, 94, 75))
        private val subtitlePaint = paint(SUBTITLE_SIZE, Typeface.DEFAULT, Color.DKGRAY)
        private val headingPaint = paint(HEADING_SIZE, Typeface.DEFAULT_BOLD, Color.BLACK)
        private val bodyPaint = paint(BODY_SIZE, Typeface.DEFAULT, Color.BLACK)
        private val boldPaint = paint(BODY_SIZE, Typeface.DEFAULT_BOLD, Color.BLACK)
        private val mutedPaint = paint(BODY_SIZE, Typeface.DEFAULT, Color.GRAY)
        private val footerPaint = paint(FOOTER_SIZE, Typeface.DEFAULT, Color.GRAY)
        private val rulePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 0.6f
        }
        private val swatchPaint = Paint().apply { isAntiAlias = true }

        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var cursorY = 0f

        // Column positions: label on the left, amount right-aligned, detail between.
        private val labelX = MARGIN
        private val amountRightX = width - MARGIN
        private val detailRightX = amountRightX - contentWidth * 0.28f
        private val labelWidth = detailRightX - labelX - 12f

        fun renderHeader() {
            startPage()
            val canvas = requireCanvas()
            canvas.drawText(report.title, MARGIN, cursorY, titlePaint)
            cursorY += TITLE_SIZE + 6f
            canvas.drawText(report.period.label, MARGIN, cursorY, subtitlePaint)
            cursorY += SUBTITLE_SIZE + 3f
            canvas.drawText(
                "${report.scope.label} · produced ${DateUtils.format(report.generatedOn)}",
                MARGIN,
                cursorY,
                subtitlePaint,
            )
            cursorY += SUBTITLE_SIZE + 10f
            canvas.drawLine(MARGIN, cursorY, width - MARGIN, cursorY, rulePaint)
            cursorY += SECTION_GAP
        }

        fun renderSection(section: ReportSection) {
            // Keep the heading with at least two rows of its section.
            val needed = HEADING_SIZE + LINE_HEIGHT * 3
            if (cursorY + needed > height - MARGIN * 1.5f) newPage()

            requireCanvas().drawText(section.title, MARGIN, cursorY, headingPaint)
            cursorY += HEADING_SIZE + 8f

            section.rows.forEach { renderRow(it) }

            section.note?.let { note ->
                cursorY += 4f
                wrap(note, mutedPaint, contentWidth).forEach { line ->
                    ensureSpace(LINE_HEIGHT)
                    requireCanvas().drawText(line, MARGIN, cursorY, mutedPaint)
                    cursorY += LINE_HEIGHT
                }
            }
            cursorY += SECTION_GAP
        }

        private fun renderRow(row: ReportRow) {
            val labelPaint = if (row.isTotal) boldPaint else bodyPaint
            val valuePaint = if (row.isTotal) boldPaint else bodyPaint
            val indent = if (row.isSubRow) 14f else 0f
            val swatchIndent = if (row.colorHex != null) SWATCH_SIZE + 6f else 0f

            val lines = wrap(row.label, labelPaint, labelWidth - indent - swatchIndent)
            ensureSpace(LINE_HEIGHT * lines.size)
            val canvas = requireCanvas()

            row.colorHex?.let { hex ->
                swatchPaint.color = parseColor(hex)
                canvas.drawRect(
                    labelX + indent,
                    cursorY - SWATCH_SIZE,
                    labelX + indent + SWATCH_SIZE,
                    cursorY,
                    swatchPaint,
                )
            }

            lines.forEachIndexed { index, line ->
                canvas.drawText(line, labelX + indent + swatchIndent, cursorY, labelPaint)
                if (index == 0) {
                    // The amount is right-aligned so columns of figures line up.
                    canvas.drawText(
                        row.value,
                        amountRightX - valuePaint.measureText(row.value),
                        cursorY,
                        valuePaint,
                    )
                    row.secondary?.let { secondary ->
                        val trimmed = truncate(secondary, mutedPaint, contentWidth * 0.24f)
                        canvas.drawText(
                            trimmed,
                            detailRightX - mutedPaint.measureText(trimmed),
                            cursorY,
                            mutedPaint,
                        )
                    }
                }
                cursorY += LINE_HEIGHT
            }

            if (row.isTotal) {
                canvas.drawLine(MARGIN, cursorY - 11f, width - MARGIN, cursorY - 11f, rulePaint)
                cursorY += 4f
            }
        }

        fun finish() {
            page?.let {
                drawFooter()
                document.finishPage(it)
            }
            page = null
            canvas = null
        }

        private fun startPage() {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(width, height, pageNumber).create()
            val newPage = document.startPage(info)
            page = newPage
            canvas = newPage.canvas
            cursorY = MARGIN + TITLE_SIZE
        }

        private fun newPage() {
            finish()
            startPage()
            // Continuation pages get a slim header rather than the full one.
            requireCanvas().drawText(
                "${report.title} — ${report.period.label} (continued)",
                MARGIN,
                cursorY - 8f,
                subtitlePaint,
            )
            cursorY += 12f
        }

        private fun ensureSpace(needed: Float) {
            if (cursorY + needed > height - MARGIN * 1.5f) newPage()
        }

        private fun drawFooter() {
            val canvas = canvas ?: return
            val text = "Page $pageNumber · Finance Tracker"
            canvas.drawText(text, MARGIN, height - MARGIN * 0.6f, footerPaint)
        }

        private fun requireCanvas(): Canvas = canvas ?: run {
            startPage()
            canvas!!
        }
    }

    // ------------------------------------------------------------- helpers

    private fun paint(size: Float, typeface: Typeface, colour: Int): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.typeface = typeface
        color = colour
    }

    /** Breaks [text] onto as many lines as it needs to fit [maxWidth]. */
    internal fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (paint.measureText(text) <= maxWidth) return listOf(text)

        val lines = mutableListOf<String>()
        val current = StringBuilder()
        text.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current.clear()
                // A single word longer than the column is broken by character.
                if (paint.measureText(word) > maxWidth) {
                    var chunk = StringBuilder()
                    word.forEach { character ->
                        if (paint.measureText("$chunk$character") > maxWidth) {
                            lines += chunk.toString()
                            chunk = StringBuilder()
                        }
                        chunk.append(character)
                    }
                    current.append(chunk)
                } else {
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** Shortens [text] with an ellipsis so a detail column never overruns. */
    internal fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun parseColor(hex: String): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)
}
