# Curato — Product Catalogue PoC

A small product catalogue: browse, search, and submit an enquiry on a product.

> **Status: complete — backend and client, every screen built and running.** Verified on
> a device: the backend suite passes on Node 22, the stack comes up under docker compose,
> and the Android app browses from Home through a category to a product, searches the
> catalogue, and sends a real enquiry that lands in the Mailhog inbox — with 182 shared
> client tests green on two targets. No route renders a placeholder.

## Stack, and why

**Backend — Node.js + TypeScript.** A thin typed JSON API in front of the
[Fake Store API](https://fakestoreapi.com/); the client never calls Fake Store directly.
Chosen for how fast it brings a correct, typed HTTP contract up, and because the two
boundaries expected to change — the product source and the email provider — sit behind
interfaces that a new adapter can replace without touching routes or the client.

**Client — Kotlin Compose Multiplatform, Android as the target.** Compose Multiplatform
is not one of the named client options, so this is a deliberate interpretation rather
than a silent assumption. CMP's Android target *is* a first-class native Kotlin Android
app, satisfying the Kotlin option, while placing the domain and presentation layers in
`commonMain` so iOS becomes additive rather than a parallel build. Android is the only
target intended to be validated in this submission; iOS is planned to be scaffolded,
not run. The trade-off is argued
in full in [DECISIONS.md](DECISIONS.md) — it is the choice most worth challenging, and
it is documented accordingly.

**Everything else:** Koin (DI), Ktor (Retrofit is not multiplatform-compatible),
Navigation Compose Multiplatform, Material 3 with centralized tokens, Mailhog as the
local SMTP catcher.

See [SPEC.md](SPEC.md) for the full specification — screen states, API contract,
validation rules and acceptance criteria.
See [DECISIONS.md](DECISIONS.md) for why, and what was assumed or cut along the way.
See [AGENTS.md](AGENTS.md) for the rules any AI coding agent follows in this repo.
See [AI_USAGE.md](AI_USAGE.md) for how AI tooling was used.
See the [Curato Screen Kit](https://claude.ai/artifact/7bKjCMsswvdLHqWAo9DiqB) for the
visual design — every screen and state, the colour, type and metric tokens, and the
component inventory they are drawn from.

## Features

- **Splash → Home** — the platform splash screen hands straight off to Home; no timed
  delay. Sections shimmer independently while loading.
- **Home** — a search bar, your recently viewed products, the 5 top-rated products, and
  then every category as its own shelf, each with a "View all". The whole catalogue is on
  the first screen; nothing is hidden behind a tap, and once it has loaded, browsing it
  costs no further requests.
- **Recently viewed** — the products you have opened, newest first, kept on the device
  only (no account, no tracking). Renders without a network call, so it works offline.
- **Category page** — opened from a shelf's "View all"; shows the top-rated products in
  that category plus the remaining ones.
- **Search** — results arrive as you type, on their own screen, so going back from a
  product returns you to your results rather than a reset home screen. Matching reads the
  product description as well as the title, so the result count names the term it matched
  on — that is why a search for "baseball" returns a t-shirt whose title never says so.
- **Product page** — full details, with "Enquire" pinned to the bottom of the screen
  within thumb reach.
- **Enquiry** — name and email required, phone and message optional. Your contact details
  are saved after the first submission (behind a "Remember my details" toggle you can
  turn off) so later enquiries are one tap, with an Edit option if anything changes. The
  enquiry carries the product's details so the recipient knows what it is about.
  It is sent via SMTP and captured locally by Mailhog, standing in for the sales inbox —
  demonstrating SMTP acceptance, not delivery to a real mailbox. The "Thank you"
  confirmation appears only once the backend confirms the message was accepted for
  sending, never merely on form submission, so a failed send is never shown as success.
  If sending fails, your input is kept and you can retry.

### Built for one-handed use

Primary actions sit in a sticky bottom bar within thumb reach rather than at the end of a
scroll, touch targets meet the 48dp minimum, form fields carry real labels with inline
error messages, and layouts respect large system font scales.

## Setup & running locally

**Prerequisites:** a Docker daemon, **JDK 21**, and Android Studio with an emulator
image. Nothing else — no accounts, no paid services, no API keys.

JDK 21 specifically: AGP does not support JDK 26, and the Gradle daemon is pinned to 21
in `gradle/gradle-daemon-jvm.properties`. Any locally installed 21 satisfies it.

Any Docker daemon works. This was verified with [Colima](https://github.com/abiosoft/colima)
rather than Docker Desktop, because Docker Desktop's licence requires a paid subscription
for larger organisations and this project commits to needing none:

```bash
brew install colima docker docker-compose
colima start --cpu 2 --memory 4
```

Homebrew's Compose is a CLI plugin, so point Docker at it once — otherwise
`docker compose` reports "is not a docker command":

```bash
mkdir -p ~/.docker && printf '{\n  "cliPluginsExtraDirs": ["/opt/homebrew/lib/docker/cli-plugins"]\n}\n' > ~/.docker/config.json
```

### 1. Backend (~2 minutes)

```bash
cp .env.example .env
docker compose up
```

This starts the API and Mailhog. Verify before moving on:

```bash
curl localhost:8080/health
```

A healthy stack answers `{"status":"ok","upstream":"reachable","smtp":"reachable"}` inside
the envelope. It returns `200` even when a dependency is degraded — the body is what tells
you which, so read it rather than the status code.

Mailhog's web inbox — where enquiry emails land — is at <http://localhost:8025>.

Measured on a clean start — images pulled, both `npm ci` stages run, no build cache —
**52 seconds from `docker compose up` to a healthy container** on an M-series Mac. The
10-minute budget in [SPEC.md](SPEC.md) §10.5 has plenty of room.

`docker compose stop` returns in under a second rather than hitting Docker's 10-second
kill timeout, because the service handles SIGTERM and finishes in-flight requests first.

To run the API without Docker instead:

```bash
cd backend && nvm use && npm ci && npm run dev
```

That needs an SMTP listener on `SMTP_PORT` before enquiries will send; reads work without
one, and `/health` will report `smtp: unreachable` until it exists.

For day-to-day client work there is a script that does both halves — API on the host with
hot reload, Mailhog in Docker for the SMTP listener — and wires the device up:

```bash
./scripts/dev.sh
```

It starts the Docker daemon if it is not already up, brings up Mailhog, runs the API on
the Node version `backend/.nvmrc` pins, points every attached device at the host with
`adb reverse`, and prints what `/health` reports for each dependency. Ctrl+C stops the API
and removes the forwards; `./scripts/dev.sh --down` also stops Mailhog. It is a
convenience over the two commands above, not a replacement for them — `docker compose up`
remains the path a reviewer should use.

### 2. Android client (~5 minutes)

Open the project in Android Studio, let Gradle sync, and run the `androidApp`
configuration on an emulator. Or from the command line:

```bash
./gradlew :androidApp:installDebug
```

**If Home shows "No connection" while the backend is plainly running**, the emulator's
NAT is not completing TCP to the host — which happened on the machine this was built
on. Tunnel over adb instead; this is also the route that works for a physical device:

```bash
adb reverse tcp:8080 tcp:8080
./gradlew :androidApp:installDebug -Pcurato.apiBaseUrl=http://localhost:8080
```

> **Two things that will otherwise fail silently**, both to be configured in the debug
> build — listed because they are the usual reason a local run "just doesn't work":
>
> 1. **The emulator cannot reach your machine at `localhost`.** Inside the emulator,
>    `localhost` is the emulator. The host is **`10.0.2.2`**, so the debug base URL is
>    `http://10.0.2.2:8080`.
> 2. **Android 9+ blocks cleartext HTTP.** A debug-only network-security configuration
>    will permit `10.0.2.2`; release builds keep cleartext blocked.
>
> To point the app at a different backend, change the base URL build-config field — no
> code edit needed.

### Configuration

Every variable lives in `.env.example` with a safe placeholder. No secrets are committed.

| Variable | Purpose |
|---|---|
| `PORT` | API port (default `8080`) |
| `CORS_ORIGINS` | Comma-separated allowlist — never `*` |
| `UPSTREAM_BASE_URL` | Fake Store base URL |
| `UPSTREAM_TIMEOUT_MS` | Upstream request timeout |
| `UPSTREAM_RETRIES` | Additional attempts after the first, so a hung upstream degrades us rather than exhausting our connections |
| `CACHE_TTL_SECONDS` | Product-list cache lifetime (default 300) |
| `CACHE_MAX_STALE_SECONDS` | How long cached data may still be served when the upstream is unreachable |
| `MAX_REQUEST_BODY_BYTES` | Body size cap; larger requests are rejected before parsing |
| `FEATURED_PRODUCT_IDS` | Comma-separated ids for the home page. **Unset = computed top 5 by rating.** Setting it also changes the shelf heading from "Top rated" to "Featured", since a curated list is not a ranking |
| `SMTP_HOST` / `SMTP_PORT` | Mailhog locally; a real provider later. Compose overrides the host to the `mailhog` service |
| `SALES_INBOX_ADDRESS` | Where enquiries are delivered. **Required** — the service refuses to start without it |
| `MAIL_FROM_ADDRESS` | The envelope sender. **Required** |
| `LOG_LEVEL` | `debug` / `info` / `warn` / `error` / `silent` (default `info`) |
| `ENQUIRY_RATE_LIMIT` / `ENQUIRY_RATE_WINDOW_S` | Abuse control on the enquiry endpoint |

## Architecture

### API

Every endpoint returns the same envelope, success or failure, so the client always knows
what it is parsing:

```json
{ "success": true, "data": { }, "error": null }
{ "success": false, "data": null,
  "error": { "code": "VALIDATION_FAILED", "userMessage": "…", "developerMessage": "…",
             "traceId": "01JBQ7F3K2",
             "fieldErrors": { "contact.email": "Enter a valid email address" } } }
```

`fieldErrors` keys are dotted request paths, so the client places each error under the
input that caused it. `userMessage` is the only *general* error string shown to a
customer — field errors render inline, and `traceId` is surfaced only on an internal
error so a report can be tied to a log line. `developerMessage` is never shown, and is
sanitized at the point it is produced rather than relying on the client not to render
it.

| Endpoint | Returns |
|---|---|
| `GET /home` | `{ featured: { source, products[] } }` |
| `GET /categories` | `{ categories: [{ category, topRated[] }] }` |
| `GET /categories/:slug/products` | `{ category, topRated[], additional[] }` |
| `GET /products/search?q=` | `{ query, results[] }` |
| `GET /products/:id` | a single product, the only one carrying `description` |
| `POST /enquiries` | 2xx only once SMTP has accepted the message |
| `GET /health` | `{ status, upstream, smtp }` — used by the compose healthcheck |

Home opens the first two in parallel; every endpoint that returns a **list** returns
products without `description`, since no card renders one.

Full endpoint contracts, the error-code registry and validation rules are in
[SPEC.md](SPEC.md) §4–§6. See [DECISIONS.md](DECISIONS.md) for why the envelope has one
user-facing string, why ranking happens on the backend, and why Home is two parallel
calls rather than one aggregate.

### Backend (`backend/`)

Ports and adapters at the two boundaries the brief says will change, with the rules in
the middle kept pure so they are cheap to test and cheap to change.

```
src/
  domain/        the rules, no I/O: ranking, the featured-list normalization,
                 search filtering, the slug map, the error registry
  domain/ports/  ProductRepository and EmailService — the two seams
  adapters/      Fake Store (HTTP client, mapper, TTL cache) and SMTP
  services/      orchestration: catalogue reads, the enquiry path
  http/          envelope, validation, the route table, the catch-all, routes
```

Nothing in `domain/` imports anything from `adapters/` or `http/`, which is why the
rules most likely to change are also the ones fastest to test. `createApp()` takes its
dependencies as arguments rather than resolving them from a container — at this size a DI
framework would be ceremony, and it means every HTTP test runs against the real app over
fake adapters.

Run it with Node 22 (`.nvmrc` pins it). The container uses the same version, so the
runtime a reviewer gets from `docker compose up` matches the one the suite ran on.

### Client (`shared/`, `androidApp/`)

Two Gradle modules, because AGP 9 forbids applying `com.android.application` alongside
Kotlin Multiplatform. The split is a toolchain requirement, not an architectural one:

```
shared/src/commonMain/kotlin/com/curato/
  domain/        models, repository interfaces, use cases, ApiResult
  data/          DTOs, the one envelope parser, mappers, Ktor client, DataStore
  presentation/  ViewModels exposing StateFlow<UiState>
  ui/            screens, navigation, and designsystem/ (theme + components)
  di/            Koin modules
shared/src/androidMain, iosMain   one file each: HTTP engine and storage path
androidApp/                       an Activity, an Application, a manifest. No logic.
```

Every visual token and all nine components come from the
[Screen Kit](https://claude.ai/artifact/7bKjCMsswvdLHqWAo9DiqB) rather than from taste —
colour, the Inter type scale with tabular figures, the 4dp spacing base, radii, motion
durations and the 48dp touch-target floor.

**All six screens are built**, along with the design system, the network and envelope
layer, DI, navigation, and both device-local stores. Nothing renders a placeholder.

Enquiry is the largest of them and the only write path in the app: it resolves its own
product from the route's id, mirrors the §6 validation rules for immediate feedback while
leaving the server authoritative, keeps the reusable contact store and the ephemeral form
draft physically apart, and restores a confirmed "Thank you" after process death without
making a single network call.

## Where to change things

A map of the handful of changes most likely to be asked for, so a small change does not
require reading the whole repo first.

| To change… | Edit |
|---|---|
| Which products are featured | `FEATURED_PRODUCT_IDS` in `.env` — no code, no rebuild |
| How "top rated" is ranked | [`backend/src/domain/ranking.ts`](backend/src/domain/ranking.ts) — one pure function; the client never ranks |
| The product data source | Add a [`ProductRepository`](backend/src/domain/ports/product-repository.ts) implementation and pass it to `createApp`. Routes, services and client untouched |
| The email provider | Add an [`EmailService`](backend/src/domain/ports/email-service.ts) implementation and pass it to `createApp` |
| Which products are curated vs computed | [`backend/src/domain/featured.ts`](backend/src/domain/featured.ts) holds the normalization; the env var drives it |
| An error's HTTP status or copy | The registry in [`backend/src/domain/errors.ts`](backend/src/domain/errors.ts) — one table, no per-route handling |
| A field on the product | Backend DTO + mapper, then the client DTO + domain model — the API contract in [SPEC.md](SPEC.md) §3 is the checklist. Decide first whether the field belongs on `ProductSummary` (every list) or only on `Product` (the product screen) |
| Colours, type, spacing | Theme tokens in `ui/designsystem` — not individual screens. The token values are in the [Screen Kit](https://claude.ai/artifact/7bKjCMsswvdLHqWAo9DiqB) |
| A reusable component | `ui/designsystem`; screens compose it rather than styling inline |
| Copy shown on an error | `userMessage` in the backend's error registry, so it changes without a client release |
| Adding a screen | A new route taking primitives only, a ViewModel exposing `StateFlow<UiState>`, and a Compose screen |

## Testing

```bash
cd backend && nvm use && npm ci && npm run typecheck && npm test   # 216 tests
./gradlew :shared:allTests                                          # 182, on two targets
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64               # proves iOS compiles
```

The whole suite runs in under a second with no network, no SMTP and no emulator, which is
the point — it is fast enough to run between edits, as [SPEC.md](SPEC.md) §11.3 requires.
The upstream catalogue is a recorded fixture, so a silent change to Fake Store fails a
test rather than a screen.

Tests cover the critical path and exist to make the code safe to change. The strategy,
and what is deliberately **not** tested, is in [SPEC.md](SPEC.md) §11.

- **Backend unit** — ranking and tie-breaks, featured-id override and fallback, category
  top/additional split, search filtering, validation rules, error-to-envelope mapping.
- **Backend integration** — `POST /enquiries` against a stubbed SMTP: 2xx only after
  acceptance, `EMAIL_SEND_FAILED` on rejection, backend-resolved price in the email,
  validation error shape, rate limiting.
- **Backend adapter** — the upstream's refusal to use status codes: a `200` with an empty
  body becomes a domain not-found, `200 []` becomes an empty list, and neither becomes a
  thrown parse that would surface as a 500.
- **Backend contract** — every endpoint returns a well-formed envelope, `data` and `error`
  stay mutually exclusive, `developerMessage` carries no stack frame, and list endpoints
  never carry `description`. Plus the four paths no route handler owns: an oversized body,
  a malformed body, an unknown route and a wrong verb.
- **Client unit** — the envelope-to-result mapping including a code this build does not
  know; Home's two calls failing independently and a Retry re-running only the one that
  failed; the shelf heading branching on `featured.source`; Category omitting its second
  section when the remainder is empty, keeping the two sets disjoint when it is not, and
  refusing a Retry on a slug that will never resolve; Product recording a view only on a
  successful load and never on a failed one, and de-duplicating a re-view to the front;
  the Enquire route carrying — and correctly omitting — the captured price; Search
  firing exactly one request for a typed word and quoting the term the *server* matched;
  recently-viewed LRU, cap, de-dupe and corrupt-payload handling; locale-independent
  price formatting. All in `commonMain`, so they run with no device — and they run on **both** the Android host
  and the iOS simulator, so a platform-specific regression fails the suite rather than
  a screen.
- **Client enquiry** — the densest group, because this is the only write and most of its
  rules are invisible on screen when broken: three rapid taps producing one submission;
  nothing stored when consent is unchecked and the store cleared the instant it is;
  a restored `submitted` draft rendering the thank-you and firing **zero** calls; the
  message surviving process death in the draft while never entering the contact store;
  `seenPrice` reaching the request exactly as the route carried it, and absent optionals
  omitted from the body rather than sent as `null`. Plus the §6 mirror boundary by
  boundary, including the addresses it must **not** reject — a client stricter than the
  server refuses submissions the backend would have accepted.
- **One acceptance criterion is unit-only on purpose.** AC-6c's unknown slug cannot be
  reached from the device: nothing in the UI links to a bad one, and the `curato://`
  deeplink that would launch one is an explicit non-goal. It is pinned at both ends
  instead — the backend contract test asserts `404 NOT_FOUND` for an unknown slug, and
  the client tests assert that envelope becomes a non-retryable error with no Retry
  button. What *was* checked on the device is the retryable path.
- **Not covered: Compose UI instrumentation tests.** A deliberate cut — emulator-bound,
  slow, and the budget buys more confidence as unit coverage of the rules. Layout
  regressions are caught by eye, not CI.

[SPEC.md](SPEC.md) §12 maps every required capability to the acceptance criteria that
verify it.

## Time spent

_To be filled in honestly at the end, split across planning / building / testing /
reviewing._

The original target was 2–3 hours. Defining the user journey added scope beyond the base
screens (see "Time-box" in [DECISIONS.md](DECISIONS.md)); the revised estimate is 4–5
hours, accepted deliberately before building rather than discovered mid-build.
