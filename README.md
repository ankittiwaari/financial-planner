# DhanNiti

A personal expense and dues tracker. It answers one question: **how much can I actually spend this cycle?**

See [PLAN.md](PLAN.md) for the full design: data model, automation jobs, API, screens and milestones.

| Folder | What |
|---|---|
| [`backend/`](backend) | Laravel 12 API (PHP 8.4, Sanctum, MySQL 8.4, Pest), run with Docker |
| [`app/`](app) | Compose Multiplatform app (Android first): `shared` (UI, Ktor, Room, Koin) + `androidApp` |

## Run the API

Needs Docker Desktop only (no local PHP).

```bash
cd backend
docker compose up -d                                  # API on http://localhost:8000, MySQL, scheduler
docker compose exec app php artisan migrate --force
docker compose exec app php artisan db:seed --force   # creates the owner from OWNER_* in .env
```

On a fresh clone, first copy `.env.example` to `.env`, fill in `DB_PASSWORD` and `OWNER_*`, then run
`docker compose run --rm app composer install` and `docker compose run --rm app php artisan key:generate`.

- Tests: `docker compose exec app php artisan test`
- Contract: [`backend/openapi.yaml`](backend/openapi.yaml)
- Check: `curl http://localhost:8000/api/v1/health` returns `{"status":"ok"}`

## Run the app

Open `app/` in Android Studio, or:

```bash
cd app
./gradlew :androidApp:installDebug
```

Debug builds call `http://10.0.2.2:8000/api/v1/`, which is your PC as seen from the emulator. For a physical
phone, change `API_BASE_URL` in `app/androidApp/build.gradle.kts` to your PC's LAN IP and add that IP to
`app/androidApp/src/debug/res/xml/network_security_config.xml`.

- On Android 17+, debug builds ask for **Nearby devices** on first launch. Allow it: that is Android's
  local-network permission (`ACCESS_LOCAL_NETWORK`), without which the app can't reach your PC and shows
  "Can't reach the server". If you denied it, enable it in the app's settings or reinstall.
- Tests: `./gradlew :shared:testAndroidHostTest`
- Sign in with `OWNER_EMAIL` / `OWNER_PASSWORD` from `backend/.env`.

### Troubleshooting

`java.io.IOException: Unable to establish loopback connection` when running Gradle from a terminal: the JDK
can't create its Unix-socket temp file in `%TEMP%`. Point it at a short path for that shell:

```bash
mkdir -p .gradle/udstmp
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/Public/Documents/Work/DhanNiti/app/.gradle/udstmp"
```
