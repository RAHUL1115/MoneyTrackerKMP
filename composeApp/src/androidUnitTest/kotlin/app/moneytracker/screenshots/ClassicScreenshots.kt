package app.moneytracker.screenshots

import app.moneytracker.app.Screen
import app.moneytracker.domain.CategoryKind
import org.junit.Test

/** The classic layout's main screens over the demo data. */
class ClassicScreenshots : ScreenshotTest(newLayout = false) {
    @Test fun transactionsDaily() { show(); shoot("transactions_daily") }
    @Test fun transactionsDailyDark() { show(dark = true); shoot("transactions_daily_dark") }
    @Test fun stats() { show(); tap("Stats"); shoot("stats") }
    @Test fun accounts() { show(); tap("Accounts"); shoot("accounts") }
    @Test fun more() { show(); tap("More"); shoot("more") }
    @Test fun editEntry() { show(Screen.EditTxn(txnId = entryId("Birthday gift"))); shoot("edit_entry") }
    @Test fun editIncome() { show(Screen.EditTxn(txnId = entryId("Interest"))); shoot("edit_income") }
    @Test fun editTransferFees() { show(Screen.EditTxn(txnId = entryId("Card bill"))); tap("Fees"); shoot("edit_transfer_fees") }
    @Test fun configuration() { show(Screen.Configuration); shoot("configuration") }
    @Test fun recurring() { show(Screen.Recurring); shoot("recurring") }
    @Test fun categories() { show(Screen.Categories(CategoryKind.EXPENSE)); shoot("categories") }
}
