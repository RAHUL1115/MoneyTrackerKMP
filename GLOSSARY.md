# MoneyTracker

A personal money tracker that runs on one phone: people record their own spending, income and transfers, see where the money went, and plan with budgets. Nothing is sent to a server.

## Entries

**Entry**:
One recorded movement of money on a date: an income, an expense, a refund, a transfer or a balance adjustment.
_Avoid_: Transaction, record, txn (in user-facing text)

**Income**:
An entry that brings money into an account from outside, filed under an income category.
_Avoid_: Credit, earning

**Expense**:
An entry that takes money out of an account to the outside, filed under an expense category.
_Avoid_: Debit, spend, payment

**Refund**:
Money coming back for an earlier purchase, filed under the expense category it reduces rather than as income.
_Avoid_: Return, reversal, cashback

**Transfer**:
Money moved between two of the person's own accounts; it changes balances but is neither income nor expense.
_Avoid_: Move, internal transaction

**Transfer fee**:
The charge for making a transfer, kept as an expense that belongs to that transfer.
_Avoid_: Charge, commission

**Balance adjustment**:
An entry that corrects an account's balance to what the person says it really is, without counting as income or expense.
_Avoid_: Correction, modified balance, reconciliation

**Installment plan**:
One credit-card purchase split into equal monthly parts, each part an entry of its own.
_Avoid_: EMI, split payment

**Note**:
The short text that names an entry, such as "Lunch" or a shop name.
_Avoid_: Title, description, payee

**Description**:
Optional longer text on an entry, under its note.
_Avoid_: Memo, comment, details

**Tag**:
A free label (written `#tag`) that groups entries across categories and accounts; one entry can carry several.
_Avoid_: Label, hashtag

**Repeat**:
A rule that adds the same entry on a schedule (every month, every week, end of month and so on) until it ends.
_Avoid_: Recurring transaction, subscription, standing order

**Bookmark**:
A saved entry that pre-fills a new one in a tap; with no amount, the amount is asked each time.
_Avoid_: Template, favourite, preset

## Accounts

**Account**:
A place money is kept or owed: cash, a bank account, a card, a loan, an investment.
_Avoid_: Wallet, asset (as a general word)

**Account group**:
A named set of accounts of one type (Cash, Bank, Credit card, Loan and so on) that decides how they behave.
_Avoid_: Account type, folder

**Liability**:
An account whose balance is money owed rather than money held: a credit card, a loan, an overdraft.
_Avoid_: Debt account, negative account

**Hidden account**:
An account kept out of lists and pickers whose entries still count in history and reports.
_Avoid_: Archived account, closed account

**Counts as expense**:
An account flag (for savings or investments) under which money moved into the account is reported as spending, and money moved back out as income.
_Avoid_: Savings mode

**Main currency**:
The currency all reports and totals are shown in.
_Avoid_: Home currency, base currency, default currency

**Rate**:
How many units of the main currency one unit of another currency is worth; each entry keeps the rate of the day it was made.
_Avoid_: Exchange rate (fine in prose), conversion factor

## Cards

**Credit card**:
A liability account whose spending is billed in cycles and paid from another account.
_Avoid_: Card (alone)

**Debit card**:
A card that draws straight from a linked bank account; its spending shows in that bank account's balance.
_Avoid_: ATM card

**Statement day**:
The day of the month a credit card's billing cycle starts.
_Avoid_: Settlement day, billing date, closing date

**Cycle**:
The stretch of days from one statement day to the next.
_Avoid_: Billing period, statement period

**Payable**:
What is owed on a credit card from finished cycles, oldest first; payments settle the oldest charges first.
_Avoid_: Outstanding, due amount

**Usage target**:
A monthly spending amount a credit card should reach (for example to waive its fee), compared with the current cycle.
_Avoid_: Card limit, spending goal

**Card limit**:
The most a credit card lets the person owe.
_Avoid_: Usage target, credit line

## Time and reports

**Month**:
The reporting month, which starts on the person's chosen start day; with a start day of 20 or later it takes the name of the month it ends in.
_Avoid_: Calendar month (unless that is what is meant), period, cycle

**Start day**:
The day of the month a reporting month begins, usually payday.
_Avoid_: Salary day, month start date

**Weekend shift**:
Whether a month that would start on a weekend starts on the Friday before or the Monday after instead.
_Avoid_: Holiday rule

**Carry-over**:
Showing the balance left from earlier months as the opening amount of the current one.
_Avoid_: Rollover, opening balance

**Budget**:
A monthly spending limit for one main category, or for everything together, that applies from a chosen month onward.
_Avoid_: Limit, cap, envelope

## Categories

**Category**:
What an income or expense was for, such as Food or Salary; each is for income or for expense, never both.
_Avoid_: Type, head

**Main category**:
A top-level category; budgets and the main reports work on these.
_Avoid_: Parent category, group

**Subcategory**:
A category inside a main category, one level deep only.
_Avoid_: Child category, sub-head

## Bank SMS

**Bank SMS**:
A text message from a bank or card issuer that reports money in or out of the person's account.
_Avoid_: Transaction SMS, alert

**Detected payment**:
A bank SMS the app has read and turned into a proposed entry, waiting until it is added or dismissed.
_Avoid_: Capture, pending transaction, suggestion

**Blocked sender**:
A sender whose messages are never read for payments.
_Avoid_: Ignored sender, spam sender

**Blocked phrase**:
Words that, when found in a message, mean it is not a payment and is skipped.
_Avoid_: Ignore phrase

**Content filter**:
Words taken out of a message before it is read, such as a recurring promotional line.
_Avoid_: Cleanup rule

**Account rule**:
Text set on an account so that messages containing it go to that account.
_Avoid_: SMS match, mapping

## Entering

**Smart entry**:
Typing or saying an entry in plain words ("1,200 for new shoes") and getting a filled-in entry to check.
_Avoid_: Voice entry, AI entry, quick add

**Quick add**:
The persistent notification with a button that opens a new entry straight away.
_Avoid_: Smart entry, shortcut

## Around the app

**Layout**:
One of the two ways the app's screens are arranged, the new layout or the classic layout; the data is the same in both.
_Avoid_: Theme, skin, mode

**Backup**:
A single file holding all of a person's data, settings and passcode, which restores on any phone.
_Avoid_: Export, snapshot (except for the copy kept before a restore)

**Passcode**:
The code that locks the app; it travels with the backup.
_Avoid_: PIN, password (that word is for the web dashboard)

**Web dashboard**:
A page the phone serves to computers on the same Wi-Fi, for viewing and editing entries on a bigger screen.
_Avoid_: Web app, cloud, sync

**Notes**:
Free-text notes the person keeps in the app, separate from any entry.
_Avoid_: Memos, note (that is an entry's text)
