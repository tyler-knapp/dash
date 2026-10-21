# Personal Finance — Build Spec

A single-user personal finance app for Tyler. It pulls transactions and balances from his banks through Plaid, categorizes every transaction, tracks monthly budgets by category, and shows net worth.

A working UI prototype exists at `reference/prototype.html`. It is the source of truth for look, feel and period/budget logic. Port it; don't redesign it.

---

## 1. Goals and non-goals

**Goals**
- One ledger of every transaction across all linked accounts, showing which account each came from.
- Every transaction tagged with a category and subcategory, automatically, with manual override.
- Monthly budgets per category. Subcategory spending rolls up to the category total.
- Period views: Month, Quarter, YTD, Year, Custom month range.
- Net worth from current balances of all accounts, including retirement.
- Data refreshes on its own (scheduled sync) without Tyler doing anything.

**Non-goals (v1)**
- Multi-user, sharing, or household accounts.
- Investment trade history, cost basis, or performance. Retirement accounts are balance-only.
- Bill pay, money movement, or any write to a bank.
- Native mobile app. The web UI must work well at phone width (the prototype already does).

---

## 2. Accounts in scope

| Institution | Accounts | Role in app |
|---|---|---|
| Huntington | Checking, Voice Rewards credit card | Ledger |
| Fifth Third | Checking, Savings | Ledger |
| SoFi | Checking, Savings | Ledger |
| American Express | Credit card | Ledger |
| Capital One | Credit card | Ledger |
| Vanguard | Money market | Ledger (interest/dividends, transfers) |
| Vanguard | Roth IRA | Balance only |
| Empower | 401(k) | Balance only |
| Fidelity | 401(k), former employer | Balance only |

Each institution login is one Plaid **Item**. Expected total: 8 Items (Huntington and Vanguard each cover two account types under one login, assuming one login each). The Plaid Trial plan allows 10 production Items, and **removing an Item does not free its slot**. All development and testing happens in Sandbox; production linking happens once, in Phase 6.

Any account that will not link (workplace 401(k)s are the usual suspects) must be supported as a **manual account**: Tyler enters a balance and date by hand.

---

## 3. Architecture

```
frontend/   React + TypeScript (Vite). Port of reference/prototype.html.
backend/    Spring Boot 3, Java 21. REST API, Plaid client, sync scheduler, categorizer.
db          Postgres 16 (docker compose for local dev). Flyway migrations.
```

- The **Plaid secret and access tokens never reach the browser.** The frontend only ever sees a short-lived `link_token` and hands back a `public_token`.
- Access tokens are encrypted at rest (AES-GCM, key from env var `TOKEN_ENC_KEY`).
- Config through env vars: `PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV` (`sandbox` | `production`), `TOKEN_ENC_KEY`, `ANTHROPIC_API_KEY`, `APP_PASSWORD_HASH` (see Auth).
- Use the official `plaid-java` client and the official Anthropic Java SDK. Verify current versions before adding them; don't guess artifact coordinates.

**Auth.** Single user. Local dev may run without auth. Any deployment reachable from the internet must require login: one password (bcrypt hash in env) with a secure, HTTP-only session cookie, plus rate limiting on the login endpoint. Every `/api/**` route requires the session.

**Hosting (decide later, see Open questions).** Start local with docker compose. Design so the same containers deploy to a small host with HTTPS.

---

## 4. Data model

Money is stored as `NUMERIC(14,2)`. **App sign convention: negative = money out, positive = money in.** Plaid uses the opposite sign; flip it once, at ingest, and nowhere else.

```
item
  id (uuid), plaid_item_id, institution_id, institution_name,
  access_token_enc (bytea), sync_cursor (text, null), status (ok | login_required | error),
  last_synced_at, created_at

account
  id (uuid), item_id (null for manual), plaid_account_id (null for manual),
  name, official_name, mask, type, subtype,
  role (ledger | balance_only), display_color, is_manual (bool), hidden (bool)

transaction
  id (uuid), account_id, plaid_transaction_id (unique, null for manual),
  pending_plaid_transaction_id (null), date, authorized_date (null),
  merchant_name, raw_name, amount (app sign), pending (bool),
  category, subcategory,
  category_source (rule | plaid | ai | user),
  confidence (0..1), reviewed (bool),
  plaid_pfc_primary, plaid_pfc_detailed, plaid_pfc_confidence,
  created_at, updated_at

category            -- seeded, editable later
  name, kind (spend | income | transfer), sort_order
subcategory
  category_name, name, sort_order

budget_version      -- budget history, see 6
  id, effective_from (YYYY-MM, unique), amounts (jsonb: {category: number})

balance_snapshot    -- one row per account per day the balance is fetched or entered
  account_id, as_of (date), current, available (null), source (plaid | manual)
  unique (account_id, as_of)

category_rule       -- Phase 7, table can exist from day one
  id, priority, match_field (merchant | raw_name | account), match_type (exact | contains | regex),
  pattern, account_id (null), category, subcategory, created_from_txn_id (null)
```

**Seed taxonomy** (identical to the prototype):

| Category | Kind | Subcategories |
|---|---|---|
| Housing | spend | Rent, Electric, Internet, Renters Insurance |
| Food | spend | Groceries, Restaurants, Coffee |
| Transportation | spend | Gas, Car Insurance, Maintenance, Parking |
| Health & Fitness | spend | Gym, Race Fees, Running Gear, Supplements, Medical |
| Subscriptions | spend | Software & AI, Streaming, Research Tools |
| Lifestyle | spend | Clothing, Haircuts, Events, Travel, Gifts |
| Side Hustles | spend | Reselling Inventory, YouTube Gear |
| Income | income | Salary, Interest, Reselling Sales |
| Transfer | transfer | Card Payment, Account Transfer, Retirement Contribution |

Only `spend` categories count against budgets and toward "Spent". `transfer` is excluded from income and spending everywhere.

---

## 5. Plaid integration

**Linking (Plaid Link)**
1. `POST /api/plaid/link-token` → backend calls `/link/token/create` with products `transactions` (and `investments` for retirement institutions), returns `link_token`.
2. Frontend opens Plaid Link with it. On success, `POST /api/plaid/exchange` with `public_token`.
3. Backend calls `/item/public_token/exchange`, encrypts and stores the access token, fetches accounts, assigns each account a `role` (depository and credit → `ledger`; investment/retirement → `balance_only`), and triggers a first sync.
4. **Update mode** for broken logins: when an Item's status is `login_required`, the UI shows a "Reconnect" button that opens Link in update mode with a link token created for that Item. This re-authenticates the existing Item and uses no new slot.
5. Several of these banks use OAuth. Configure the Link redirect URI per Plaid's OAuth docs before Phase 6.

**Transaction sync** — use `/transactions/sync`, never `/transactions/get`.
- Per Item: call with the stored `sync_cursor` (omit on first call; request `days_requested: 730` on first link to get two years of history). Loop while `has_more`, then persist `next_cursor` **only after all pages are applied in one DB transaction**.
- `added`: insert, flip sign, categorize (section 7).
- `modified`: update fields from Plaid but **keep the category if `category_source = user` or `reviewed = true`**.
- `removed`: delete by `plaid_transaction_id`.
- Pending → posted: when an added transaction has `pending_transaction_id`, copy category, subcategory, category_source and reviewed from the pending row before it is removed, so a user's edit survives settlement.
- Request `personal_finance_category_version: v2` and store the PFC fields.

**Balances**
- After each sync, fetch balances for all accounts on the Item and upsert today's `balance_snapshot`.
- Retirement institutions: use the balance on the account object (or `/investments/holdings/get` totals if the account balance is missing). No holdings stored in v1.

**Schedule.** Sync all Items every 6 hours with `@Scheduled`, plus a "Sync now" button. Webhooks (`SYNC_UPDATES_AVAILABLE`) are optional and only possible once the backend has a public HTTPS URL.

**Errors.** Map `ITEM_LOGIN_REQUIRED` to item status `login_required` and surface it in the UI. Never crash the scheduler on one Item's failure. Log Plaid `request_id` on every error.

---

## 6. Budget and period logic

Port these behaviors exactly from `reference/prototype.html` (functions `periodMonths`, `budgetFor`, `elapsed`, `renderBudget`, `setBudget`).

- **Budget versions.** A version applies from its `effective_from` month until the next version. `budgetFor(month)` = amounts of the latest version with `effective_from <= month`.
- **Editing.** Budgets are editable only in Month view. Editing a category in month M upserts the version for M (copying the amounts in effect for M if no version exists for M yet) and changes only that category. Earlier months never change.
- **Period budget** = sum over the period's months of `budgetFor(month)[category]`.
- **Pace.** Expected-to-date = sum over months of `budget × elapsed(month)`, where elapsed is 1 for past months, day-of-month / days-in-month for the current month, 0 for future months. Bars show a pace marker.
- **Status pill per category:** Over by $X (spent > budget) · Ahead of pace (spent > expected × 1.1, period not finished) · On track · Under by $X / On budget (finished periods) · Not started (future periods).
- **Periods:** Month, Quarter, YTD (Jan through the current month for the current year, full year for past years), Year, Custom (from/to month). Prev/next arrows step by 1, 3 or 12 months and cannot go past the current period.
- All period math uses the user's time zone, America/New_York. A transaction's month is taken from its `date`.

Do this math on the backend (section 8 summary endpoint) and unit test it. The frontend may keep a copy for instant feedback after edits.

---

## 7. Categorization pipeline

Run on every newly added transaction, first match wins:

1. **User rules** (`category_rule`, by priority). `category_source = rule`, confidence 1.0. (Phase 7; skip the step until rules exist.)
2. **Transfer detection.** Plaid PFC primary `TRANSFER_IN`, `TRANSFER_OUT` or `LOAN_PAYMENTS` with detailed credit card payment → `Transfer` with the right subcategory. Also pair opposite-signed equal amounts between two of Tyler's own accounts within 3 days as transfers.
3. **Plaid PFC mapping.** A static map from PFC `detailed` to (category, subcategory) for clear cases (e.g. groceries, gas, restaurants, coffee, streaming, rent, utilities). Confidence from PFC: VERY_HIGH 0.95, HIGH 0.85, MEDIUM 0.65, LOW 0.4. Use it only when the mapped confidence ≥ 0.85.
4. **Claude API.** Everything else, in batches of up to 50: send merchant name, raw name, amount, account name, PFC fields, and the full taxonomy; ask for strict JSON `{id, category, subcategory, confidence}` choosing only from the taxonomy. Include up to 20 of Tyler's past manual corrections as examples. Validate the response against the taxonomy; on any invalid output, fall back to the PFC mapping with `confidence = 0.3`. `category_source = ai`.

- Anything with confidence < 0.7 and `reviewed = false` shows the **Review** tag and appears under "Needs review only", exactly like the prototype.
- A manual change sets `category_source = user`, `reviewed = true`, and (Phase 7) offers "Always categorize *merchant* this way" which creates a rule.
- Known ambiguous merchants from the sample data: Amazon, Target, Venmo, Kroger Fuel (gas, not groceries), Goodwill/Plato's Closet/Ticketmaster resale (reselling inventory). Seed these as few-shot examples.

---

## 8. REST API

All JSON, all under `/api`, all authenticated (except login).

```
POST   /api/auth/login                    {password} → sets session
POST   /api/auth/logout

POST   /api/plaid/link-token              {itemId?} → {linkToken}     (itemId = update mode)
POST   /api/plaid/exchange                {publicToken} → {item}
POST   /api/sync                          → sync all items now; returns per-item result
GET    /api/items                         → items with status, lastSyncedAt, accounts

GET    /api/accounts
POST   /api/accounts/manual               {name, type, role}
PATCH  /api/accounts/{id}                 {name?, displayColor?, hidden?}
POST   /api/accounts/{id}/balance         {asOf, current}             (manual accounts)

GET    /api/transactions?from=YYYY-MM&to=YYYY-MM&account=&category=&q=&needsReview=&includeTransfers=&cursor=&limit=
PATCH  /api/transactions/{id}             {category, subcategory}     → user override

GET    /api/categories                    → taxonomy
GET    /api/budgets                       → all budget versions
PUT    /api/budgets/{month}/{category}    {amount}                    → upsert per section 6

GET    /api/summary?from=YYYY-MM&to=YYYY-MM
       → {income, spent, budgeted, net,
          categories:[{name, spent, budget, expectedToDate, status, statusLabel,
                       subs:[{name, spent}]}]}
GET    /api/networth                      → latest balance per account, grouped; totals
GET    /api/networth/history?from=&to=    → monthly net worth series (Phase 7 chart)
```

Pagination: cursor-based, default 150 rows (the prototype's "Show more" step).

---

## 9. Frontend

Port `reference/prototype.html` to React + TypeScript components. Keep:

- **Design tokens** from its `:root` block, both dark-mode blocks, and the three Google Fonts (Schibsted Grotesk, Public Sans, IBM Plex Mono). Tabular numerals on all money.
- **Layout:** eyebrow "Personal Finance", period title + date span, Sample/Status badge, period bar (segmented control + arrows / custom selects), 4-cell summary strip, tabs Budget / Ledger / Net worth.
- **Budget tab:** category cards with status pill, spent-of-budget figures, editable budget input (Month view only), progress bar with pace marker, expandable subcategory rows, explanatory note. The row must wrap cleanly at 400px (name, pill, then figures on their own line).
- **Ledger tab:** search, account filter, category filter, Show transfers, Needs review only; rows grouped by day; merchant, account with color dot, category select, Auto / Review · auto NN% / Reviewed tag; signed, colored amounts; "Show more" paging.
- **Net worth tab:** total, cash minus cards, groups (Cash & savings, Credit cards, Retirement), manual accounts labeled with their last-updated date.

Add, which the prototype lacks:
- **Accounts screen:** linked institutions with status, last sync time, Reconnect (update mode), Sync now, Link new account, add manual account, enter manual balance.
- **Loading, empty and error states** for every view (the prototype's wording is a good starting point).
- Remember last tab and period in `localStorage`.

Replace the prototype's `claude.use("db")` calls entirely with the REST API. No Claude artifact runtime code in this app.

---

## 10. Build phases

Each phase ends with tests passing and a short note in `PROGRESS.md` saying what was verified and how. Don't mark a phase done on assumption.

**Phase 0 — Scaffold.** Monorepo, docker compose (Postgres + backend + frontend), Flyway baseline with the schema and seed taxonomy, env var config, health endpoint, CI-style `make test`.
*Done when:* `docker compose up` serves the frontend and `/api/health`.

**Phase 1 — Plaid Sandbox linking and sync.** Link token, exchange, encrypted token storage, accounts fetch, `/transactions/sync` loop with cursor, sign flip, balances snapshot, scheduler, update mode.
*Done when:* linking a Sandbox institution (`user_good` / `pass_good`) loads accounts and transactions; a second sync is incremental; integration tests cover added/modified/removed and pending → posted with a preserved user category.

**Phase 2 — Ledger UI.** Port the Ledger tab against the real API, including filters, paging and recategorize.
*Done when:* recategorizing persists across reload and matches the prototype's layout at desktop and 400px widths.

**Phase 3 — Budgets and periods.** Budget versions, `/api/summary`, period picker, Budget tab, summary strip.
*Done when:* unit tests prove: editing October doesn't change September; YTD budget sums the version in effect each month; pace and status labels match the prototype on the sample data in `reference/sample-data/`.

**Phase 4 — Categorization.** Transfer detection, PFC mapping, Claude API batch categorizer, Review flags.
*Done when:* Sandbox transactions all get a valid category; invalid model output falls back safely; card payments never count as spending.

**Phase 5 — Net worth and manual accounts.** Net worth tab, manual account + balance entry, Accounts screen.
*Done when:* totals equal the sum of latest snapshots; a manual account shows its updated date.

**Phase 6 — Production cutover.** Auth on, deploy (if hosting is chosen), `PLAID_ENV=production`, OAuth redirect configured, link the 8 real logins one at a time.
*Done when:* every account in section 2 appears with a balance (or is set up as manual), and the first real sync categorizes without errors. **Stop and ask Tyler before each production link.** Slots are not refundable.

**Phase 7 — Later.** Category rules and "always categorize this merchant", month-by-month trend chart per category, net worth history chart, budget vs. actual by month for the year, optional MCP server so Claude chat can answer questions about spending, optional webhooks.

---

## 11. Testing and safety rules

- Unit tests for period math, budget versions, sign flip, categorization fallbacks.
- Integration tests against Plaid Sandbox for sync (gate behind an env flag so CI without keys still passes).
- Never log access tokens, secrets, full account numbers, or the Anthropic key. Masks only.
- Never commit `.env`. Provide `.env.example`.
- Never call Plaid production from tests.

---

## 12. Open questions for Tyler

1. **Hosting:** local-only (laptop, docker compose) or a small cloud host so it works from a phone? Phone access requires hosting plus auth.
2. **Claude API spend:** OK to use the Anthropic API for categorization (low cost at ~50–60 transactions a month), or start with Plaid categories + manual review only?
3. **History depth:** pull the full 2 years on first link, or start from January 2026?
4. **Budget amounts:** the prototype's numbers are placeholders. Real starting budgets?
5. **Reselling:** keep inventory as a budgeted spend category, or move it to a separate profit-and-loss view later?
