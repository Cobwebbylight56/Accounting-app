package com.rhys.financetracker.ui.receipts

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.ReceiptEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.receipts.ReceiptParser
import com.rhys.financetracker.data.receipts.ReceiptRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ------------------------------------------------------------------ scanning

private const val ACCOUNTS_WAIT_MILLIS = 2_000L

enum class ScanStage { PICK, READING, REVIEW }

/** A payment on a screenshot of a list, whether it is in the app already, and whether to add it. */
data class ListedRow(
    val payment: com.rhys.financetracker.data.receipts.ScreenText.ListedPayment,
    val alreadyIn: Boolean,
    val selected: Boolean,
)

data class ScanState(
    val stage: ScanStage = ScanStage.PICK,
    val fileName: String? = null,
    val text: String? = null,
    val reading: ReceiptParser.Reading? = null,
    val shop: String = "",
    val amountText: String = "",
    val date: LocalDate = LocalDate.now(),
    val matches: List<TransactionEntity> = emptyList(),
    val accountId: Long? = null,
    val message: String? = null,
    /** Set once the receipt is kept with a payment: where to go next. */
    val doneWith: Long? = null,
    val addedNew: Boolean = false,
    /** Payments listed on a screenshot like Google Wallet's, when it is one. */
    val listed: List<ListedRow> = emptyList(),
    /** Set once listed payments have been added. */
    val listAdded: Int? = null,
) {
    val amountMinor: Long? get() = Money.parseOrNull(amountText)?.takeIf { it > 0L }
}

@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receipts: ReceiptRepository,
    private val settingsRepository: SettingsRepository,
    accountRepository: AccountRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The payment to keep the receipt with, when scanning from one. */
    val attachTo: Long = savedStateHandle.get<Long>(Routes.ARG_ATTACH_TO) ?: Routes.NEW_ID
    private val sharedImage: String? = savedStateHandle.get<String>(Routes.ARG_IMAGE)

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    val accounts: StateFlow<List<AccountEntity>> = accountRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var kept = false

    init {
        sharedImage?.let { picked(Uri.parse(it)) }
        viewModelScope.launch { runCatching { receipts.tidyUp() } }
    }

    fun cameraFile(): File = receipts.cameraFile()

    fun picked(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(stage = ScanStage.READING, message = null) }
            val fileName = runCatching { receipts.keep(uri) }.getOrElse {
                _state.update { it.copy(stage = ScanStage.PICK, message = "That picture couldn't be opened. Try another.") }
                return@launch
            }
            val scan = receipts.read(fileName)
            val text = scan.text
            val reading = scan.reading
            val settings = settingsRepository.settings.first()
            val spending = accounts.value.ifEmpty {
                withTimeoutOrNull(ACCOUNTS_WAIT_MILLIS) { accounts.first { it.isNotEmpty() } }.orEmpty()
            }
            // A screenshot of a list of payments — Google Wallet, a banking
            // app — is several payments, not one receipt.
            val listed = if (attachTo == Routes.NEW_ID && scan.listed.size >= 2) {
                val already = receipts.alreadyIn(scan.listed)
                scan.listed.mapIndexed { i, payment -> ListedRow(payment, already[i] != null, already[i] == null) }
            } else {
                emptyList()
            }
            val cardAccount = reading.cardEnding?.let { settings.liveCardAccounts[it] }
            _state.update {
                it.copy(
                    stage = ScanStage.REVIEW,
                    fileName = fileName,
                    text = text,
                    reading = reading,
                    listed = listed,
                    shop = reading.shop.orEmpty(),
                    amountText = reading.totalMinor?.let(Money::formatPlain).orEmpty(),
                    date = reading.date ?: LocalDate.now(),
                    accountId = cardAccount?.takeIf { id -> spending.any { a -> a.id == id } }
                        ?: settings.defaultAccountId?.takeIf { id -> spending.any { a -> a.id == id } }
                        ?: spending.firstOrNull { a -> a.holding == Holding.SPEND }?.id
                        ?: spending.firstOrNull()?.id,
                    message = if (text.isBlank()) {
                        "Couldn't read any writing on that — fill in what it says, or try a clearer photo."
                    } else {
                        null
                    },
                )
            }
            refreshMatches()
        }
    }

    fun setShop(text: String) = _state.update { it.copy(shop = text) }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text) }
        refreshMatches()
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(date = date) }
        refreshMatches()
    }

    fun setAccount(id: Long) = _state.update { it.copy(accountId = id) }

    private fun refreshMatches() {
        if (attachTo != Routes.NEW_ID) return
        viewModelScope.launch {
            val current = _state.value
            val amount = current.amountMinor
            val matches = if (amount == null) emptyList() else receipts.paymentsFor(amount, current.date)
            _state.update { it.copy(matches = matches) }
        }
    }

    /** Keeps the receipt with an existing payment. */
    fun attach(transactionId: Long) {
        val current = _state.value
        val fileName = current.fileName ?: return
        viewModelScope.launch {
            receipts.attach(transactionId, fileName, current.reading, current.text)
            kept = true
            _state.update { it.copy(doneWith = transactionId) }
        }
    }

    /** Adds a payment for the receipt and keeps the receipt with it. */
    fun addNew() {
        val current = _state.value
        val fileName = current.fileName ?: return
        val amount = current.amountMinor
        val account = current.accountId
        when {
            current.shop.isBlank() -> _state.update { it.copy(message = "Add where it was from.") }
            amount == null -> _state.update { it.copy(message = "Add how much it was.") }
            account == null -> _state.update { it.copy(message = "Pick which account paid.") }
            else -> viewModelScope.launch {
                val id = receipts.addPayment(
                    shop = current.shop.trim(),
                    amountMinor = amount,
                    date = current.date,
                    accountId = account,
                    fileName = fileName,
                    reading = current.reading,
                    text = current.text,
                )
                kept = true
                _state.update { it.copy(doneWith = id, addedNew = true) }
            }
        }
    }

    fun toggleListed(index: Int) = _state.update { current ->
        current.copy(
            listed = current.listed.mapIndexed { i, row -> if (i == index) row.copy(selected = !row.selected) else row },
        )
    }

    /** Adds the ticked payments from a screenshot of a list. */
    fun addListed() {
        val current = _state.value
        val account = current.accountId ?: run {
            _state.update { it.copy(message = "Pick which account paid.") }
            return
        }
        viewModelScope.launch {
            val added = receipts.addListed(current.listed.filter { it.selected }.map { it.payment }, account)
            _state.update { it.copy(listAdded = added) }
        }
    }

    /** Starts again with another picture. */
    fun startAgain() {
        _state.value.fileName?.let { name -> viewModelScope.launch { receipts.discard(name) } }
        _state.value = ScanState()
    }

    fun fileFor(name: String): File = receipts.fileFor(name)

    override fun onCleared() {
        // A picture read but never kept with a payment is not kept at all.
        val name = _state.value.fileName
        if (!kept && name != null) runCatching { receipts.fileFor(name).delete() }
        super.onCleared()
    }
}

/**
 * Scan a receipt: take a photo or choose a picture or screenshot, see what
 * was read off it, then keep it with the payment it was for — one the app
 * finds by amount and date, or a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onBack: () -> Unit,
    onKeptWithPayment: (Long) -> Unit,
    onAddedPayment: (Long) -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var enlarged by remember { mutableStateOf(false) }

    LaunchedEffect(state.doneWith) {
        state.doneWith?.let { id -> if (state.addedNew) onAddedPayment(id) else onKeptWithPayment(id) }
    }
    LaunchedEffect(state.listAdded) {
        state.listAdded?.let { added ->
            android.widget.Toast.makeText(
                context,
                if (added == 1) "1 payment added" else "$added payments added",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onBack()
        }
    }

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::picked)
    }
    val cameraUri = remember { cameraUriFor(context, viewModel.cameraFile()) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) viewModel.picked(cameraUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.attachTo != Routes.NEW_ID) "Add a receipt" else "Scan a receipt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            when (state.stage) {
                ScanStage.PICK -> {
                    Spacer(Modifier.height(24.dp))
                    Icon(
                        Icons.Outlined.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Photograph a paper receipt, or choose a picture or screenshot of one — an " +
                            "online order, a bank app, a till receipt. The shop, total and date are read " +
                            "off it on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { camera.launch(cameraUri) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Take a photo")
                    }
                    OutlinedButton(
                        onClick = { choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose a picture or screenshot")
                    }
                }

                ScanStage.READING -> {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(
                        "Reading the receipt…",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                ScanStage.REVIEW -> if (state.listed.isNotEmpty()) {
                    ListedPayments(
                        state = state,
                        accounts = accounts,
                        onToggle = viewModel::toggleListed,
                        onAccount = viewModel::setAccount,
                        onAdd = viewModel::addListed,
                        onStartAgain = viewModel::startAgain,
                    )
                } else {
                    val file = state.fileName?.let(viewModel::fileFor)
                    if (file != null) {
                        ReceiptImage(
                            file = file,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { enlarged = true },
                            contentScale = ContentScale.Fit,
                        )
                    }
                    LabelledTextField(label = "Where from", value = state.shop, onValueChange = viewModel::setShop)
                    AmountField(label = "Total", value = state.amountText, onValueChange = viewModel::setAmount)
                    DateField(label = "Date", date = state.date, onDateChange = viewModel::setDate)

                    if (viewModel.attachTo != Routes.NEW_ID) {
                        Button(
                            onClick = { viewModel.attach(viewModel.attachTo) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Keep it with this payment") }
                    } else {
                        if (state.matches.isNotEmpty()) {
                            Text(
                                "Is it one of these?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.matches.forEach { payment ->
                                MatchCard(
                                    payment = payment,
                                    accountName = accounts.firstOrNull { it.id == payment.accountId }?.name,
                                    onClick = { viewModel.attach(payment.id) },
                                )
                            }
                            Text(
                                "Or add it as a new payment",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else if (state.amountMinor != null) {
                            Text(
                                "No payment of that amount around then yet — add it as a new one. When the " +
                                    "statement comes in, it is matched to the bank's line, not added twice.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownField(
                            label = "Paid from",
                            options = accounts,
                            selected = accounts.firstOrNull { it.id == state.accountId },
                            onSelect = { viewModel.setAccount(it.id) },
                            optionLabel = { it.name },
                        )
                        Button(onClick = viewModel::addNew, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("Add as a new payment")
                        }
                    }
                    TextButton(onClick = viewModel::startAgain) { Text("Use a different picture") }
                }
            }
        }
    }

    if (enlarged) {
        state.fileName?.let { name -> ReceiptViewer(file = viewModel.fileFor(name), onClose = { enlarged = false }) }
    }
}

/** The payments on a screenshot of a list, ticked to add unless they are in the app already. */
@Composable
private fun ListedPayments(
    state: ScanState,
    accounts: List<AccountEntity>,
    onToggle: (Int) -> Unit,
    onAccount: (Long) -> Unit,
    onAdd: () -> Unit,
    onStartAgain: () -> Unit,
) {
    val chosen = state.listed.count { it.selected }
    val already = state.listed.count { it.alreadyIn }
    Text(
        "${state.listed.size} payments on this screenshot",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        if (already > 0) {
            "$already of them ${if (already == 1) "is" else "are"} in the app already and left unticked. " +
                "Tick or untick any, then add them."
        } else {
            "Untick any you don't want, then add them."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.listed.forEachIndexed { index, row ->
        Card(modifier = Modifier.fillMaxWidth().clickable { onToggle(index) }) {
            Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = row.selected, onCheckedChange = { onToggle(index) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.payment.payee, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Text(
                        listOfNotNull(
                            row.payment.date?.let(DateUtils::format),
                            "In the app already".takeIf { row.alreadyIn },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    (if (row.payment.isMoneyIn) "+" else "") + Money.format(row.payment.amountMinor),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
    DropdownField(
        label = "Paid from",
        options = accounts,
        selected = accounts.firstOrNull { it.id == state.accountId },
        onSelect = { onAccount(it.id) },
        optionLabel = { it.name },
    )
    Button(onClick = onAdd, enabled = chosen > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (chosen == 1) "Add 1 payment" else "Add $chosen payments")
    }
    Text(
        "When the statement comes in, each is matched to the bank's line and replaced by it, so " +
            "nothing is counted twice.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onStartAgain) { Text("Use a different picture") }
}

@Composable
private fun MatchCard(payment: TransactionEntity, accountName: String?, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(payment.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    listOfNotNull(DateUtils.format(payment.date), accountName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Money.format(payment.amountMinor), style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ------------------------------------------------------- on a payment's page

@HiltViewModel
class PaymentReceiptsViewModel @Inject constructor(
    private val receipts: ReceiptRepository,
) : ViewModel() {

    val reading = MutableStateFlow(false)

    fun receiptsFor(transactionId: Long): Flow<List<ReceiptEntity>> = receipts.observeFor(transactionId)

    fun fileFor(name: String): File = receipts.fileFor(name)

    fun cameraFile(): File = receipts.cameraFile()

    fun add(transactionId: Long, uri: Uri) {
        viewModelScope.launch {
            reading.value = true
            runCatching {
                val name = receipts.keep(uri)
                val (text, parsed) = receipts.read(name)
                receipts.attach(transactionId, name, parsed, text)
            }
            reading.value = false
        }
    }

    fun remove(receipt: ReceiptEntity) {
        viewModelScope.launch { receipts.remove(receipt) }
    }
}

/**
 * The receipts, photos and screenshots kept with a payment, and buttons to
 * add more. Tap one to see it full size.
 */
@Composable
fun PaymentReceipts(
    transactionId: Long,
    viewModel: PaymentReceiptsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val receipts by remember(transactionId) { viewModel.receiptsFor(transactionId) }.collectAsState(initial = emptyList())
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<ReceiptEntity?>(null) }

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.add(transactionId, it) }
    }
    val cameraUri = remember { cameraUriFor(context, viewModel.cameraFile()) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) viewModel.add(transactionId, cameraUri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Receipts and photos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (receipts.isNotEmpty() || reading) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(receipts, key = { it.id }) { receipt ->
                    ReceiptImage(
                        file = viewModel.fileFor(receipt.fileName),
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { open = receipt },
                        maxSide = 300,
                    )
                }
                if (reading) {
                    item {
                        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        } else {
            Text(
                "Keep the receipt, a photo or a screenshot with this payment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { camera.launch(cameraUri) }) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Take a photo")
            }
            OutlinedButton(onClick = { choose.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Outlined.Image, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add a picture")
            }
        }
    }

    open?.let { receipt ->
        ReceiptViewer(
            file = viewModel.fileFor(receipt.fileName),
            caption = listOfNotNull(
                receipt.shop,
                receipt.totalMinor?.let(Money::format),
                receipt.receiptDate?.let(DateUtils::format),
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            onClose = { open = null },
            onDelete = {
                viewModel.remove(receipt)
                open = null
            },
        )
    }
}

// --------------------------------------------------------------- pictures

/** A kept picture, loaded off the main thread and shrunk to [maxSide]. */
@Composable
fun ReceiptImage(
    file: File,
    modifier: Modifier = Modifier,
    maxSide: Int = 1_200,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.path, maxSide) {
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(file, maxSide)?.asImageBitmap() }.getOrNull() }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = "Receipt", modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = 88.dp))
    }
}

/** A picture full screen: pinch to zoom, drag to move. */
@Composable
private fun ReceiptViewer(
    file: File,
    onClose: () -> Unit,
    caption: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ReceiptImage(
                file = file,
                maxSide = 2_400,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
                Text(
                    caption.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = Color.White)
                    }
                }
            }
            if (confirmDelete && onDelete != null) {
                Card(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Remove this picture from the payment?", style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                            TextButton(onClick = onDelete) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeSampled(file: File, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun cameraUriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
