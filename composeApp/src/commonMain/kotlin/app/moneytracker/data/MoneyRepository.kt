package app.moneytracker.data

import app.moneytracker.data.moneymanager.MoneyManagerBridge
import app.moneytracker.data.moneymanager.SheetImport
import app.moneytracker.db.MoneyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The app's data, one store per area. Builds the stores over one shared [Db] and hands them out; the rules that
 * span areas live in [EntryWriter], [EntryEditor], [MessageIntake], [SheetImport], [MoneyManagerBridge] and [FreshStart].
 */
class MoneyRepository(
    database: MoneyDatabase,
    val io: CoroutineDispatcher,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) {
    private val db = Db(database, io, now, today)

    val settingsStore = SettingsStore(db)
    val currencyStore = CurrencyStore(db, settingsStore)
    val accountStore = AccountStore(db)
    val categoryStore = CategoryStore(db)
    val tagStore = TagStore(db)
    val photoStore = PhotoStore(db)
    val noteStore = NoteStore(db)
    val bookmarkStore = BookmarkStore(db)
    val captureStore = CaptureStore(db)
    val learningStore = LearningStore(db, settingsStore)
    val entryWriter = EntryWriter(db, settingsStore, currencyStore, accountStore, tagStore)
    val repeatStore = RepeatStore(db, settingsStore, entryWriter)
    val txnStore = TxnStore(db, settingsStore, currencyStore, accountStore, tagStore, entryWriter, repeatStore)
    val reportStore = ReportStore(db, settingsStore, accountStore)
    val messageIntake = MessageIntake(db, settingsStore, currencyStore, accountStore, categoryStore, learningStore, captureStore, txnStore)
    val sheetImport = SheetImport(db, settingsStore, categoryStore, tagStore, entryWriter)
    val moneyManagerBridge = MoneyManagerBridge(db, settingsStore, currencyStore, tagStore, learningStore, repeatStore, entryWriter, txnStore)
    val freshStart = FreshStart(db, settingsStore, tagStore)
    val entryEditor = EntryEditor(txnStore, photoStore, accountStore, learningStore, messageIntake, entryWriter)

    /** Finished views (accounts, budgets, month totals) for screens, the web dashboard and widgets. */
    val views: ReadModels by lazy {
        ReadModels(settingsStore, currencyStore, accountStore, categoryStore, tagStore, txnStore, reportStore, io, today)
    }
}
