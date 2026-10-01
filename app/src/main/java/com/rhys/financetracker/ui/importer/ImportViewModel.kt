package com.rhys.financetracker.ui.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.importer.AccountFitCheck
import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.DetectedLayout
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportMapping
import com.rhys.financetracker.data.importer.ImportOutcome
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.SheetData
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.OwnAccountMatcher
import com.rhys.financetracker.data.importer.RecurringDetector
import com.rhys.financetracker.data.importer.StatementCheck
import com.rhys.financetracker.data.importer.StatementKind
import com.rhys.financetracker.data.importer.StatementOwner
import com.rhys.financetracker.data.importer.WorkbookData
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.BillFinderRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the spreadsheet import, one step at a time.
 *
 * Nothing is written to the database until [applyImport] is called on the last
 * step, so the user can go back and change their mind at any point.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: SpreadsheetImporter,
    private val peopleRepository: PeopleRepository,
    private val accountRepository: AccountRepository,
    private val billFinder: BillFinderRepository,
    private val importHistory: com.rhys.financetracker.data.repository.ImportHistoryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** The person's other accounts a row can be moved to; loaded with the statement. */
    private val _ownTargets = MutableStateFlow<List<OwnAccountMatcher.Target>>(emptyList())
    val ownTargets: StateFlow<List<OwnAccountMatcher.Target>> = _ownTargets.asStateFlow()

    /**
     * Says where one row's money went: into one of the person's own accounts
     * ([target]), or nowhere of theirs — ordinary spending or income.
     *
     * Saved as a transfer, the choice teaches the importer: the next
     * statement with this payee goes to the same account by itself.
     */
    fun setDestination(candidateId: String, target: OwnAccountMatcher.Target?) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map { candidate ->
                if (candidate.id != candidateId) {
                    candidate
                } else {
                    candidate.copy(
                        transferAccountId = target?.id,
                        transferAccountName = target?.name,
                    )
                }
            },
        )
    }

    /** Ticks or unticks one of the bills found in the statement. */
    fun toggleBill(name: String) {
        val chosen = _state.value.chosenBills
        _state.value = _state.value.copy(
            chosenBills = if (name in chosen) chosen - name else chosen + name,
        )
    }

    /** Sets up the ticked bills as regular payments. */
    fun addChosenBills() {
        val current = _state.value
        val bills = current.foundBills.filter { it.name in current.chosenBills }
        if (bills.isEmpty()) return
        viewModelScope.launch {
            val result = billFinder.addAsBills(bills)
            _state.value = _state.value.copy(
                foundBills = emptyList(),
                billsNote = when (result) {
                    is AppResult.Success -> "${result.data} " +
                        (if (result.data == 1) "bill" else "bills") + " added to Bills"
                    is AppResult.Failure -> result.message
                },
            )
        }
    }

    /** The people a statement can be said to belong to. */
    val people: StateFlow<List<PersonEntity>> = peopleRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Offered when filing a statement, so the rows land on the right account. */
    val accounts: StateFlow<List<AccountOption>> = accountRepository.observeActiveOptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Reads the name printed at the top of a statement and says whose it is.
     *
     * The account holder is on every statement and is the one part of the file
     * that says which person's money this is. Not reading it means being asked
     * on every import, which is how a statement ends up filed against the
     * wrong person — or against nobody, which is worse, since an account under
     * nobody's name is invisible to every per-person view in the app.
     *
     * Never acted on by itself: it fills in the account picker's starting
     * point and says what it found, and the choice stays with the user.
     */
    private fun findStatementOwner(uri: Uri, fileName: String?) {
        viewModelScope.launch {
            val lines = importer.readPdfText(uri)?.lines().orEmpty()
            // What kind of account this is a statement for, from its heading
            // (or a CSV's file name). It narrows the account picker to
            // accounts of the same kind, which is what stops a saver's
            // statement being filed against a current account.
            val kind = StatementKind.detect(lines, fileName)
            _state.value = _state.value.copy(statementKind = kind)
            val people = peopleRepository.activePeople()
            val owner = if (lines.isEmpty()) null else StatementOwner.detect(lines, people)
            _state.value = _state.value.copy(printedName = StatementOwner.nameOnStatement(lines))
            if (owner == null) {
                // Nobody here is called that. Worth saying: staying quiet
                // looked exactly like not having read the statement at all,
                // and the answer — add them — is one the user can act on.
                val printed = StatementOwner.nameOnStatement(lines)
                if (printed != null && people.none { it.name.equals(printed, ignoreCase = true) }) {
                    _state.value = _state.value.copy(unknownOwnerName = printed)
                }
                val matching = accounts.value.filter { kind == null || it.holding == kind.kind.holding }
                _state.value = _state.value.copy(
                    preselectedAccountId = _state.value.preselectedAccountId
                        ?: matching.singleOrNull()?.id,
                )
                return@launch
            }
            val theirs = accounts.value.filter {
                it.personName == owner.name && (kind == null || it.holding == kind.kind.holding)
            }
            _state.value = _state.value.copy(
                statementOwnerName = owner.name,
                unknownOwnerName = null,
                // Only when it is unambiguous. Picking the first of several
                // would be filing a statement against a guess.
                preselectedAccountId = _state.value.preselectedAccountId
                    ?: theirs.singleOrNull()?.id,
            )
        }
    }

    /**
     * Makes the account this statement is for, under the person it is
     * addressed to, and chooses it.
     *
     * For the first statement from a saver the app has never seen: rather than
     * leaving it to be filed against whatever account exists, the statement
     * says what it is — "Start to Save", a savings account — and that is what
     * is made, already set aside.
     */
    fun createAccountForStatement() {
        val found = _state.value.statementKind
            ?: StatementKind.Found(StatementKind.CURRENT, productName = null)
        viewModelScope.launch {
            val ownerName = _state.value.filingFor
            val owner = peopleRepository.activePeople()
                .firstOrNull { it.name == ownerName }
                ?: peopleRepository.activePeople().singleOrNull()
            val name = found.productName ?: found.kind.accountType.displayName
            val existing = accounts.value.firstOrNull {
                it.name.equals(name, ignoreCase = true) && it.personName == owner?.name
            }
            if (existing != null) {
                _state.value = _state.value.copy(preselectedAccountId = existing.id)
                return@launch
            }
            when (
                val made = accountRepository.save(
                    AccountEntity(
                        name = name,
                        type = found.kind.accountType,
                        holding = found.kind.holding,
                        personId = owner?.id,
                        colorHex = DefaultData.PALETTE.random(),
                    ),
                )
            ) {
                is AppResult.Success ->
                    _state.value = _state.value.copy(preselectedAccountId = made.data)
                is AppResult.Failure -> _state.value = _state.value.copy(error = made.message)
            }
        }
    }

    /**
     * Step 1: read the file the user picked.
     *
     * If the sheet turns out to be a household budget — a column of figures per
     * person, in blocks — the layout is detected and offered as a single
     * button, because mapping that by hand means running the import once per
     * person and is where people give up.
     */
    fun openFile(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, sourceUri = uri)
            when (val result = importer.readWorkbook(uri)) {
                is AppResult.Success -> {
                    val workbook = result.data
                    val firstSheet = workbook.sheets.firstOrNull()
                    val detected = firstSheet?.let(importer::detectHouseholdLayout)
                    val statement = firstSheet?.let { importer.detectStatement(it) }
                    val before = _state.value
                    _state.value = ImportState(
                        step = if (firstSheet == null) ImportStep.CHOOSE_FILE else ImportStep.MAP,
                        sourceUri = uri,
                        // Where the import was started from survives the
                        // file being opened: the account, or the person.
                        preselectedAccountId = before.preselectedAccountId,
                        expectedPersonId = before.expectedPersonId,
                        expectedPersonName = before.expectedPersonName,
                        // The rest of a pile of statements picked together.
                        queue = before.queue,
                        queueTotal = before.queueTotal,
                        workbook = workbook,
                        selectedSheetIndex = 0,
                        detectedLayout = detected,
                        detectedStatement = statement,
                        // A statement is a list of things that have already
                        // happened, so it defaults to transactions. Offering
                        // "Regular bills" for one invites every payment to be
                        // set up as a repeating bill.
                        mapping = firstSheet?.let {
                            importer.suggestMapping(
                                it,
                                if (statement != null) {
                                    ImportTarget.TRANSACTION
                                } else {
                                    ImportTarget.RECURRING_EXPENSE
                                },
                            )
                        },
                    ).also { newState ->
                        refreshCandidates(newState)
                        if (statement != null) findStatementOwner(uri, workbook.fileName)
                    }
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isBusy = false,
                    error = result.message,
                    // A PDF the reader could not make sense of is worth showing:
                    // the layout is what needs fixing, and it cannot be seen
                    // from here.
                    unreadablePdfText = importer.readPdfText(uri)?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /**
     * Takes the detected layout and builds candidates for every person and
     * every block at once, then goes straight to the review step.
     */
    fun useDetectedLayout() {
        val current = _state.value
        val sheet = current.sheet ?: return
        val layout = current.detectedLayout ?: return
        _state.value = current.copy(
            candidates = importer.buildCandidatesForLayout(sheet, layout),
            usingDetectedLayout = true,
            step = ImportStep.REVIEW,
        )
    }

    /**
     * Reads a bank statement with the detected mapping and goes to review.
     *
     * [account] files the rows against an account, which matters because
     * duplicate checking is per account: the same £40 at the same shop on the
     * same day can legitimately appear on two different cards. It is passed by
     * id rather than name, since names are only unique per person.
     */
    fun useDetectedStatement(account: AccountOption? = null) {
        val current = _state.value
        val sheet = current.sheet ?: return
        val detected = current.detectedStatement ?: return
        val mapping = if (account == null) {
            detected
        } else {
            detected.copy(defaultAccountName = account.name, defaultAccountId = account.id)
        }
        viewModelScope.launch {
            _state.value = current.copy(isBusy = true)
            _ownTargets.value = account?.let { importer.ownAccountTargets(it.id) }.orEmpty()
            val read = importer.buildCandidatesWithDuplicates(sheet, mapping)
            // On a set-aside account every movement is money moved to or from
            // savings, other than interest. Filed any other way, the £200
            // arriving in a saver was counted as £200 earned.
            val candidates = if (account?.holding == Holding.SET_ASIDE) {
                read.map { candidate ->
                    val isInterest = candidate.categoryName
                        ?.contains("interest", ignoreCase = true) == true ||
                        candidate.name.contains("interest", ignoreCase = true)
                    if (isInterest) candidate else candidate.copy(categoryName = SAVINGS_CATEGORY)
                }
            } else {
                read
            }
            _state.value = _state.value.copy(
                mapping = mapping,
                candidates = candidates,
                chosenAccount = account,
                // Filing a statement against the wrong account is silent and
                // expensive to undo, so it is checked while there is still a
                // review screen to say it on.
                accountFit = account?.let { importer.checkAccountFit(candidates, it.id) },
                usingDetectedLayout = false,
                step = ImportStep.REVIEW,
                isBusy = false,
            )
        }
    }

    /**
     * Remembers the account the importer was opened from.
     *
     * Kept in state rather than passed straight through, because the file has
     * not been chosen yet — the answer has to survive until the statement card
     * appears.
     */
    fun preselectAccount(accountId: Long?) {
        if (_state.value.preselectedAccountId == accountId) return
        _state.value = _state.value.copy(preselectedAccountId = accountId)
    }

    /**
     * Goes back to pick a different account, with the suggested one already
     * selected — the answer to "which one then?" is the whole point of having
     * asked, and making the user find it again would be a poor reward for
     * taking the advice.
     *
     * The warning itself is dropped on the way. It was about the account just
     * left behind, and leaving it up would make the next choice look condemned
     * before it had been checked.
     */
    fun chooseAnotherAccount() {
        val suggested = _state.value.accountFit?.suggestedAccountId
        _state.value = _state.value.copy(
            step = ImportStep.MAP,
            accountFit = null,
            chosenAccount = null,
            preselectedAccountId = suggested,
        )
    }

    /** Keeps the chosen account despite the warning, and says no more about it. */
    fun keepChosenAccount() {
        _state.value = _state.value.copy(accountFit = null)
    }

    /** Falls back to mapping the columns by hand. */
    fun mapByHand() {
        _state.value = _state.value.copy(usingDetectedLayout = false, step = ImportStep.MAP)
    }

    fun selectSheet(index: Int) {
        val workbook = _state.value.workbook ?: return
        val sheet = workbook.sheets.getOrNull(index) ?: return
        val target = _state.value.mapping?.target ?: ImportTarget.RECURRING_EXPENSE
        val newState = _state.value.copy(
            selectedSheetIndex = index,
            detectedLayout = importer.detectHouseholdLayout(sheet),
            detectedStatement = importer.detectStatement(sheet),
            usingDetectedLayout = false,
            mapping = importer.suggestMapping(sheet, target),
        )
        refreshCandidates(newState)
    }

    fun setTarget(target: ImportTarget) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(_state.value.copy(mapping = mapping.copy(target = target)))
    }

    fun setColumnRole(column: Int, role: ColumnRole) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(columnRoles = mapping.columnRoles + (column to role)),
            ),
        )
    }

    fun setHeaderRow(row: Int) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(
                    headerRow = row,
                    firstDataRow = (row + 1).coerceAtLeast(0),
                ),
            ),
        )
    }

    fun setRowRange(first: Int, last: Int) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(firstDataRow = first, lastDataRow = last),
            ),
        )
    }

    /**
     * Applies a single person or account to the whole block — the layout most
     * hand-built household spreadsheets use, where one column of figures
     * belongs to one person.
     */
    fun setDefaults(person: String?, account: String?, category: String?) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(
                    defaultPersonName = person?.takeIf { it.isNotBlank() },
                    defaultAccountName = account?.takeIf { it.isNotBlank() },
                    defaultCategoryName = category?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /**
     * Flips one row between money in and money out.
     *
     * The app cannot always tell. A statement gives a line of text, and where
     * neither the running balance nor a debit/credit letter says which way the
     * money went, an employer's name on a credit is indistinguishable from a
     * shop's name on a payment. Rather than guessing and being confidently
     * wrong across a whole file, the reading is shown and this changes it.
     */
    fun toggleDirection(id: String) {
        applyDirections { candidate ->
            if (candidate.id == id) candidate.flipped() else candidate
        }
    }

    /** Flips every row at once, for a file read the wrong way round throughout. */
    fun swapAllDirections() {
        applyDirections { it.flipped() }
    }

    private fun ImportCandidate.flipped(): ImportCandidate =
        if (target != ImportTarget.TRANSACTION) {
            this
        } else {
            // Null means "nothing said", which is read as money out, so its
            // opposite is money in.
            copy(
                transactionType = if (transactionType == TransactionType.INCOME) {
                    TransactionType.EXPENSE
                } else {
                    TransactionType.INCOME
                },
            )
        }

    /**
     * Applies a change of direction and then asks the ledger again.
     *
     * Duplicate checking and the corrections both key on which way the money
     * went, so every verdict about these rows is stale the moment one is
     * flipped.
     */
    private fun applyDirections(change: (ImportCandidate) -> ImportCandidate) {
        val current = _state.value
        val mapping = current.mapping ?: return
        val changed = current.candidates.map(change)
        _state.value = current.copy(candidates = changed)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                candidates = importer.refreshAgainstLedger(changed, mapping),
            )
        }
    }

    fun toggleCandidate(id: String) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map { candidate ->
                if (candidate.id == id) {
                    candidate.copy(isSelected = !candidate.isSelected)
                } else {
                    candidate
                }
            },
        )
    }

    fun selectAll(selected: Boolean) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map {
                // Rows already in the ledger stay unticked. Ticking them makes
                // no difference to what is written — they are skipped either
                // way — but it makes the count above the list promise to add
                // rows it is not going to add.
                if (it.isImportable && !it.isAlreadyPresent) {
                    it.copy(isSelected = selected)
                } else {
                    it
                }
            },
        )
    }

    fun goToReview() {
        _state.value = _state.value.copy(step = ImportStep.REVIEW)
    }

    fun goToMapping() {
        _state.value = _state.value.copy(step = ImportStep.MAP, usingDetectedLayout = false)
        // Rebuild from the hand-made mapping, discarding any auto-detected set.
        refreshCandidates(_state.value)
    }

    /** Step 3: write the selected rows. */
    fun applyImport() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            when (val result = importer.applyCandidates(_state.value.candidates)) {
                is AppResult.Success -> {
                    val current = _state.value
                    val account = current.chosenAccount
                    val before = account?.let { accountRepository.get(it.id) }
                    // A statement brings the account's balance up to date
                    // with the bank's own figure, and shows its bills.
                    val balanceNote = if (account != null && current.canImportStatement) {
                        val check = current.check
                        accountRepository.updateFromStatement(
                            accountId = account.id,
                            closingBalanceMinor = check.endBalanceMinor.takeIf { check.balancesSeen > 0 },
                            lastDate = check.lastDate,
                            firstDate = check.firstDate,
                        )
                    } else {
                        null
                    }
                    // Kept as a record of what came in, so it can be listed
                    // on the account and taken back out in one go.
                    val batchId = if (account != null &&
                        (result.data.createdTransactionIds.isNotEmpty() || result.data.transactionsUpdated > 0)
                    ) {
                        val after = accountRepository.get(account.id)
                        val check = current.check
                        runCatching {
                            importHistory.record(
                                accountId = account.id,
                                fileName = current.workbook?.fileName ?: "Statement",
                                addedIds = result.data.createdTransactionIds,
                                rowsUpdated = result.data.transactionsUpdated,
                                firstDate = check.firstDate,
                                lastDate = check.lastDate,
                                balanceBefore = before?.let { it.openingBalanceMinor to it.openingBalanceDate },
                                balanceAfter = after?.let { it.openingBalanceMinor to it.openingBalanceDate },
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    val bills = if (account != null) billFinder.find(account.id) else emptyList()
                    _state.value = current.copy(
                        isBusy = false,
                        step = ImportStep.DONE,
                        importBatchId = batchId,
                        undoneNote = null,
                        outcome = result.data,
                        balanceNote = balanceNote,
                        foundBills = bills,
                        chosenBills = bills.filter { it.isConfirmed }.map { it.name }.toSet(),
                    )
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isBusy = false,
                    error = result.message,
                )
            }
        }
    }

    /**
     * Adds the person named on the statement.
     *
     * Offered rather than done, because the name on a statement is not always
     * somebody who should appear in the app — post gets forwarded, and a joint
     * account carries two names.
     */
    fun addPersonFromStatement() {
        val name = _state.value.unknownOwnerName ?: return
        viewModelScope.launch {
            val tidied = name.split(' ')
                .drop(1)
                .filter { it.length > 1 }
                .joinToString(" ") { part ->
                    part.lowercase().replaceFirstChar { it.uppercase() }
                }
                .ifBlank { name }
            val result = peopleRepository.save(
                PersonEntity(
                    name = tidied,
                    colorHex = DefaultData.PALETTE.random(),
                ),
            )
            if (result is AppResult.Success) {
                peopleRepository.rememberStatementName(result.data, name)
            }
            _state.value = _state.value.copy(
                unknownOwnerName = null,
                statementOwnerName = tidied.takeIf { result is AppResult.Success },
                filingPersonName = tidied.takeIf { result is AppResult.Success },
                preselectedAccountId = null,
                error = (result as? AppResult.Failure)?.message,
            )
        }
    }

    /**
     * The person whose page the import was started from.
     *
     * Their name is checked against the one on the statement: if it is
     * somebody else's, the card says so and offers to file it under them.
     */
    fun expectPerson(personId: Long?) {
        if (personId == null || personId == _state.value.expectedPersonId) return
        viewModelScope.launch {
            val person = peopleRepository.get(personId) ?: return@launch
            _state.value = _state.value.copy(
                expectedPersonId = person.id,
                expectedPersonName = person.name,
            )
        }
    }

    /**
     * Files the statement under [personName], whoever it seemed to be for.
     *
     * When the statement carried a name the app did not recognise, that name
     * is remembered for this person, so their next statement is recognised
     * without asking.
     */
    fun fileUnder(personName: String) {
        val current = _state.value
        viewModelScope.launch {
            val person = peopleRepository.activePeople().firstOrNull { it.name == personName }
            val printed = current.printedName
            if (person != null && printed != null && current.statementOwnerName == null) {
                peopleRepository.rememberStatementName(person.id, printed)
            }
            _state.value = _state.value.copy(
                filingPersonName = personName,
                unknownOwnerName = if (person != null) null else current.unknownOwnerName,
                preselectedAccountId = null,
            )
        }
    }

    fun reset() {
        val before = _state.value
        _state.value = ImportState(
            expectedPersonId = before.expectedPersonId,
            expectedPersonName = before.expectedPersonName,
        )
    }

    /**
     * Several statements picked at once. The first is opened now; each of
     * the others is offered in turn from the finished screen.
     */
    fun openFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.value = _state.value.copy(queue = uris.drop(1), queueTotal = uris.size)
        openFile(uris.first())
    }

    /** Opens the next statement in the pile, filed against the same account as before. */
    fun openNext() {
        val current = _state.value
        val next = current.queue.firstOrNull() ?: return
        _state.value = current.copy(
            queue = current.queue.drop(1),
            preselectedAccountId = current.chosenAccount?.id ?: current.preselectedAccountId,
        )
        openFile(next)
    }

    /** Takes the import just finished back out; see ImportHistoryRepository.undo. */
    fun undoImport() {
        val batchId = _state.value.importBatchId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            when (val result = importHistory.undo(batchId)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    isBusy = false,
                    importBatchId = null,
                    undoneNote = "Import undone: ${result.data.removed} " +
                        (if (result.data.removed == 1) "payment" else "payments") + " taken out" +
                        (if (result.data.balanceRestored) ", and the balance put back as it was." else "."),
                )
                is AppResult.Failure -> _state.value = _state.value.copy(isBusy = false, error = result.message)
            }
        }
    }

    /**
     * Puts the raw text of the PDF on screen at any point, not only when the
     * reading failed outright.
     *
     * A statement that imports but reads wrongly is the harder case, and it
     * cannot be diagnosed without seeing what the lines actually look like.
     */
    fun showWhatWasRead() {
        val current = _state.value
        val uri = current.sourceUri ?: return
        viewModelScope.launch {
            // A PDF's own text; for a spreadsheet or CSV, its rows as read.
            val text = importer.readPdfText(uri)?.takeIf { it.isNotBlank() }
                ?: current.sheet?.let { sheet ->
                    (0 until sheet.rowCount).joinToString("\n") { row ->
                        (0 until sheet.columnCount).joinToString(" | ") { column -> sheet.cell(row, column) }
                    }
                }?.takeIf { it.isNotBlank() }
            _state.value = _state.value.copy(
                whatWasRead = text,
                error = if (text == null) "Nothing could be read from that file." else null,
            )
        }
    }

    fun closeWhatWasRead() {
        _state.value = _state.value.copy(whatWasRead = null)
    }

    fun clearUnreadablePdf() {
        _state.value = _state.value.copy(unreadablePdfText = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Recomputes the preview whenever the hand-made mapping changes. */
    private fun refreshCandidates(newState: ImportState) {
        val sheet = newState.sheet
        val mapping = newState.mapping
        _state.value = when {
            sheet == null || mapping == null ->
                newState.copy(isBusy = false, candidates = emptyList())
            // An auto-detected set spans several mappings; rebuilding it from
            // the single hand-made mapping would throw most of it away.
            newState.usingDetectedLayout ->
                newState.copy(
                    isBusy = false,
                    candidates = newState.detectedLayout
                        ?.let { importer.buildCandidatesForLayout(sheet, it) }
                        .orEmpty(),
                )
            else -> newState.copy(
                isBusy = false,
                candidates = importer.buildCandidates(sheet, mapping),
            )
        }
    }

    private companion object {
        /** The seeded savings category; see `DefaultData`. */
        const val SAVINGS_CATEGORY = "Savings"
    }
}

enum class ImportStep { CHOOSE_FILE, MAP, REVIEW, DONE }

data class ImportState(
    val step: ImportStep = ImportStep.CHOOSE_FILE,
    val workbook: WorkbookData? = null,
    val selectedSheetIndex: Int = 0,
    val mapping: ImportMapping? = null,
    /** Set when the sheet looks like a household budget the app can read whole. */
    val detectedLayout: DetectedLayout? = null,
    /** The file being imported, kept so its text can be shown on request. */
    val sourceUri: Uri? = null,
    /** Set when the sheet looks like a downloaded bank statement. */
    val detectedStatement: ImportMapping? = null,
    /** The account a statement is being filed against, once one is chosen. */
    val chosenAccount: AccountOption? = null,
    /** Set when the rows look like they belong to a different account. */
    val accountFit: AccountFitCheck.Verdict? = null,
    /** The account this import was started from, when it began on one. */
    val preselectedAccountId: Long? = null,

    /** What kind of account the statement is for, when its heading says. */
    val statementKind: StatementKind.Found? = null,

    /** Who the statement is addressed to, when the name on it settles it. */
    val statementOwnerName: String? = null,

    /** A name read off the statement that belongs to nobody in the app yet. */
    val unknownOwnerName: String? = null,

    /** The name as printed on the statement, whether or not anybody matched it. */
    val printedName: String? = null,

    /** What happened to the account's balance after the statement was imported. */
    val balanceNote: String? = null,

    /** Regular payments found after importing, offered as bills. */
    val foundBills: List<RecurringDetector.RegularPayment> = emptyList(),
    val chosenBills: Set<String> = emptySet(),
    val billsNote: String? = null,

    /** The person whose page this import was started from, if any. */
    val expectedPersonId: Long? = null,
    val expectedPersonName: String? = null,

    /** Who the user has said the statement is for, settling any doubt. */
    val filingPersonName: String? = null,
    /** Everything read from the file, shown full screen on request. */
    val whatWasRead: String? = null,
    /** Text pulled from a PDF whose layout was not recognised, for showing. */
    val unreadablePdfText: String? = null,
    val usingDetectedLayout: Boolean = false,
    val candidates: List<ImportCandidate> = emptyList(),
    val outcome: ImportOutcome? = null,
    /** The record of the import just finished, while it can still be undone. */
    val importBatchId: Long? = null,
    /** Said once an import has been undone. */
    val undoneNote: String? = null,
    /** Statements picked together that are still to be opened. */
    val queue: List<Uri> = emptyList(),
    /** How many were picked together, for "2 of 5". */
    val queueTotal: Int = 0,
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val sheet: SheetData?
        get() = workbook?.sheets?.getOrNull(selectedSheetIndex)

    /** How many rows the import will actually act on. */
    val selectedCount: Int
        get() = candidates.count { it.isSelected && it.isImportable && !it.isAlreadyPresent }
    val problemCount: Int get() = candidates.count { !it.isImportable }

    /** True when the sheet can be imported whole without any manual mapping. */
    val canAutoImport: Boolean get() = detectedLayout?.isUsable == true

    /**
     * Whose accounts the statement is offered for: what the user said, else
     * the name on the statement, else the person it was opened from.
     */
    val filingFor: String?
        get() = filingPersonName
            ?: statementOwnerName.takeIf { !ownerConflict }
            ?: expectedPersonName
            ?: statementOwnerName

    /**
     * True when the statement was opened from one person's page but the
     * name on it is somebody else's — worth saying before it is filed.
     */
    val ownerConflict: Boolean
        get() = filingPersonName == null && expectedPersonName != null &&
            statementOwnerName != null && statementOwnerName != expectedPersonName

    /** True when the name on the statement belongs to nobody, and nobody has been picked. */
    val ownerUnknown: Boolean
        get() = filingPersonName == null && unknownOwnerName != null

    /** True when the sheet is a bank statement and can be read as it stands. */
    val canImportStatement: Boolean get() = detectedStatement != null

    /**
     * Whether the whole statement was read: its rows checked against its own
     * running balance. See [StatementCheck].
     */
    val check: StatementCheck get() = StatementCheck.of(candidates)

    /** How many rows move money between the person's own accounts. */
    val moveCount: Int
        get() = candidates.count { it.isSelected && it.transferAccountId != null && !it.isAlreadyPresent }

    /** How many rows the import would skip because they are already recorded. */
    val alreadyPresentCount: Int get() = candidates.count { it.isAlreadyPresent }

    /** How many rows will correct an entry already held rather than add one. */
    val correctionCount: Int
        get() = candidates.count { it.isSelected && it.isImportable && it.corrects != null }

    /** How many rows will become new entries, which is not all the selected ones. */
    val additionCount: Int get() = selectedCount - correctionCount

    private val transactions: List<ImportCandidate>
        get() = candidates.filter { it.isImportable && it.target == ImportTarget.TRANSACTION }

    /** How the rows are being read, which is the thing most worth checking. */
    val moneyInCount: Int get() = transactions.count { it.transactionType == TransactionType.INCOME }
    val moneyOutCount: Int get() = transactions.size - moneyInCount
}
