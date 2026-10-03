package com.rhys.financetracker.data.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rhys.financetracker.data.importer.MerchantCategoriser
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

    suspend fun attach(transactionId: Long, fileName: String, reading: ReceiptParser.Reading?, text: String?): Long =
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
        attach(id, fileName, reading, text)
        return id
    }

    suspend fun remove(receipt: ReceiptEntity) {
        receiptDao.delete(receipt)
        withContext(Dispatchers.IO) { fileFor(receipt.fileName).delete() }
    }

    /** Throws away a kept picture that was never attached to anything. */
    suspend fun discard(fileName: String) = withContext(Dispatchers.IO) {
        if (fileName !in receiptDao.fileNames()) fileFor(fileName).delete()
    }

    /** Deletes pictures whose payment has gone. */
    suspend fun tidyUp() = withContext(Dispatchers.IO) {
        val inUse = receiptDao.fileNames().toSet()
        folder.listFiles()?.filter { it.name !in inUse && it.lastModified() < System.currentTimeMillis() - DAY }
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
    }
}
