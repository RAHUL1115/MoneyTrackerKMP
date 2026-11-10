# A refund is an expense with a negative amount

A refund is not income. It is stored as an expense in the category it reduces, with a negative amount, so every expense total, budget and category report nets it out without special cases, and the balance still goes up. This also matches how `.mmbak` backups store refunds, so imported refunds stay refunds. Code that reads amounts must not take `abs()` of an expense; `isRefund` names the case.

## Considered Options

- **A separate REFUND kind.** Rejected: every report and budget query would have to remember to subtract it.
- **Income in the same category.** Rejected: a category is for income or for expense, never both.
