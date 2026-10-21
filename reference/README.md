# Reference files

- `prototype.html` — the approved UI, built as a Claude artifact. It reads and writes data through the artifact runtime (`claude.use("db")`), which does not exist in this repo. Replace those calls with the REST API from SPEC §8; keep everything else.
- `sample-data/2026-01.json` … `2026-10.json` — one file per month: `{month, sample, transactions:[{id, date, merchant, amount, account, category, sub, confidence, edited}]}`. Amounts use the app sign (negative = money out). `account` values are the prototype's short ids (hv, amex, c1, hchk, 53chk, 53sav, sofichk, sofisav, vgmm).
- `sample-data/budgets.json` — budget versions `{history:[{from:"YYYY-MM", amounts:{category: number}}]}`. Food changes in July, which is useful for testing that edits don't rewrite past months.
- `sample-data/balances.json` — latest balances, including one manual account.

All values are invented.
