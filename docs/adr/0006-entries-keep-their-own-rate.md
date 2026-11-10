# Entries in another currency keep the rate of their own day

An entry made in a currency other than the main one stores three amounts: what was typed in its own currency, the amount in the main currency (used by reports), and the amount in the account's currency (used by balances). It also stores the rate used. Rates are refreshed daily, but old entries keep theirs, so last year's totals don't move when today's rate changes, and editing an entry keeps its rate for as long as its currency stays the same.
