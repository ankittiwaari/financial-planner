# DhanNiti API

> **Personal expense & dues tracker** — a single-user Laravel API with Sanctum token auth, OpenAPI 3.1 docs, and a ledger-centric data model.

---

## Quick Start

```bash
# From the backend/ directory
cp .env.example .env
composer install
php artisan key:generate
php artisan migrate --seed   # creates the owner user (see below)
php artisan serve            # http://localhost:8000
```

**Default owner credentials** (created by `db:seed`):

| Field       | Value                   |
| ----------- | ----------------------- |
| **Email**   | `owner@dhanniti.local`  |
| **Password**| `password`              |

> **Note:** This is a single-user app. There is no public sign-up endpoint. Create additional users directly in the database if needed.

---

## Base URL

| Environment | URL |
|-------------|-----|
| Local (Docker) | `http://localhost:8000/api/v1` |
| Android Emulator | `http://10.0.2.2:8000/api/v1` |

All endpoints are prefixed with `/api/v1`.

---

## Authentication

**Type:** Laravel Sanctum Personal Access Tokens (Bearer)

### Login

```http
POST /auth/login
Content-Type: application/json

{
  "email": "owner@dhanniti.local",
  "password": "password",
  "device_name": "My Phone"
}
```

**Response (200):**
```json
{
  "token": "1|kXl3...",
  "user": { "id": 1, "name": "Owner", "email": "owner@dhanniti.local", "timezone": "Asia/Kolkata", "currency": "INR", "cycle_mode": "payday" }
  }
```

### Using the Token

```http
Authorization: Bearer 1|kXl3...
```

### Logout (revokes only the current token)

```http
POST /auth/logout
Authorization: Bearer <token>
```

### Get Current User

```http
GET /me
Authorization: Bearer <token>
```

---

## Conventions

### Money = **Paise** (integer)
All amounts are stored and transmitted as **positive integer paise**.
- ₹45,250.50 → `4525050`
- ₹100 → `10000`

> **Rule:** `direction` gives the sign. `credit` = money in, `debit` = money out.

### Dates
- Format: **ISO 8601** (`YYYY-MM-DD`)
- "Today" is computed in the user's `timezone` (default `Asia/Kolkata`).
- Month periods use `YYYY-MM` (e.g., `2026-10`).

### Response Wrapping
All resources are wrapped in a `data` envelope:
```json
{ "data": { ... } }          // single resource
{ "data": [ ... ] }          // collections (accounts, categories, income-sources)
{ "data": [...], "meta": { "next_cursor": "..." } }  // cursor-paginated transactions
```

### Errors
| Status | Shape |
|--------|-------|
| 401 | `{ "message": "Unauthenticated." }` |
| 422 | `{ "message": "...", "errors": { "field": ["..."] } }` |
| 404 | `{ "message": "Not found" }` |
| 409 | `{ "message": "Conflict description" }` |
| 429 | `{ "message": "Too many attempts" }` |

---

## API Endpoints

### Health
| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `GET` | `/health` | ❌ | Liveness check → `{ "status": "ok" }` |

---

### Accounts
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/accounts` | List accounts (primary first, with live balance). `?include_archived=true` |
| `POST` | `/accounts` | Create account. First account becomes primary. |
| `GET` | `/accounts/{id}` | Get one account |
| `PUT` | `/accounts/{id}` | Update (name, type, opening_balance, is_primary) |
| `DELETE` | `/accounts/{id}` | **Archive** — keeps history, stops new transactions |

**Account Types:** `bank`, `cash`, `wallet`

**Account Resource:**
```json
{
  "id": 1,
  "name": "HDFC Checking",
  "type": "bank",
  "opening_balance": 5000000,
  "balance": 4825050,
  "is_primary": true,
  "archived": false
}
```

---

### Categories
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/categories` | List categories. `?kind=income|expense&include_archived=true` |
| `POST` | `/categories` | Create category |
| `PUT` | `/categories/{id}` | Rename / change icon (kind locked if used) |
| `DELETE` | `/categories/{id}` | **Archive** — hidden from pickers, transactions keep it |

**Category Kinds:** `income`, `expense`

**Category Resource:**
```json
{
  "id": 3,
  "name": "Food",
  "kind": "expense",
  "icon": "🍔",
  "archived": false
}
```

---

### Transactions
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/transactions` | Cursor-paginated, newest first. Filters: `from`, `to`, `account_id`, `category_id`, `direction`, `per_page` (1–100, default 50), `cursor` |
| `POST` | `/transactions` | Record a transaction. Category kind must match direction. |
| `GET` | `/transactions/summary?from=2026-10-01&to=2026-10-31` | Totals for a date range |
| `GET` | `/transactions/{id}` | Get one |
| `PUT` | `/transactions/{id}` | Update |
| `DELETE` | `/transactions/{id}` | Hard delete |

**Transaction Resource:**
```json
{
  "id": 42,
  "account_id": 1,
  "category_id": 3,
  "direction": "debit",
  "amount": 125000,
  "occurred_on": "2026-10-15",
  "description": "Lunch",
  "source_type": null,
  "source_id": null,
  "period": null,
  "is_auto": false
}
```

**Summary Response:**
```json
{
  "data": {
    "from": "2026-10-01",
    "to": "2026-10-31",
    "credit_total": 12000000,
    "debit_total": 8450000,
    "net": 3550000
  }
}
```

---

### Income Sources (Salary, Freelance, etc.)
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/income-sources` | List with `next_pay_date` and `last_credited_period` |
| `POST` | `/income-sources` | Create |
| `GET` | `/income-sources/{id}` | Get one |
| `PUT` | `/income-sources/{id}` | Update |
| `DELETE` | `/income-sources/{id}` | Delete (past credits stay, link cleared) |
| `POST` | `/income-sources/{id}/credits` | **Log a credit for a period** (manual salary credit) |

**Pay Rules:**
- `fixed_day` — uses `pay_day` (1–31, clamped to month length)
- `last_working_day` — last Mon–Fri of the month
- `last_day` — last calendar day

**Weekend Shift (for `fixed_day`):**
- `before` — move to Friday
- `after` — move to Monday
- `none` — keep as-is

**Income Source Resource:**
```json
{
  "id": 1,
  "name": "Salary - Acme Corp",
  "account_id": 1,
  "amount": 12000000,
  "is_variable": false,
  "pay_rule": "fixed_day",
  "pay_day": 1,
  "weekend_shift": "before",
  "auto_credit": true,
  "active_from": "2026-01-01",
  "active_to": null,
  "next_pay_date": "2026-11-01",
  "last_credited_period": "2026-10"
}
```

**Credit a Period:**
```http
POST /income-sources/1/credits
Content-Type: application/json
Authorization: Bearer <token>

{
  "period": "2026-11",
  "amount": 12500000,
  "occurred_on": "2026-11-01"
}
```
- Creates a `credit` transaction in the source's account, categorised as **Salary**.
- One credit per source per period (409 if duplicate).
- `amount` defaults to source's amount; `occurred_on` defaults to the pay date for that period.

---

### Loans, Credit Cards & Recurring Payments (Milestone 2)

Each of these produces **dues** (see below). Creating or editing one rebuilds its open dues from today; deleting it
removes its unpaid dues, while paid and skipped ones stay as history.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET/POST` | `/loans` | List (active first) / create |
| `GET/PUT/DELETE` | `/loans/{id}` | Get / update / delete |
| `GET` | `/loans/{id}/schedule` | Amortisation table: principal/interest split and outstanding after each EMI |
| `POST` | `/loans/{id}/close` | Mark repaid (foreclosed); drops unpaid EMIs |
| `GET/POST` | `/credit-cards` | List with `latest_statement` / create |
| `GET/PUT/DELETE` | `/credit-cards/{id}` | Get / update / delete (statements go with it) |
| `GET` | `/credit-cards/{id}/statements` | Statements, newest first |
| `POST` | `/credit-cards/{id}/statements` | Enter the real bill for a `period` (replaces the estimate) |
| `GET/POST` | `/recurring-payments` | List / create (rent, SIPs, insurance, subscriptions) |
| `GET/PUT/DELETE` | `/recurring-payments/{id}` | Get / update / delete |

- **EMI**: `emi_amount` from the bank, or computed with the reducing-balance formula and rounded to the rupee. EMIs
  fall on `first_emi_date`'s day each month (31st → last day of short months). Loans close by themselves once the
  last EMI date has passed with nothing open.
- **Card bills**: statement on `statement_day`; due on `due_day` of the same month if later, else the next month.
  Until the bill is entered, the amount is an estimate (`estimate_mode`: `last_statement`, `avg_3` or `fixed`).
- **Recurring**: `monthly`, `quarterly` or `yearly` on `day`; quarterly/yearly repeat from `active_from`'s month.

### Dues

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/dues?from=&to=&status=` | Defaults: 30 days ago to 45 days ahead, plus anything overdue. `status`: `upcoming`, `overdue`, `paid`, `skipped` |
| `GET` | `/dues/{id}` | Get one |
| `POST` | `/dues/{id}/pay` | Books a debit (`source_type: due`) and marks it paid. Optional `amount`, `paid_on`, `account_id` |
| `POST` | `/dues/{id}/skip` | Skip without paying |
| `POST` | `/dues/{id}/reopen` | Undo a payment (deletes its transaction) or a skip |

- Dues for the next **45 days** are generated on every `GET /dues` and daily at 00:15 IST (`php artisan dues:generate`).
- Keyed by `(source_type, source_id, period)`, so regenerating never duplicates; paid/skipped dues are never touched.
- `overdue` isn't stored: it's an unpaid due dated before today.
- Paying files EMIs under **Loan EMI**, card bills under **Credit card bill**, recurring payments under their own
  category (default **Bills & utilities**). Deleting that transaction reopens the due.

**Due Resource:**
```json
{
  "id": 81,
  "source_type": "loan",
  "source_id": 4,
  "period": "2026-11",
  "title": "HDFC Home Loan EMI",
  "amount": 4339100,
  "due_date": "2026-11-05",
  "status": "upcoming",
  "paid_on": null,
  "paid_amount": null,
  "transaction_id": null,
  "account_id": 1,
  "auto_debit": true,
  "is_estimate": false,
  "min_due": null
}
```

---

### Dashboard (Milestone 4)

`GET /dashboard` returns the one number the app is built around: **how much can be spent this cycle**.

```
purchasing_power = income (credited, or expected until logged) + other credits
                 − dues this cycle (paid or not, skipped excluded) − overdue dues carried in
                 − spent so far (excluding due payments) − lent out
```

- **Cycle**: payday of the largest active income source to the day before the next; calendar month when the
  user's `cycle_mode` is `calendar` or there's no income source.
- Money still owed to you is under `receivables` and never counted.
- Also returns the next 5 unpaid dues and `alerts` (salary not logged, card bill still estimated, overdue dues
  and lendings). The calculation lives in `App\Services\ProjectionService`; `DashboardTest` checks it against a
  hand-worked cycle.

---

### Automation (Milestone 5)

`php artisan automation:run` runs **hourly** (the Docker `scheduler` service, or a `schedule:run` cron entry) and,
for each user:

| Job | What it does |
|-----|--------------|
| `generate_dues` | Dues for the next 45 days; closes loans after their last EMI |
| `post_salary` | On payday, credits each `auto_credit` income source (variable ones at the expected amount). Catches up on paydays missed by up to 7 days; never back-fills paydays from before the source was added |
| `settle_auto_debits` | On the due date, marks `auto_debit` EMIs and recurring payments paid from their account. Card bills are never auto-settled |

- **Automated ≠ silent**: everything it books has `is_auto: true` and `needs_confirmation: true` until
  `POST /transactions/{id}/confirm` (a variable salary can take its real `amount` there). The dashboard raises a
  `confirm_auto` alert for each.
- **Undo sticks**: deleting an auto-posted salary, or reopening an auto-settled due, is recorded in
  `automation_opt_outs`, so the next run doesn't redo it.
- Every job run is logged in `job_runs` (`GET /automation/runs`). One job failing doesn't stop the others.
- Overdue status and the dues reserve aren't jobs: both are worked out when read.

---

### Reminders (Milestone 6)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/settings/reminders` | `enabled`, `offsets_days` (default `[5, 2, 0]`), `time_of_day` (`09:00`), `overdue_daily` |
| `PUT` | `/settings/reminders` | Replace them |

The server only stores the preference. The Android app plans the notifications itself (`ReminderPlanner`) and
schedules them with WorkManager, so they fire offline: whenever dues or settings change in the app, twice a day in
the background, and after a reboot or clock/timezone change. Each notification has **Mark paid** (queued until
online; calls `POST /dues/{id}/pay`) and **Snooze 1 day**.

---

### Offline & Backups (Milestone 7)

- **`GET /sync`** returns everything the app shows in one snapshot, each part shaped exactly like its own endpoint.
  The app caches it and opens with it when offline.
- **Offline writes**: the app queues new transactions and due payments made offline and sends them when the server
  is reachable. New transactions carry a `client_ref` UUID, so a resend returns the original (200) instead of a
  duplicate; a due payment that's already been made returns 409, which the app treats as done.
- **Backups**: `php artisan backup:database` runs daily at 02:30 IST. It writes a gzipped `mysqldump` (password via
  the environment, never the command line) to the `backups` disk and keeps the newest `BACKUP_KEEP` (default 14).
  The default disk is `storage/app/backups` on this machine: for off-site copies set `BACKUP_DISK` to an S3 or SFTP
  disk in `config/filesystems.php` (S3 needs `composer require league/flysystem-aws-s3-v3`). To restore:
  `gunzip -c dhanniti-….sql.gz | mysql -u… -p… dhanniti`.

---

### Lent Money (Milestone 3)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/lendings?person=&status=` | Still-owed first. `status`: `outstanding`, `settled`, `written_off` |
| `GET` | `/lendings/summary` | Receivables: totals and a balance per person |
| `POST` | `/lendings` | Record money lent |
| `GET/PUT/DELETE` | `/lendings/{id}` | Get / update / delete (delete removes its repayments and transactions) |
| `POST` | `/lendings/{id}/repayments` | Record money paid back (`amount`, optional `received_on`, `account_id`) |
| `DELETE` | `/lendings/{id}/repayments/{repaymentId}` | Undo a repayment |
| `POST` | `/lendings/{id}/write-off` | Give up on what's still owed |
| `POST` | `/lendings/{id}/reopen` | Undo a write-off |

- Outstanding money is a **receivable**: never part of purchasing power until it's repaid.
- With `account_id`, lending books a debit (no category: it isn't spending) and a repayment books a credit under
  **Repayment received**. Leave it null for money that isn't tracked in an account.
- Status follows the repayments: `open` → `partially_repaid` → `settled`. A repayment on a `written_off` lending reopens it.
- People are grouped by name, ignoring case and extra spaces.
- Transactions booked by dues or lending can't be edited from `/transactions` (409); deleting them updates the source.

---

## OpenAPI / Swagger

The full machine-readable spec lives at **`openapi.yaml`** (OpenAPI 3.1).

### View Locally (Swagger UI)

```bash
# Option 1: Redocly (CLI)
npx @redocly/cli preview-docs openapi.yaml

# Option 2: Docker Swagger UI
docker run -p 8080:8080 -v ${PWD}/openapi.yaml:/usr/share/nginx/html/openapi.yaml -e SWAGGER_JSON=/usr/share/nginx/html/openapi.yaml swaggerapi/swagger-ui
# Open http://localhost:8080
```

### Generate Client Code

```bash
# TypeScript (for the Android app)
npx @openapitools/openapi-generator-cli generate -i openapi.yaml -g typescript-axios -o ../android/app/src/main/api

# Or use any OpenAPI Generator target
```

---

## Testing

```bash
# Run all tests (Pest)
php artisan test

# Run only API feature tests
php artisan test --filter=Api
```

**Test Structure:**
```
tests/Feature/Api/V1/
├── AuthTest.php
├── AccountsTest.php
├── CategoriesTest.php
├── TransactionsTest.php
├── IncomeSourcesTest.php
├── LoansTest.php
├── CreditCardsTest.php
├── RecurringPaymentsTest.php
├── DuesTest.php
├── LendingsTest.php
├── DashboardTest.php
├── ReminderSettingsTest.php
└── SyncTest.php
tests/Feature/BackupDatabaseTest.php
tests/Feature/AutomationTest.php   # a simulated two months of daily runs
tests/Unit/
├── PayScheduleTest.php
└── EmiTest.php
```

---

## Key Domain Rules

1. **Single User** — Every resource is scoped to the authenticated user. Another user's ID returns 404.
2. **Archiving ≠ Deleting** — Accounts & categories are soft-archived (`archived_at`). Transactions are hard-deleted.
3. **Category ↔ Direction Lock** — `income` categories only on `credit` transactions; `expense` only on `debit`.
4. **Primary Account** — Exactly one active primary account per user. Creating the first account auto-sets it. Unsetting primary on the current one is blocked.
5. **Income Source Credits** — Idempotent per `(source_id, period)` via unique DB constraint.
6. **Timezone-Aware "Today"** — All date boundaries use the user's `timezone` column.
7. **Dues Are Idempotent** — One due per `(source_type, source_id, period)`; regeneration only updates open ones.

---

## Environment Variables (`.env`)

| Variable | Description | Default |
|----------|-------------|---------|
| `APP_URL` | Base URL for generated links | `http://localhost:8000` |
| `DB_CONNECTION` | Database driver | `sqlite` |
| `DB_DATABASE` | SQLite file path | `database/database.sqlite` |
| `SANCTUM_TOKEN_EXPIRATION` | Token TTL (minutes, 0 = never) | `0` |
| `DEFAULT_TIMEZONE` | Fallback timezone | `Asia/Kolkata` |
| `DEFAULT_CURRENCY` | ISO 4217 code | `INR` |

---

## Milestone Roadmap (from `PLAN.md`)

| Milestone | Scope |
|-----------|-------|
| **M0** | Setup: Laravel, Sanctum, Pest, Docker; app login |
| **M1** | Ledger: accounts, categories, transactions, income sources |
| **M2** | Obligations: loans (EMI + schedule), credit cards + statements, recurring payments, dues |
| **M3** | Lending: money lent, repayments, per-person receivables |
| **M4** | Projection: purchasing power dashboard |
| **M5** | Automation: salary posting, auto-debit settlement, confirmations, job log |
| **M6** | Reminders: settings API; Android notifications with Mark paid and Snooze |
| **M7** | Offline: `/sync` snapshot, cached app, queued offline writes; daily database backups |

-----------|-------|
| **M1** | Ledger: Accounts, Categories, Transactions, Summary |
| **M2** | Income Sources + manual credit logging |
| **M3** | Dues / recurring expenses (planned) |
| **M4** | Budgets & alerts (planned) |
| **M5** | **Automation**: auto-credit salaries on payday, recurring dues, monthly rollover |

---

## Deploying

`.github/workflows/deploy-backend.yml` deploys to cPanel at **https://dhanniti.algopraxis.com** on every push to
`master` that touches `backend/` (or by hand: Actions → *Deploy backend to cPanel* → Run workflow). It runs the tests
first and only deploys if they pass, then:

1. rsyncs `backend/` into `~/dhanniti.algopraxis.com` (never touching the server's `.env`, `storage/` or `vendor/`),
2. runs `composer install --no-dev`, `migrate --force` and the config/route/event caches,
3. checks that `/api/v1/health` answers.

### Repository secrets

Settings → Secrets and variables → Actions: `CPANEL_HOST`, `CPANEL_USER`, `CPANEL_PASSWORD` (the same three as the
other cPanel deploys on this server).

### One-time server setup

1. **Domain**: in cPanel → Domains, create `dhanniti.algopraxis.com` with document root
   `dhanniti.algopraxis.com/public`, and enable AutoSSL for it.
2. **PHP 8.3**: MultiPHP Manager → set the domain to PHP 8.3 (the lock file is resolved for 8.3). The deploy and the
   cron job call `/opt/cpanel/ea-php83/root/usr/bin/php` directly, because the plain `php` in non-interactive shells
   may be another version, and `~/.bashrc` aliases don't apply there. Composer is found at its cPanel path, or
   downloaded to `~/bin/composer.phar` and checked against its published checksum.
3. **Database**: MySQL Databases → create a database and a user with all privileges on it.
4. **Run the workflow once**: it copies the code, then stops with "`.env` is missing". That's expected.
5. **`.env`**: over SSH, in `~/dhanniti.algopraxis.com`, `cp .env.example .env` and edit it: `APP_ENV=production`,
   `APP_DEBUG=false`, `APP_URL=https://dhanniti.algopraxis.com`, `DB_HOST=localhost` plus the database name, user and
   password from step 3, the `OWNER_*` login, and `LOG_LEVEL=warning`. Leave `APP_KEY=` empty: the next deploy
   generates it.
6. **Run the workflow again**: it generates the key, migrates and goes live. Then create the login:
   `php artisan db:seed --force`.

To set the key by hand instead, clear any cached config first and pass `--force`. Without them `key:generate`
compares against a stale cached key, and in production it asks to confirm and, when nobody answers, cancels without
changing `.env`:

```bash
/opt/cpanel/ea-php83/root/usr/bin/php artisan config:clear
/opt/cpanel/ea-php83/root/usr/bin/php artisan key:generate --force
grep APP_KEY .env
```
7. **Cron** (cPanel → Cron Jobs), every minute, for automation (salary posting, auto-debits) and the daily backup:
   ```
   * * * * * cd ~/dhanniti.algopraxis.com && /opt/cpanel/ea-php83/root/usr/bin/php artisan schedule:run >> /dev/null 2>&1
   ```
8. **App**: set the release `API_BASE_URL` in `app/androidApp/build.gradle.kts` to
   `https://dhanniti.algopraxis.com/api/v1/`.

Backups land in `~/dhanniti.algopraxis.com/storage/app/backups`, on the same server: copy them off-site (see
*Offline & Backups*).

---

## Useful Commands

```bash
# Recreate DB & seed owner
php artisan migrate:fresh --seed

# Clear all caches
php artisan optimize:clear

# View all routes
php artisan route:list --path=api/v1

# Lint OpenAPI spec
npx @redocly/cli lint openapi.yaml
```

---

## License

MIT — see `LICENSE` in the repository root.