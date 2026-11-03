# Curato — Product Catalogue PoC

A small product catalogue: browse, search, and submit an enquiry on a product.

> **Status: specification and planning complete; implementation not started.**
> Everything below describes the intended system. Commands, behaviours and setup steps
> are the target shape and are marked as verified only once they have actually been run.

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
- **Search** — enter a term and search; results open on their own screen, so going back
  from a product returns you to your results rather than a reset home screen.
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

**Prerequisites:** Docker Desktop, and Android Studio with an emulator image. Nothing
else — no accounts, no paid services, no API keys.

_Commands are the target shape; filled in and verified once the project is scaffolded._

### 1. Backend (~2 minutes)

```bash
cp .env.example .env
docker compose up
```

This starts the API and Mailhog. Verify before moving on:

```bash
curl localhost:8080/health
```

Mailhog's web inbox — where enquiry emails land — is at <http://localhost:8025>.

### 2. Android client (~5 minutes)

Open the project in Android Studio, let Gradle sync, and run the `androidApp`
configuration on an emulator.

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
| `CACHE_TTL_SECONDS` | Product-list cache lifetime (default 300) |
| `CACHE_MAX_STALE_SECONDS` | How long cached data may still be served when the upstream is unreachable |
| `MAX_REQUEST_BODY_BYTES` | Body size cap; larger requests are rejected before parsing |
| `FEATURED_PRODUCT_IDS` | Comma-separated ids for the home page. **Unset = computed top 5 by rating.** Setting it also changes the shelf heading from "Top rated" to "Featured", since a curated list is not a ranking |
| `SMTP_HOST` / `SMTP_PORT` | Mailhog locally; a real provider later |
| `SALES_INBOX_ADDRESS` | Where enquiries are delivered |
| `ENQUIRY_RATE_LIMIT` / `ENQUIRY_RATE_WINDOW_S` | Abuse control on the enquiry endpoint |

## Architecture

_To be completed as the project is scaffolded — see [AGENTS.md](AGENTS.md) in the
meantime for the intended shape._

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

## Where to change things

A map of the handful of changes most likely to be asked for, so a small change does not
require reading the whole repo first.

| To change… | Edit |
|---|---|
| Which products are featured | `FEATURED_PRODUCT_IDS` in `.env` — no code, no rebuild |
| How "top rated" is ranked | The ranking function in the backend's product service (one place; the client never ranks) |
| The product data source | Add a `ProductRepository` implementation; swap the binding. Routes and client untouched |
| The email provider | Add an `EmailService` implementation; swap the binding |
| A field on the product | Backend DTO + mapper, then the client DTO + domain model — the API contract in [SPEC.md](SPEC.md) §3 is the checklist. Decide first whether the field belongs on `ProductSummary` (every list) or only on `Product` (the product screen) |
| Colours, type, spacing | Theme tokens in `ui/designsystem` — not individual screens. The token values are in the [Screen Kit](https://claude.ai/artifact/7bKjCMsswvdLHqWAo9DiqB) |
| A reusable component | `ui/designsystem`; screens compose it rather than styling inline |
| Copy shown on an error | `userMessage` on the backend, so it changes without a client release |
| Adding a screen | A new route taking primitives only, a ViewModel exposing `StateFlow<UiState>`, and a Compose screen |

## Testing

_Commands and results filled in as the suite is built._

Tests cover the critical path and exist to make the code safe to change. The strategy,
and what is deliberately **not** tested, is in [SPEC.md](SPEC.md) §11.

- **Backend unit** — ranking and tie-breaks, featured-id override and fallback, category
  top/additional split, search filtering, validation rules, error-to-envelope mapping.
- **Backend integration** — `POST /enquiries` against a stubbed SMTP: 2xx only after
  acceptance, `EMAIL_SEND_FAILED` on rejection, backend-resolved price in the email,
  validation error shape, rate limiting.
- **Client unit** — use cases, ViewModel state transitions, envelope-to-result mapping,
  recently-viewed LRU and de-dupe, draft restore honouring submitted state. All in
  `commonMain`, so no device is needed.
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
