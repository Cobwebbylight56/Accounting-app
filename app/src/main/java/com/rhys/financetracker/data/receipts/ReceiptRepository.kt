package com.rhys.financetracker.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.PdfStatementReader
import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.ReceiptDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.ReceiptEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Receipts: photos and screenshots kept with payments, and read for the
 * shop, total and date.
 *
 * Pictures are copied into the app's own storage, shrunk to a size that is
 * still easy to read, so they stay even if the original is deleted from the
 * gallery. Reading happens on the phone, through Google Play services; the
 * picture is never sent anywhere.
 *
 * A receipt that arrives as words — an order email, a website's receipt, an
 * invoice PDF — is kept as a picture too, so it shows like any other, and its
 * exact words are kept beside it to be read instead of the picture.
 */
@Singleton
class ReceiptRepository @Inject constructor(
    private val context: Context,
    private val receiptDao: ReceiptDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {

    private val folder: File get() = File(context.filesDir, FOLDER).apply { mkdirs() }

    fun fileFor(fileName: String): File = File(folder, fileName)

    /** The exact words of a receipt that came as words, kept beside its picture. */
    private fun wordsFor(fileName: String): File = File(folder, fileName + WORDS)

    /**
     * Keeps whatever was shared or picked as a receipt: a picture, a PDF, or
     * words shared from an email or a web page (which arrive as a ".txt" the
     * app wrote itself).
     *
     * @throws IllegalStateException with what to tell the user, when it is
     *   only a link to a web page.
     */
    suspend fun keepAny(uri: Uri): String {
        val path = uri.path.orEmpty()
        if (uri.scheme == "file" && path.endsWith(".txt")) {
            val words = withContext(Dispatchers.IO) { File(path).readText() }
            return keepText(words)
        }
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return if (type == "application/pdf" || path.endsWith(".pdf", ignoreCase = true)) keepPdf(uri) else keep(uri)
    }

    /** Keeps a receipt that came as words, drawn as a slip, with the words kept exactly. */
    suspend fun keepText(text: String): String = withContext(Dispatchers.IO) {
        check(!EReceipts.isJustALink(text)) {
            "That's a link to a web page. The app can't open your accounts on websites — take a screenshot " +
                "of the receipt instead, or select its words and share those."
        }
        val words = text.trim().take(EReceipts.MAX_CHARS)
        check(words.isNotEmpty()) { "There were no words in what was shared." }
        val bitmap = slipOf(words)
        val name = "receipt-${UUID.randomUUID()}.jpg"
        fileFor(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        wordsFor(name).writeText(words)
        name
    }

    /** Keeps a PDF receipt or invoice: its first pages drawn as one picture, and its words kept exactly. */
    suspend fun keepPdf(uri: Uri): String = withContext(Dispatchers.IO) {
        // A copy the renderer can move about in; a shared file often can't be.
        val copy = File(context.cacheDir, "receipt.pdf")
        val input = context.contentResolver.openInputStream(uri) ?: error("That PDF couldn't be opened.")
        input.use { source -> copy.outputStream().use { source.copyTo(it) } }
        val words = runCatching { copy.inputStream().use { PdfStatementReader.extractTextForDiagnostics(it) } }
            .getOrDefault("")
            .trim()
            .take(EReceipts.MAX_CHARS)
        val bitmap = pagesOf(copy)
        val name = "receipt-${UUID.randomUUID()}.jpg"
        fileFor(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        copy.delete()
        if (words.isNotEmpty()) wordsFor(name).writeText(words)
        name
    }

    /** Words drawn dark on white, like a printed slip, wrapped to a phone-sized width. */
    private fun slipOf(words: String): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x22, 0x22, 0x22)
            textSize = SLIP_TEXT
        }
        val inner = SLIP_WIDTH - SLIP_PADDING * 2
        val layout = StaticLayout.Builder.obtain(words, 0, words.length, paint, inner)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        val height = (layout.height + SLIP_PADDING * 2).coerceAtMost(MAX_SLIP_HEIGHT)
        val bitmap = Bitmap.createBitmap(SLIP_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(SLIP_PADDING.toFloat(), SLIP_PADDING.toFloat())
        canvas.clipRect(0, 0, inner, height - SLIP_PADDING * 2)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** A PDF's first few pages, one under the other, on white. */
    private fun pagesOf(file: File): Bitmap {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            val count = minOf(renderer.pageCount, MAX_PAGES)
            check(count > 0) { "That PDF has no pages." }
            val pages = (0 until count).map { index ->
                val page = renderer.openPage(index)
                try {
                    val scale = PAGE_WIDTH.toFloat() / page.width
                    val bitmap = Bitmap.createBitmap(PAGE_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            }
            val height = pages.sumOf { it.height }
            val shrink = minOf(1f, MAX_PDF_HEIGHT.toFloat() / height)
            val out = Bitmap.createBitmap((PAGE_WIDTH * shrink).toInt(), (height * shrink).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.scale(shrink, shrink)
            var top = 0f
            pages.forEach { page ->
                canvas.drawBitmap(page, 0f, top, null)
                top += page.height
                page.recycle()
            }
            return out
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    fun observeFor(transactionId: Long): Flow<List<ReceiptEntity>> = receiptDao.observeFor(transactionId)

    /** Copies the picture at [uri] into the app, shrunk, and returns its file name. */
    suspend fun keep(uri: Uri): String = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val name = "receipt-${UUID.randomUUID()}.jpg"
        fileFor(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bitmap.recycle()
        name
    }

    /** Everything read off a picture: as rows, as a receipt, and as a list of payments if it is one. */
    data class Scan(
        val text: String,
        val reading: ReceiptParser.Reading,
        val listed: List<ScreenText.ListedPayment>,
    )

    /** Reads the lines off a kept picture, with where each sat; empty when nothing could be read. */
    suspend fun readLines(fileName: String): List<ScreenText.Line> = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, Uri.fromFile(fileFor(fileName)))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            recognizer.process(image).await().textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    ScreenText.Line(line.text, box.left, box.top, box.right, box.bottom)
                }
            }
        } finally {
            recognizer.close()
        }
    }

    /** What a kept picture says, as far as it can be read. */
    suspend fun read(fileName: String): Scan {
        // Came as words: those, exactly, rather than what can be made out of the picture of them.
        val words = withContext(Dispatchers.IO) { wordsFor(fileName).takeIf { it.exists() }?.readText() }
        if (!words.isNullOrBlank()) return Scan(words, ReceiptParser.parse(words), emptyList())
        val lines = runCatching { readLines(fileName) }.getOrDefault(emptyList())
        // Rows as the eye reads them, so "TOTAL" and its amount sit together.
        val text = ScreenText.rowText(lines)
        return Scan(text, ReceiptParser.parse(text), ScreenText.listedPayments(lines))
    }

    /**
     * For each payment listed on a screenshot, the payment already in the
     * app it is — the same amount within a day — or null. Each existing
     * payment is only claimed once, so two £1.25 Tesco trips stay two.
     */
    suspend fun alreadyIn(listed: List<ScreenText.ListedPayment>): List<Long?> {
        val claimed = mutableSetOf<Long>()
        return listed.map { payment ->
            val date = payment.date ?: return@map null
            receiptDao.paymentsFor(payment.amountMinor, date.minusDays(1), date.plusDays(1), date)
                .firstOrNull { it.id !in claimed }
                ?.id
                ?.also { claimed += it }
        }
    }

    /**
     * Adds payments read off a screenshot, into [accountId]. They stand in
     * like payments from a bank alert: when the statement comes, each is
     * matched to the bank's line and replaced by it. Returns how many were
     * added.
     */
    suspend fun addListed(listed: List<ScreenText.ListedPayment>, accountId: Long): Int {
        val learned = learned()
        var added = 0
        for (payment in listed) {
            val date = payment.date ?: continue
            val type = if (payment.isMoneyIn) TransactionType.INCOME else TransactionType.EXPENSE
            val hash = "shot:${payment.amountMinor}:$date:${TransactionFingerprint.normaliseDescription(payment.payee)}"
            if (transactionDao.countWithHash(hash) > 0) continue
            transactionDao.insert(
                TransactionEntity(
                    amountMinor = payment.amountMinor,
                    type = type,
                    date = date,
                    description = payment.payee,
                    accountId = accountId,
                    categoryId = categoryIdFor(payment.payee, type, learned),
                    importHash = hash,
                    source = RecordSource.LIVE,
                ),
            )
            added++
        }
        return added
    }

    private suspend fun learned(): Map<String, String> =
        transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in Refiling.VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }

    private suspend fun categoryIdFor(payee: String, type: TransactionType, learned: Map<String, String>): Long? {
        val name = MerchantCategoriser.categoryFor(payee, type, learned) ?: return null
        for (kind in listOf(CategoryKind.TRANSFER, CategoryKind.SAVING, CategoryKind.CASH)) {
            categoryDao.getByNameAndKind(name, kind)?.let { return it.id }
        }
        val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        return categoryDao.getByNameAndKind(name, kind)?.id
    }

    /** Payments a receipt for [amountMinor] on [date] could be for, the likeliest first. */
    suspend fun paymentsFor(amountMinor: Long, date: LocalDate?): List<TransactionEntity> {
        val around = date ?: LocalDate.now()
        return receiptDao.paymentsFor(
            amountMinor = amountMinor,
            from = around.minusDays(if (date == null) NO_DATE_DAYS else MATCH_DAYS),
            to = around.plusDays(MATCH_DAYS),
            around = around,
        )
    }

    suspend fun attach(transactionId: Long, fileName: String, reading: ReceiptParser.Reading?, text: String?): Long {
        fileShop(transactionId, reading?.shop)
        return keepWith(transactionId, fileName, reading, text)
    }

    /**
     * Files a payment by the receipt's shop when the bank's line didn't say
     * enough to file it — "PAYPAL *4029357733", a card payment with no
     * category — unless it was filed by hand.
     */
    private suspend fun fileShop(transactionId: Long, shop: String?) {
        if (shop.isNullOrBlank()) return
        val payment = transactionDao.getById(transactionId) ?: return
        if (payment.categoryByUser || payment.type != TransactionType.EXPENSE) return
        val current = payment.categoryId?.let { id -> categoryDao.getAll().firstOrNull { it.id == id }?.name }
        if (current != null && current !in Refiling.VAGUE && current !in Refiling.WEAK) return
        val categoryId = categoryIdFor(shop, TransactionType.EXPENSE, learned()) ?: return
        if (categoryId == payment.categoryId) return
        transactionDao.update(payment.copy(categoryId = categoryId, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun keepWith(transactionId: Long, fileName: String, reading: ReceiptParser.Reading?, text: String?): Long =
        receiptDao.insert(
            ReceiptEntity(
                transactionId = transactionId,
                fileName = fileName,
                shop = reading?.shop,
                totalMinor = reading?.totalMinor,
                receiptDate = reading?.date,
                readText = text?.takeIf { it.isNotBlank() },
            ),
        )

    /**
     * A new payment for a receipt with nothing to attach it to, filed like a
     * statement line, with the receipt kept on it. Returns the payment's id.
     */
    suspend fun addPayment(
        shop: String,
        amountMinor: Long,
        date: LocalDate,
        accountId: Long,
        fileName: String,
        reading: ReceiptParser.Reading?,
        text: String?,
    ): Long {
        val categoryId = categoryIdFor(shop, TransactionType.EXPENSE, learned())
        val id = transactionDao.insert(
            TransactionEntity(
                amountMinor = amountMinor,
                type = TransactionType.EXPENSE,
                date = date,
                description = shop,
                accountId = accountId,
                categoryId = categoryId,
                source = RecordSource.MANUAL,
            ),
        )
        keepWith(id, fileName, reading, text)
        return id
    }

    suspend fun remove(receipt: ReceiptEntity) {
        receiptDao.delete(receipt)
        withContext(Dispatchers.IO) {
            fileFor(receipt.fileName).delete()
            wordsFor(receipt.fileName).delete()
        }
    }

    /** Throws away a kept picture that was never attached to anything. */
    suspend fun discard(fileName: String) = withContext(Dispatchers.IO) {
        if (fileName !in receiptDao.fileNames()) {
            fileFor(fileName).delete()
            wordsFor(fileName).delete()
        }
    }

    /** Deletes pictures, and the words beside them, whose payment has gone. */
    suspend fun tidyUp() = withContext(Dispatchers.IO) {
        val inUse = receiptDao.fileNames().toSet()
        folder.listFiles()?.filter { it.name.removeSuffix(WORDS) !in inUse && it.lastModified() < System.currentTimeMillis() - DAY }
            ?.forEach { it.delete() }
    }

    /** A camera photo goes here first, then is kept like any other picture. */
    fun cameraFile(): File = File(File(context.cacheDir, "camera").apply { mkdirs() }, "receipt.jpg")

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        const val FOLDER = "receipts"
        const val MAX_SIDE = 1_800
        const val QUALITY = 85
        const val MATCH_DAYS = 7L
        const val NO_DATE_DAYS = 45L
        const val DAY = 24 * 60 * 60 * 1000L

        /** Beside a picture: the words it was drawn from. */
        const val WORDS = ".txt"

        /** A shared receipt drawn as a slip: about a phone screen wide. */
        const val SLIP_WIDTH = 1_080
        const val SLIP_PADDING = 56
        const val SLIP_TEXT = 32f
        const val MAX_SLIP_HEIGHT = 9_000

        /** A PDF receipt: its first pages, a page wide, never taller than this. */
        const val MAX_PAGES = 3
        const val PAGE_WIDTH = 1_240
        const val MAX_PDF_HEIGHT = 5_200
    }
}
