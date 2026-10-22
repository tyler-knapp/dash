# Progress

One entry per phase (SPEC §10): what was built, what was verified, and how.

## Phase 0 — Scaffold ✅ (2026-10-21)

**Built**
- Monorepo: `backend/` (Spring Boot 3.5.16, Java 21, Gradle 8.14.3 wrapper), `frontend/` (React 19 + TypeScript, Vite 8), `docker-compose.yml` (Postgres 16 + backend + frontend), `Makefile`.
- Flyway `V1__schema.sql`: every table in SPEC §4 (`item`, `account`, `transaction`, `category`, `subcategory`, `budget_version`, `balance_snapshot`, `category_rule`). Money is `NUMERIC(14,2)`. Constraints: category/subcategory pairs must exist in the taxonomy (composite FK), manual accounts can't carry Plaid ids, `budget_version.effective_from` must be `YYYY-MM`, enum-like columns have CHECKs.
- Flyway `V2__seed_taxonomy.sql`: the 9 categories and 32 subcategories, same names and order as the prototype.
- Env config bound to `AppProperties` (`PLAID_CLIENT_ID`, `PLAID_SECRET`, `PLAID_ENV` = sandbox|production, `TOKEN_ENC_KEY`, `ANTHROPIC_API_KEY`, `APP_PASSWORD_HASH`). Startup fails on an unknown `PLAID_ENV`. `toString()` never prints secret values. `.env.example` provided; `.env` is gitignored.
- `GET /api/health` → `{status, db, plaidEnv, timeZone, config:{plaid, tokenEncKey, anthropic, auth}}`. Config fields are booleans only. Returns 503 if the DB is down.
- Frontend shell: prototype stylesheet ported verbatim (`src/styles/app.css`, tokens + both dark-mode blocks), the three Google Fonts, a typed fetch client, and a status page with loading / ok / error states. nginx serves the build and proxies `/api` to the backend (same origin, ready for the session cookie in Phase 6).
- `make test` = backend tests + frontend lint, typecheck, tests, production build. GitHub Actions runs `make test` on push and PR.

**Verified**
- `./gradlew cleanTest test`: 10/10 passed, against a real Postgres 16 via Testcontainers:
  - health endpoint returns 200, `db: ok`, `plaidEnv: sandbox`, config values are booleans only;
  - all 8 tables exist after migration; taxonomy matches the prototype's `CATS` exactly, in order; only Income is `income`, only Transfer is `transfer`;
  - DB rejects `Food › Gas`, a category without a subcategory, a manual account with a Plaid id, and budget month `2026-13`;
  - config binding rejects `PLAID_ENV=development`, accepts `production`, and secrets don't appear in `toString()`.
- Frontend: `npm run lint`, `npm run typecheck`, `npm test` (2/2: healthy and server-down states), `npm run build` all clean.
- `make test` ran end to end and passed.
- `docker compose up --build`: all three containers healthy; Flyway applied V1 and V2 on a fresh volume. `make smoke` passed: frontend HTML served on :8080, `/api/health` OK through nginx (:8080) and directly (:8081). Headless Chromium screenshots at 980px and 400px show the prototype fonts and colors and the status banner.

**Notes**
- The dev sandbox this was built in intercepts HTTPS, so image builds there needed an extra CA. Both Dockerfiles accept an optional build secret `extra_ca` (a PEM bundle) for that case; on a normal machine nothing is needed and the step does nothing.
- No budget versions are seeded: the prototype's amounts are placeholders (SPEC §12 Q4). Sample data loading for dev comes with Phases 2–3.
- Auth is not wired yet (Phase 6); `config.auth` in the health response just reports whether `APP_PASSWORD_HASH` is set.

## Phase 1 — Plaid Sandbox linking and sync 🟡 built and tested against a fake Plaid; live Sandbox check pending (2026-10-21)

**Built**
- `plaid-java` 47.0.0 (latest on Maven Central) behind a small `PlaidGateway` interface. The real client never logs request bodies. Every Plaid error is logged with `error_code` and `request_id` only.
- **Linking:** `POST /api/plaid/link-token` with `{itemId?, kind?}`. `kind` is `bank` (default: `transactions`, 730 days of history) or `retirement` (`investments`, for 401(k) providers that don't offer transactions). `itemId` = update mode: the Item's own access token and no products, so no new slot. `POST /api/plaid/exchange {publicToken}` exchanges, reads institution and products via `/item/get`, encrypts and stores the token, saves accounts with roles (depository/credit → ledger, everything else → balance only) and prototype colors, then runs the first sync.
- **Token encryption:** AES-256-GCM, random 12-byte IV per token, version byte and a fixed associated-data label. A malformed `TOKEN_ENC_KEY` stops startup; a missing one returns 503 when a token is needed.
- **Sync:** `/transactions/sync` from the stored cursor, 500 per page, with `personal_finance_category_version: v2` and `days_requested: 730`. All pages are fetched first, then accounts, transactions, the new cursor, balances and item status are written in **one DB transaction**. If any page fails, nothing is written and the old cursor stays. `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION` restarts from the saved cursor (up to 3 tries).
  - Sign flipped once, in `Money`: transactions negated; card and loan balances stored negative.
  - `added` inserted (idempotent on replay). `modified` updates Plaid fields only and never touches the category. `removed` deleted.
  - Pending → posted: the posted transaction copies category, subcategory, source, confidence and reviewed from the pending row before that row is removed. Works when they arrive on different pages of one sync.
  - Categorization is a no-op hook (`Categorizer`) until Phase 4. New transactions are uncategorized for now.
- **Balances:** read from `/accounts/get` (Plaid's cached balances) after each sync, upserted into today's `balance_snapshot` (New York date). `/accounts/balance/get` was not used because Plaid bills it per call. Retirement accounts with no balance fall back to summing holdings from `/investments/holdings/get`. The user's account name is never overwritten by a sync.
- **Errors:** `ITEM_LOGIN_REQUIRED` → item status `login_required`; other Plaid errors → `error`. `last_error_code` is stored for the UI. Status goes back to `ok` on the next successful sync.
- **Schedule:** every 6 hours at 00/06/12/18 New York time (`SYNC_CRON`), plus `POST /api/sync` ("Sync now"), with per-Item results. One Item failing, even with an unexpected exception, never stops the others or the scheduler. A per-Item lock prevents overlapping syncs. After a new link, follow-up syncs run at 15s, 30s, 1m, 2m, 4m, 8m and 15m until Plaid reports `HISTORICAL_UPDATE_COMPLETE`, since there are no webhooks.
- `GET /api/items`: items with status, last sync, history status, last error code and accounts with latest balance. No tokens or cursors.
- Migration `V3__item_sync_state.sql`: `item.products`, `item.transactions_update_status`, `item.last_error_code`.
- **Frontend:** a basic Accounts panel (Phase 5 polishes it): Link bank or card, Link retirement account, Sync now, Reconnect (update mode, then sync), status pills, last sync, accounts with signed balances, loading/empty/error states. Uses `react-plaid-link` 5.0.0.

**Verified**
- `./gradlew test`: **37 passed, 1 skipped** (the live Sandbox test; it needs keys). Against real Postgres 16, with Plaid faked:
  - linking through the HTTP API loads accounts, transactions and balances; amounts are in app sign (purchase −118.42, paycheck +3412.55, card balance −412.66); no access token in API responses;
  - the access token in the DB is ciphertext;
  - the second sync sends the stored cursor and adds only new rows;
  - modified updates amount, date and merchant but keeps a user category;
  - removed deletes;
  - pending → posted keeps the user's category, in one page and across pages;
  - a failure on page 2 writes nothing and keeps the old cursor; the next run replays and recovers;
  - mutation during pagination restarts from the saved cursor;
  - `ITEM_LOGIN_REQUIRED` marks that Item and the others still sync; update-mode link token uses that Item's token with no products; status returns to `ok` after a sync;
  - retirement Items skip `/transactions/sync` and use holdings when the balance is missing;
  - balances upsert once per day; a user-renamed account keeps its name;
  - follow-up syncs run until history is complete, then stop;
  - a corrupt Item doesn't stop the scheduled run;
  - plus unit tests for encryption (round trip, fresh IV, tamper and wrong-key detection, key validation), sign flips and Plaid model mapping.
- Frontend: lint, typecheck, **8/8 tests** (empty state; list with signed balances; full link flow: link token → Link → exchange → refresh; Reconnect via update mode then sync, no exchange; Sync now failure message; Plaid-not-configured message), production build.
- `docker compose up --build` + `make smoke` pass. With no Plaid keys, `/api/plaid/link-token` returns a clear 503. Screenshot at 400px with hand-inserted rows shows the Accounts panel, Connected and Needs reconnect states.

**Not yet verified (blocks marking Phase 1 done)**
- The "Done when" checks against the **real** Plaid Sandbox (link with `user_good`/`pass_good`, incremental second sync) have **not run**. This dev environment has no Plaid keys and its network policy blocks `sandbox.plaid.com`. `PlaidSandboxIT` covers exactly this (link via `/sandbox/public_token/create`, sync until history completes, sign check against Plaid's raw amount, incremental second sync, reset login → `login_required` → update-mode token). It runs with the command in CLAUDE.md once keys and network access exist.
- Plaid Link in a browser (the iframe) can't be tested here for the same reason.

**Open items for later phases**
- Vanguard's money market may come through as an `investment` account, which Plaid's Transactions product doesn't cover. If so, it becomes balance-only rather than a ledger account, or needs `/investments/transactions/get`. Check during Phase 6 linking.

## Phase 2 — Ledger UI ✅ (2026-10-21)

Built on the kit's sample data. Phase 1's live Plaid Sandbox check is still open (see above): this environment still has no Plaid keys and can't reach `sandbox.plaid.com`. Tyler chose to go ahead with Phase 2 anyway, since the Ledger reads from the database, not from Plaid.

**Built**
- **Sample data for dev:** `SAMPLE_DATA=true` (the default in docker compose) loads `reference/sample-data` on startup: 12 prototype accounts with their colors, 518 transactions, 2 budget versions, balances. Rows are tagged `is_sample` (migration V4). It never loads while a real account exists, and it is deleted automatically when the first real institution is linked. The files are read in place (mounted read-only into the container), not copied.
- `GET /api/transactions?from&to&account&category&q&needsReview&includeTransfers&cursor&limit`, with the prototype's filter rules:
  - transfers hidden unless "Show transfers" is on or the Transfer category is picked;
  - needs review = uncategorized, or confidence < 0.7 and not reviewed;
  - search is case-insensitive over merchant and raw name, with `%` and `_` taken literally;
  - hidden accounts are left out.
- Paging is cursor-based (keyset on date, then arrival order), 150 rows by default, max 500, and returns `total` for the "Show N more of M remaining" button. Rows within a day keep the order they arrived, which matches the prototype (migration V5 adds `transaction.seq`).
- `PATCH /api/transactions/{id} {category, subcategory}`: validated against the taxonomy (400 if not in it, 404 if the transaction doesn't exist); sets `category_source = user`, `reviewed = true`.
- `GET /api/categories` (taxonomy in order). `GET /api/accounts` (every account with latest balance, institution and sample flag; Phase 5 extends it).
- **Frontend:**
  - Prototype layout: eyebrow, period title, span with count ("Oct 1 – Oct 21 (today) · 48 transactions"), Sample data badge, period bar (Month/Quarter/YTD/Year/Custom, arrows that stop at the current period, custom from/to), tabs Budget / Ledger / Net worth / Accounts. Budget and Net worth are placeholders until Phases 3 and 5.
  - Period logic ported function by function from the prototype, with "today" in New York time.
  - Ledger tab: search (debounced), account and category filters, Show transfers, Needs review only; rows grouped by day with merchant, account dot, category dropdown, Auto / Review · auto NN% / Reviewed tag (plus "Review · uncategorized" and "Pending" for real Plaid data), signed and colored amounts, gray transfers; "Show more" paging.
  - Recategorizing updates the row at once and saves. If the save fails it reverts and shows the prototype's toast. A row that no longer fits the filters (e.g. fixed while "Needs review only" is on) drops out, as in the prototype.
  - Loading, empty and error states use the prototype's wording, plus a Retry button.
  - Last tab and period are remembered in `localStorage` (prototype key `pf-view`). Bad or future saved values fall back to defaults.
  - A health banner appears only when the server or database is down.

**Verified**
- Backend `./gradlew test`: **51 passed, 1 skipped** (the live Plaid test). *(Corrected in Phase 3: this line first said 52 passed, but 52 was the total including the skipped test.)* Ledger tests check API results against counts computed independently from the sample JSON files with the prototype's rules:
  - month view total, transfers toggle and Transfer category, needs review (42 low-confidence unedited rows minus transfers), search, account and category filters;
  - paging walks every row exactly once, newest first, in ⌈total/150⌉ pages;
  - a day's rows keep file order;
  - recategorize persists as user and reviewed and drops out of needs-review; bad pairs and unknown ids are rejected;
  - hidden accounts excluded; month validation; reversed range accepted;
  - sample data loads once, refuses to load next to real accounts, clears completely, and is removed when a real institution is linked.
- Frontend: lint, typecheck, **27/27 tests** (period logic incl. New York "today", the ledger behaviors above, shell: title/span/badge, stepping, custom range, remembering tab and period across reloads, server-down banner), production build. `make test` passes.
- **Done-when checks, in headless Chromium against `docker compose up` with sample data:**
  - Recategorized the first October row (Polaris Grill, Food › Restaurants → Side Hustles › YouTube Gear): PATCH 200, then **after a page reload the row still showed the new category with the Reviewed tag**, and the Ledger tab was still selected.
  - Ledger next to the prototype (fed the same sample data) at **980px and 400px**: same header, span and count (48), same filters, day headers, row layout, tags, amounts and row order. The only difference is the row recategorized on purpose. No horizontal scroll at either width. Side-by-side images: `docs/screenshots/phase2-ledger-vs-prototype-desktop.png` and `…-400px.png` (app on the left).

**Notes**
- The prototype shows its Budget list and custom-range selects at all times because inline `display` styles override its `hidden` attributes. That's a prototype bug (its tab code clearly means to hide them), so the app doesn't copy it.
- The summary strip (Income / Spent / Budgeted / Net) comes with `/api/summary` in Phase 3.

## Phase 3 — Budgets and periods ✅ (2026-10-21)

Built on sample data. Phase 1's live Plaid check is still open; Tyler expects to provide Sandbox keys this weekend.

**Built**
- `BudgetHistory`: the prototype's `budgetFor` / `setBudget` as a small immutable class. A version applies from its month until the next. Editing month M updates M's version, or creates it by copying the amounts in effect for M, and changes only that category.
- `Pace`: the prototype's `elapsed` and `renderBudget` rules, in the same order: Over by $X (spent > budget + half a cent) → finished periods: On budget (< $1 left) / Under by $X → Not started (future) → Ahead of pace (spent > expected × 1.1 and > 0) → On track. Dollar labels rounded half up with separators, like `toLocaleString`.
- `GET /api/summary?from&to` → `{income, spent, budgeted, net, transactionCount, uncategorizedCount, periodState, months, today, categories:[{name, spent, budget, expectedToDate, status, statusLabel, subs}]}`. Only spend categories count against budgets and toward Spent; Transfer counts nowhere; uncategorized transactions are counted but not spent; hidden accounts are left out (same as the ledger). "Today" is the New York date from an injected clock.
- `GET /api/budgets`. `PUT /api/budgets/{month}/{category} {amount}`: spend categories only, amount 0–99,999,999.99 with at most 2 decimals; edits are serialized with a DB lock. A version Tyler edits stops being sample data, so it survives the first real Plaid link.
- **Frontend:**
  - Summary strip (Income / Spent / Budgeted / Net cash flow, net green or red).
  - Budget tab: category cards with status pill, spent-of-budget figures, a progress bar colored by status, the pace marker (current periods only), expandable subcategories, and the prototype's two notes.
  - The budget input appears in Month view only; it saves on Enter or when you leave the field, and snaps back to the saved value if the save fails.
  - While a newly picked period loads, the old numbers stay on screen but can't be edited.
  - The app opens on Budget, as the prototype does. The header's transaction count now comes from the summary. Recategorizing in the Ledger refreshes the budgets.
- `scripts/prototype-oracle/`: runs the prototype itself in headless Chromium on the sample data, with the clock frozen at 2026-10-21 (New York), and records what it shows for 11 periods: statuses, spent, budgets, bar widths, pace markers, subcategory totals, summary strip, header count. The recording is checked in as a test fixture.

**Verified**
- Backend `./gradlew test`: **77 passed, 1 skipped** (the live Plaid test). New for Phase 3:
  - `BudgetHistoryTest`, pure unit tests on the sample budgets:
    - **editing October doesn't change September** (or July), and October's new version copies the other six amounts;
    - editing a month with a version changes only that category;
    - editing August after October leaves October's version alone;
    - **YTD budget sums the version in effect each month** (Food 6×700 + 4×750 = 7,200; Health & Fitness 6×250 + 4×275 = 2,600).
  - `PaceTest`: elapsed, period state, every status label including edge cases (within half a cent, under by less than $1, over in a future period), dollar rounding.
  - `BudgetMatchesPrototypeTest`: for **all 11 recorded periods** (current, past and July months; past and current quarter; YTD; year; empty 2025; two custom ranges, one entered backwards), `/api/summary` reproduces **exactly** what the prototype shows: strip figures, header count, every category's status class and label, spent, budget, bar width, pace marker position (present only when it should be) and subcategory totals. To check the test has teeth, I changed the "ahead of pace" factor from 1.1 to 1.2; the test failed on the two current periods, then I reverted it.
  - `BudgetApiTest`: editing October via the API leaves September and June alone; a second edit reuses October's version; edited versions survive clearing sample data; bad month, non-spend category, unknown category, negative or 3-decimal amounts and missing body are rejected with nothing changed; "Health & Fitness" works in the URL; hidden accounts drop out of Spent; future periods say Not started; uncategorized rows count toward the transaction count but not Spent.
- Frontend: lint, typecheck, **33/33 tests** (Budget tab: pills, figures, bar width and pace position matching the prototype's numbers, expand, edit commits once on Enter/blur for the right month and skips unchanged values, read-only multi-month view with its note and no pace marker, loading/error; summary strip formatting and colors; shell opens on Budget and shows the strip), production build. `make test` passes.
- **In headless Chromium** (backend and production frontend bundle run on the host because Docker Hub rate-limited image builds here; see notes):
  - Changed October's Food budget 750 → 900 in the Budget tab: PUT 200, Budgeted went $4,105 → $4,255, **still 900 after a page reload**, and **September still showed 750** with Budgeted $4,105.
  - Compared with the prototype on the same data and day, at **980px and 400px**: Month, Quarter, YTD and Year views are **identical**: title, all four strip figures, and every category's status, spent and budget.
  - **At 400px every category row wraps the prototype's way** (name and pill on one line, figures on their own line below), with no horizontal scroll. Side-by-side images: `docs/screenshots/phase3-budget-vs-prototype-desktop.png` and `…-400px.png` (app on the left).

**Notes**
- `docker compose up --build` couldn't be re-run at the end of this phase: Docker Hub answered 429 Too Many Requests when this sandbox tried to pull the JRE and Node base images. No Dockerfile or compose change was made in Phase 3, and CI (`make test`) doesn't use those images.
- Uncategorized transactions (real Plaid data before Phase 4) are left out of Spent; the summary reports how many there are as `uncategorizedCount` for later use in the UI.

## Phase 4 — Categorization 🟡 built and tested end to end with Plaid and Claude faked; live checks pending keys (2026-10-22)

**Built** (SPEC §7, first match wins; runs on every newly added transaction during sync)
1. **User rules** from `category_rule` by priority (exact / contains / regex on merchant, raw name or account) → source `rule`, confidence 1.0. The table is empty until Phase 7 adds a way to create rules.
2. **Transfers.**
   - Plaid's credit card payment and transfer categories → source `plaid`, Plaid's confidence.
   - Card-payment wording on either side: "Payment received – thank you" landing on a card; "Amex payment" or "CREDIT CARD 3333 PAYMENT" leaving checking, recognized by card words or the names of Tyler's own linked cards.
   - Person-to-person apps (Venmo, Zelle, Cash App, PayPal) are deliberately *not* treated as transfers; the spec lists Venmo as ambiguous.
   - **Pairing:** an equal and opposite amount on another of Tyler's own ledger accounts within 3 days → both sides Transfer (Card Payment if a card is involved, else Account Transfer), source `rule`, confidence 0.9. The other side can be in the same sync or already stored; a stored side is updated unless Tyler set or reviewed it. Guards: under $1 never pairs, and a side Plaid confidently calls a purchase or income doesn't pair.
3. **Plaid category map** (`PfcMapper`) for clear cases (groceries, coffee, restaurants, gas, parking, car maintenance, rent, electric, internet, streaming, gym, medical, clothing, haircuts, travel, events, gifts, salary, interest), used only when Plaid's confidence is ≥ 0.85 (VERY_HIGH 0.95, HIGH 0.85, MEDIUM 0.65, LOW 0.4).
4. **Claude** (`ClaudeCategorizer`, official `com.anthropic:anthropic-java` **3.1.0**, API version 2026-10-21, model `claude-opus-5-5`, low effort, structured JSON output) for everything else, in batches of up to 50.
   - The prompt has the full taxonomy, the rules (card payments are never spending, refunds go in the purchase's category, low confidence for ambiguous merchants), seeded examples for the spec's ambiguous merchants (Amazon, Target, Venmo, Kroger Fuel, Goodwill, Plato's Closet, Ticketmaster vs Ticketmaster resale), and up to 20 of Tyler's latest manual corrections.
   - Transactions are sent with short ids (`t1…`), never Plaid ids.
   - Every answer is validated: id known and answered once, category/subcategory pair in the taxonomy, confidence 0–1. Anything invalid, missing or duplicated, a refused or cut-off reply, or a failed call **falls back to the Plaid map at confidence 0.3**, so it shows as Review.
   - Server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`) is on by default; turn it off with `AI_SERVER_FALLBACKS=false`.
- **Without `ANTHROPIC_API_KEY`**, step 4 is skipped: unclear transactions get the Plaid map's guess at low confidence (≤ 0.65) and show as Review. If Plaid sent no category at all, the transaction stays uncategorized and shows "Review · uncategorized".
- `POST /api/transactions/categorize`: re-runs the pipeline on uncategorized or 0.3-fallback rows (e.g. after adding an API key later). It never touches rows Tyler set or reviewed.
- Category names Plaid sends that have no clear-case mapping are logged after each sync, so the map can be extended after the first real sync.
- The Review tag and "Needs review only" filter already followed the spec rule (confidence < 0.7 and not reviewed) from Phase 2.

**Verified**
- Backend `./gradlew test`: **101 passed, 2 skipped** (live Plaid Sandbox and live Claude tests; both need keys).
- `CategorizationIntegrationTest` runs a 24-transaction fixture modeled on Plaid Sandbox's test bank through the real sync, pipeline, Postgres and API, with a scriptable fake Claude:
  - **Every transaction gets a valid category** in all four model situations: no model, valid answers, garbage answers, and a model call that throws.
  - **Invalid model output falls back safely:** unknown category, wrong category/subcategory pair, confidence 1.7, unknown id, empty answer and duplicate answers all become Plaid-map fallbacks at exactly 0.3, while valid answers are kept as `ai`. A failed call still leaves the sync `ok`. (This test caught a bug: a duplicate answer could override a rejected one. Fixed.)
  - **Card payments never count as spending:** all four card-payment rows ($2,078.50 Plaid-tagged on the card, $25 Plaid-tagged from checking, and a $412.66 pair that only matched by wording and amount) end up Transfer › Card Payment, and `/api/summary` Spent equals the database's spend-category total.
  - Claude only receives what steps 1–3 couldn't settle; batches go out 50 / 50 / 20 for 120 transactions; exactly 20 user corrections are included; transfer pairs across two syncs update the earlier side but never a user-set row; a coincidental $40 dinner and $40 payout don't pair; a user rule beats everything; the recategorize endpoint upgrades fallbacks once a model is available and skips reviewed rows; a transaction with no Plaid category stays uncategorized and needs review.
  - `PfcMapperTest` (v1 names, v2-style names from Plaid's API spec, ambiguous ones left to the model, every mapping in the taxonomy, a valid fallback for every primary incl. unknown ones), `TransferDetectorTest` (sample-data card-payment wording on both sides; refunds, rent and purchases are not card payments; Venmo/Zelle excluded; retirement contributions), `ClaudeCategorizerTest` (prompt and message content).
- Frontend unchanged this phase; `make test` passes (33/33 frontend tests).

**Not yet verified**
- **"Sandbox transactions all get a valid category"** against the *real* Plaid Sandbox: `PlaidSandboxIT` now asserts exactly that (plus credit card payments being Transfer › Card Payment), but it can't run until Sandbox keys exist and `sandbox.plaid.com` is reachable. The fixture above is modeled on Sandbox from memory and is labeled as such.
- **The real Claude call:** `ClaudeCategorizerIT` sends the fixture once and checks every answer is valid and card payments come back as transfers. It needs `ANTHROPIC_API_KEY`. The request was built from the SDK's own classes (checked with `javap` against the 3.1.0 jar) and compiles, but hasn't been sent.
- **Plaid's v2 category names:** only a few appear in Plaid's public API spec (plaid.com is blocked here). The map matches on keywords so close variants still map, and anything unmapped still gets a category through the model or the fallback.

**Notes**
- Cost estimate with Claude Opus 5.5 at low effort: roughly a cent per batch of 50; the first two-year history pull is maybe 30 batches. Steady state is a few calls a month.
- Categorization runs inside the sync's database transaction, so the model calls during a large first sync keep that transaction open longer; fine for one user.

## Phase 5 — Net worth and manual accounts ✅ (2026-10-22)

Built and verified on sample data. Phase 1's live Plaid check, and the live parts of Phase 4, still wait on keys.

**Built**
- `GET /api/networth`: each visible account's most recent balance, grouped the prototype's way (Cash & savings / Credit cards / Retirement, plus Other for loans and other assets). Totals: net worth and cash minus card balances (everything except Retirement). Within a group: checking, savings, money market, then the order accounts were added (migration V6 makes `created_at` reflect that order). "Balances as of" is the newest bank-fetched balance. Hidden accounts are left out; accounts with no balance yet are listed separately and don't count.
- `POST /api/accounts/manual {name, type, subtype?, role?}` (type: depository / credit / investment / loan / other; role defaults to balance-only). `POST /api/accounts/{id}/balance {asOf, current}` for manual accounts only, in app sign: what you owe is negative. One entry per day; the date can't be in the future (New York) or before 1990. `PATCH /api/accounts/{id} {name?, displayColor?, hidden?}` for any account. Hidden accounts drop out of the ledger, budgets and net worth.
- **Frontend:**
  - **Net worth tab**, ported from the prototype: the two totals, groups with totals, rows with "Manual · updated Oct 1" / "Retirement · linked" / kind meta lines, negative balances in red, the prototype's note, plus a "No balance yet" group. Loading, empty and error states.
  - **Accounts screen:** linked institutions with status, last sync, Reconnect, Sync now, Link (bank or retirement); "Add manual account" (name, type, optional starting balance and date); per account Edit (rename, pick a color, hide) and, for manual accounts, Update balance. Cards and loans ask for "Amount owed" as a positive number and store it negative. Sample accounts appear in their own group with a note that they're removed on the first real link.

**Verified**
- Backend `./gradlew test`: **106 passed, 2 skipped** (live Plaid and live Claude tests).
  - `NetWorthMatchesPrototypeTest`: on the sample data, `/api/networth` reproduces the prototype's Net worth tab exactly (recorded with `scripts/prototype-oracle`): $176,961 and $44,434, the "as of Oct 20, 2026" date, each group's total, and every row's name, meta line, amount and sign, in the same order.
  - `NetWorthApiTest`:
    - **totals equal the sum of the latest snapshots**, computed independently in SQL with `distinct on`, with older snapshots present for several accounts, a manual asset and loan, and an account with no balance; group totals add up; cash minus cards = total − retirement; hiding an account removes it and the totals still match SQL;
    - **a manual account shows its updated date** (Oct 1, then Oct 20 after a newer entry; a same-day entry replaces the old one);
    - manual-balance rules (future date, 1980, 3 decimals, bad date, linked account → 400; unknown → 404) and create/patch validation (bad type, blank name, bad color → 400; trimming; rename/hide on sample accounts; the ledger respects hidden).
- Frontend: lint, typecheck, **40/40 tests** (Net worth: prototype formatting, meta lines, red negatives, note, no-balance group, states; Accounts: list, link, reconnect, add manual account with a starting balance, "amount owed" stored negative, rename/recolor/hide, sample group, sync and not-configured messages; shell: Net worth tab loads). `make test` passes.
- **`docker compose up --build` + `make smoke`** (Docker Hub allowed the pulls this time), then headless Chromium:
  - Net worth tab next to the prototype at **980px and 400px**: **identical** totals, groups, rows, meta lines and note; no horizontal scroll.
  - Added "Old 401(k)" through the Accounts screen with $12,345.67 as of Oct 15: the Net worth tab showed **"Manual · updated Oct 15"**, still after a reload. **The total matched the database's sum of latest snapshots** before ($176,961.01), after adding (+$12,345.67) and after hiding Capital One (+$578.03).
  - Screenshots: `docs/screenshots/phase5-networth-vs-prototype-desktop.png`, `…-400px.png`, `phase5-accounts-400px.png`.

**Notes**
- Net worth history (`/api/networth/history`) and its chart are Phase 7, per the spec.
- Manual accounts are balance-only; there's no manual transaction entry (not in the spec).

## Phase 6 (part) — Home-screen app, opens full screen ✅ (2026-10-22)

Tyler asked for the phone app to open full screen like a regular app. Done ahead of the rest of Phase 6, since it doesn't need Plaid keys.

**Built**
- `public/manifest.webmanifest`: name "Personal Finance", short name "Finance", `display: standalone` (no browser bar), start page `/`, portrait, the prototype's background color; icons at 192 and 512, plus a 512 "maskable" one for Android's shaped icons.
- iPhone tags in `index.html`: `apple-mobile-web-app-capable`, an `apple-touch-icon` (180×180, no transparency), home-screen title "Finance", status bar style, and `theme-color` in the prototype's light and dark background colors.
- Icons rendered from the app's existing logo (green tile, white chart line) with the line inside Android's safe zone.
- Content keeps clear of the notch and the home indicator (safe-area padding on top of the prototype's spacing).
- nginx serves the manifest as `application/manifest+json` (its default types lack it).
- No service worker on purpose: nothing is cached offline, so the app always shows current numbers and needs a connection.

**Verified**
- Frontend: lint, typecheck, **43/43 tests**. New `homeScreenApp.test.ts`: the manifest is standalone with the right start page; every icon file exists at its stated size, including the maskable one; `index.html` has the manifest link and iPhone tags; the iPhone icon is 180×180 with no alpha channel. Production build includes the manifest and icons. `make test` passes.
- `docker compose up --build` + `make smoke`. Served types checked: manifest `application/manifest+json`, PNG icons `image/png`, and the hashed CSS/JS still `text/css` / `application/javascript` with year-long caching after the nginx change.
- **Chromium's own installability check** (DevTools `Page.getInstallabilityErrors`, normal profile): **no errors**; manifest parsed with no errors as `display: standalone`, `start_url: /`.

**Not verified**
- On a real phone. Only Tyler can do that, after the Tailscale setup in `docs/hosting.md`. Install from Safari → Share → **Add to Home Screen** (iPhone), or Chrome → ⋮ → **Install app** (Android).
- **Note for Phase 6:** banks that log in on their own site (OAuth) can lose their way back to a home-screen app on iPhone. Link those banks from a regular browser tab; the home-screen app picks them up after.
