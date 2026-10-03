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

    /** Reads the text off a kept picture; empty when nothing could be read. */
    suspend fun readText(fileName: String): String = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, Uri.fromFile(fileFor(fileName)))
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }

    /** What a kept picture says: shop, total and date, as far as they can be read. */
    suspend fun read(fileName: String): Pair<String, ReceiptParser.Reading> {
        val text = runCatching { readText(fileName) }.getOrDefault("")
        return text to ReceiptParser.parse(text)
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
        val learned = transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in Refiling.VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }
        val categoryId = MerchantCategoriser.categoryFor(shop, TransactionType.EXPENSE, learned)
            ?.let { name -> categoryDao.getByNameAndKind(name, CategoryKind.EXPENSE)?.id }
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
