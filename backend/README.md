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
└── IncomeSourcesTest.php
```

---

## Key Domain Rules

1. **Single User** — Every resource is scoped to the authenticated user. Another user's ID returns 404.
2. **Archiving ≠ Deleting** — Accounts & categories are soft-archived (`archived_at`). Transactions are hard-deleted.
3. **Category ↔ Direction Lock** — `income` categories only on `credit` transactions; `expense` only on `debit`.
4. **Primary Account** — Exactly one active primary account per user. Creating the first account auto-sets it. Unsetting primary on the current one is blocked.
5. **Income Source Credits** — Idempotent per `(source_id, period)` via unique DB constraint.
6. **Timezone-Aware "Today"** — All date boundaries use the user's `timezone` column.

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

## Milestone Roadmap (from `openapi.yaml`)

| Milestone | Scope |
|-----------|-------|
| **M1** | Ledger: Accounts, Categories, Transactions, Summary |
| **M2** | Income Sources + manual credit logging |
| **M3** | Dues / recurring expenses (planned) |
| **M4** | Budgets & alerts (planned) |
| **M5** | **Automation**: auto-credit salaries on payday, recurring dues, monthly rollover |

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