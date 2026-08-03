# FlipAccounting

FlipAccounting records financial activity in user-managed books and derives planning views such as budgets from those records.

## Language

**Book**:
A stable owner of book-scoped financial data. Its ID is identity; its name is mutable display data.
_Avoid_: Ledger name, account book name

**All Books scope**:
The aggregate scope across every Book. It has reserved identity `0` for budgets and is not a real Book.
_Avoid_: All Books book

**Budget slot**:
The single budget for one Book or All Books scope, one month, and either one category or total spending.
_Avoid_: Budget row, budget item

**Total budget**:
The Budget slot for all expense categories within a scope and month.
_Avoid_: Uncategorized budget
