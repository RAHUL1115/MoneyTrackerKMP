# Amounts are whole minor units, and the entry's kind gives the sign

Every amount is a `Long` count of the currency's smallest unit (paise, cents), never a floating-point number, so totals add up to the last paisa. The stored amount is positive, and the entry's kind (income, expense, transfer out or in, adjustment up or down) says whether it adds to the balance or takes away. The one exception is the refund ([0004](0004-refund-is-a-negative-expense.md)). Exchange rates are the only `Double` values, and an amount is rounded back to minor units once it has been converted.
