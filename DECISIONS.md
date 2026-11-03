# Decisions and Assumptions

This file is maintained incrementally as decisions are made, not reconstructed at the
end. See [AGENTS.md](AGENTS.md) for the working agreement behind that.

**This file holds the *why*.** The resulting specification — screen states, API contracts,
validation rules, error codes and acceptance criteria — is in [SPEC.md](SPEC.md). Where
the two overlap, SPEC.md is the buildable statement and this file is the reasoning and
the rejected alternatives behind it.

## Stack choice

- **Backend: Node.js + TypeScript.** A thin, fast-to-scaffold JSON API layer in front of
  the Fake Store API, with a repository interface so the data source can be swapped
  later without touching routes or the client contract. Easy to bring up: a single
  `docker compose up` starts the API and the local email catcher.
- **Client: Kotlin Compose Multiplatform (CMP), Android as the primary target.** See
  below for the full justification — this deviates from reading the challenge's client
  table literally, so it's called out explicitly.

## Client table interpretation (CMP)

The permitted client options include a native Android app in Kotlin or Java; Compose
Multiplatform is not named among them. Chose Compose Multiplatform with Android as the primary
supported client. This allows UI and business logic to be reused across Android and iOS
as the product evolves. For the PoC, Android is the target deliverable to minimize setup
complexity and stay within the time-box. iOS support can be completed and validated
independently as a future enhancement.

This is a deliberate interpretation rather than a silent assumption: CMP's Android
target is a first-class native Kotlin Android app, satisfying the letter of the "Kotlin"
option, while setting up the project to honor the brief's own premise that the stack
is expected to evolve.

## Architecture

- **Backend:** ports/adapters at the two boundaries the brief says will change —
  `ProductRepository` (→ Fake Store adapter today) and `EmailService` (→ local Mailhog
  adapter today). Swapping either later is a new adapter, not a rewrite.
- **Client:** Clean Architecture + MVVM (`data` / `domain` / `presentation` / `ui`),
  kept to a single Gradle module with clean package boundaries rather than a true
  multi-module split. Splitting into multiple Gradle modules is a reasonable future
  shape if/when dependency boundaries or team ownership actually demand it — not an
  inherent correctness upgrade over a single module. It adds real build-configuration
  overhead that isn't justified for ~5 screens in a 2–3 hour box. Documented as a
  deliberate cut, not an oversight — revisit if the app grows past this screen count or
  gets a second team working on it concurrently.
- **DI:** Koin — idiomatic for CMP, minimal ceremony for a small screen count.
- **Networking:** Ktor client — Retrofit isn't Kotlin-Multiplatform-compatible, so Ktor
  keeps the same client working across Android and the (planned) iOS target.
- **Navigation:** Navigation Compose Multiplatform.

## Design system

Planned: a lightweight design system using Compose Material 3, with centralized theme
tokens and reusable application components (button, text field, product card, search
bar, section header, shimmer placeholder, loading/error/empty states).

This isolates visual decisions from feature implementation, allowing the product's
branding and visual identity to evolve — directly addressing the brief's statement that
"look and feel" is expected to change.

Deliberately avoiding a standalone design-system Gradle module and an extensive
component library because the current PoC does not justify that complexity. The
components will live in `ui/designsystem` within `commonMain` as a dedicated package,
which is a one-step extraction to a real module later if/when reuse pressure justifies
it.

## User journey and navigation

Settled before any scaffolding, screen by screen, rather than inferred from the feature
list. Walking the flow surfaced gaps the feature list had hidden (see
[AI_USAGE.md](AI_USAGE.md) for what it caught). Specified in full, with per-screen states
and acceptance criteria, in [SPEC.md](SPEC.md) §7.

```
Splash (platform API)
  └─ Home ──────────────────┬─ [View all]       → Category
     • search bar           ├─ [search submit]  → Search Results
     • Recently viewed      ├─ [product tile]   → Product
     • Top rated / Featured └─
     • A shelf per category

  Category / Search Results ─ [tile] ──→ Product

  Product ─ [Enquire] ──→ Enquiry ──→ Thank you ──→ (pops back to Product)
```

- **Splash** — the platform splash screen API, not a custom timed screen. It dismisses as
  soon as Home's first frame is ready. A deliberate delay would add friction for no
  benefit.
- **Loading** — shimmer placeholders per section while Home loads. "Recently viewed" is
  local, so it renders instantly while the networked sections shimmer.
- **Search lives on its own screen**, not as an in-place mutation of Home. Home keeps its
  state, results get their own back-stack entry, and back from a product returns to the
  results rather than a reset Home.
- **Enquiry is a full screen**, not a bottom sheet — it has four fields, validation, a
  loading state and a terminal success state; a sheet makes keyboard handling and error
  recovery harder for no gain.
- **Thank-you is a state of the enquiry route**, not a separate destination, and pops back
  to Product. Back can never re-enter a submitted form.
- **Failure keeps the user on the form** with input intact, a retry, and a plain-language
  message — this is the case that makes "confirm only after SMTP acceptance" worth having.

## Designed for deeplinks, not deeplinked

Deeplinks are not implemented — there is no domain or hosting, so App Links / iOS
associated domains are out of scope. But three choices make them additive later instead
of a refactor, at zero cost today:

1. **Routes carry primitives only** — product id, category slug, search query. Never a
   whole `Product` object passed between destinations.
2. **The product screen loads from an id alone**, fetching its own data rather than
   receiving an object handed down from the list it was opened from. This is the load-
   bearing one: a product screen that depends on caller-supplied data cannot be
   deeplinked without a rewrite.
3. **Category names are slugged** (`mens-clothing`), with a slug ↔ name map on the
   backend. Fake Store's own names contain a space and an apostrophe (`men's clothing`),
   which are a reliable source of path-encoding bugs; slugs avoid them and read correctly
   as a URL.

## Category surfacing

**Every category is a shelf on Home** — a section header, a horizontal row of up to five
products, and a **View all** into the category screen. Without a home-screen surface the
category page is unreachable; the feature list said it opens "from a category link" but no
screen hosted one.

A chip row that filtered in place was the alternative, and it is worse on two counts.
Interaction: four categories would sit behind a tap, and selecting one would need a
request, a loading state, a per-slug cache and a failure state — four pieces of machinery
on the screen that must never look broken. Content: with one shelf visible, Home is two
sections deep and has almost nothing to scroll. Shelves put the whole catalogue on the
first screen, remove every one of those states, and leave the section headers as the only
navigation — a chip row *and* headers would be two ways to reach the same four
destinations.

**This is what makes Home two calls rather than one.** `GET /home` returns the global
shelf; `GET /categories` returns every category with the head of its ranking. They are
fired in parallel, neither waiting on the other, and each owns its own sections — so one
can fail while the rest of the screen stays usable. Because every shelf's products arrive
together, **no interaction on Home costs a request**: after first paint, scrolling and
browsing are free. Contracts in [SPEC.md](SPEC.md) §4.1–§4.2, states in §7.2.

The cost is bounded and measured. Four categories of five is 18 cards; the whole upstream
catalogue is 10.6 KB of JSON in one call. It stops being free at roughly ten categories,
where the answer is to load shelves lazily as they scroll into view — which a
section-per-category layout supports without restructuring, and a chip filter would not
have.

A shelf is a selection, not the category. Electronics and women's clothing hold six
products each, so one product in each is reachable only through search or View all.
Raising the shelf to six would render this catalogue in full and make View all pointless.

## API contract

A single fixed response envelope on every endpoint, success or failure, so the client
always knows the shape it is parsing:

```json
{ "success": true,  "data": { }, "error": null }
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "userMessage": "Please check the highlighted fields.",
    "developerMessage": "email failed regex validation",
    "traceId": "01JBQ7F3K2",
    "fieldErrors": { "contact.email": "Enter a valid email address" }
  }
}
```

- `success` is a boolean, not a status string. The HTTP status code keeps its own meaning
  (200 / 400 / 404 / 422 / 502) so the envelope does not fight HTTP.
- `code` is a stable enum (`VALIDATION_FAILED`, `EMAIL_SEND_FAILED`,
  `UPSTREAM_UNAVAILABLE`, `NOT_FOUND`) and is the only field the client branches on. Copy
  gets reworded; codes do not. It is what makes "retry vs. edit and resubmit" decidable.
- `userMessage` is the single user-facing string — always present on error, always safe to
  display. Deliberately one field, not three: an earlier draft had `message`,
  `userMessage` and `errorMessage` together, which left the client unable to tell which to
  render.
- `developerMessage` is sanitized at the point it is produced — a short, stable category
  of failure, never an exception, stack frame, upstream body or internal path. It ships
  in the response body, so the guarantee has to live in the backend; declining to render
  it client-side would not stop anyone reading it. Raw diagnostics stay in server logs,
  reachable via `traceId`.
- `fieldErrors` is keyed by the **dotted request path** (`contact.email`), so the enquiry
  form can place an error under the input that caused it without a mapping table. A
  single error string cannot do this.
- `traceId` correlates a customer-visible failure with the server log holding the real
  cause — which is what lets `developerMessage` stay sanitized without losing
  diagnosability.
- **List endpoints return `ProductSummary`, the product screen returns `Product`.** The
  only difference is `description`, which no card renders and which is 60% of a list
  payload. Deliberately two fixed shapes rather than one shape and a `?lightweight=true`
  flag: a flag makes the response conditional, so `description` becomes optional on a type
  the product screen requires it on, the DTO stops describing what a caller will receive,
  and every endpoint needs contract tests twice. The honesty matters more than the 6 KB —
  an endpoint whose callers render cards should not return a field no card can show.
- `data` and `error` are mutually exclusive and both always present with one null, so the
  client is one generic `ApiResponse<T>` with nullable fields — no polymorphic
  deserializer — mapped once into a sealed result type that every repository reuses. That
  reuse is the actual payoff of a fixed envelope.
- **`userMessage` origin:** the backend always supplies it, so copy can change without a
  client release (consistent with the backend owning ranking). The client keeps its own
  fallback copy for transport failures, because "no response" means there is no envelope
  to read.

### Endpoints

| Endpoint | Returns |
|---|---|
| `GET /home` | `{ featured: { source, products[] } }` |
| `GET /categories` | `{ categories: [{ category: { slug, name }, topRated[] }] }` |
| `GET /categories/:slug/products` | `{ category: { slug, name }, topRated[], additional[] }` |
| `GET /products/search?q=` | `{ query, results[] }` |
| `GET /products/:id` | a single product, the only one carrying `description` |
| `POST /enquiries` | 2xx only once SMTP has accepted the message |

- **Home is two calls in parallel**, not one aggregate. An earlier shape folded the
  category list into `/home`; putting a shelf of products under every category made that
  aggregate the whole screen, and splitting it buys three things: the category list lives
  in exactly one response and cannot disagree with itself, neither call blocks the other,
  and each owns its own sections so one can fail while the rest of Home stays usable.
  Reasoning in full under "Category surfacing" above.
- **`featured.source` tells the client which rule produced the shelf** — `computed` or
  `curated` — so it can head the section "Top rated" or "Featured". Without it a curated
  list sits under a heading claiming a ranking it does not have:
  `FEATURED_PRODUCT_IDS=8,4` resolves to the two lowest-rated products in the catalogue.
  The backend sends the discriminator, never the copy.
- **Ranking happens on the backend**, which returns pre-ranked, pre-split payloads. The
  ranking rule is business logic, and the brief's premise is that things change — if "top
  rated" later means rating x review count, or becomes editorially curated, that ships as
  a backend change with no client release. It also keeps the client a dumb renderer, which
  is the reason for wrapping Fake Store at all.
- **Reads are GET, writes are POST.** `GET /products/:id` and `GET /products/search?q=`
  are safe, idempotent and cacheable; search uses a query param rather than a POST body so
  it stays cacheable and addressable. `POST /enquiries` creates a resource and sends mail,
  so it is correctly non-idempotent.

## Client-local persistence

Two small local stores, no backend involvement:

- **Recently viewed** — a row above the categories on Home, populated as products are
  opened. Deliberately client-local with zero endpoints: there is no auth or user account
  (see below), so a server-side list would have nothing to key on but an invented device
  id, and it would need the persistence layer this PoC drops. It is per-device UI state by
  nature. Local also means it renders instantly with no network call and works offline,
  which is the point.
  Stores a denormalized display snapshot (`id, title, image, price`) rather than ids
  alone — ids would force N fetches or a new batch endpoint to render the row. Capped at
  10, LRU, de-duped on re-view. The trade-off is that a stored price can go stale; tapping
  through fetches fresh data, so a stale value never reaches a decision point.
- **Enquiry contact details** — name, email and phone are saved on a successful submission
  and reused. The message is never saved *to this store*: it is product-specific, and
  restoring a previous product's message onto a different enquiry would be actively
  confusing. It is held in the separate ephemeral draft that survives process death only,
  so a half-typed message is not lost to a backgrounded app. The two stores are easy to
  conflate and are set out side by side in [SPEC.md](SPEC.md) §7.6.

A server-side recently-viewed list would be a different feature — telemetry — and is noted
under next steps rather than squeezed into the time-box.

**Mechanism: androidx DataStore** (Preferences, with the two payloads serialized as
JSON), chosen over `multiplatform-settings`. DataStore is Kotlin-Multiplatform-capable,
so the stores live in `commonMain` alongside the rest of the shared code rather than
needing a per-platform shim when iOS is completed. It is `Flow`-based, which composes
directly with the `StateFlow` convention used everywhere else — a change to the saved
contact details propagates to the enquiry screen without a manual refresh — and it is
async and transactional by design, so a write during process death cannot leave a
half-written record.

`multiplatform-settings` is lighter and would have been quicker to wire for two small
stores. It was rejected because its synchronous key-value API would have sat awkwardly
against the reactive flow used by every other data source, and the saving is measured in
minutes.

## Enquiry friction

The user can only enquire, never order, so the enquiry is the one conversion point in the
app and is worth optimizing hard.

- **First enquiry** — the full form.
- **Every one after** — a collapsed summary ("Enquiring as <name> - <email>") with an
  **Edit** affordance, the optional message field, and Submit. One tap to send.
- **Consent is explicit:** a "Remember my details" checkbox, default on and visible.
  Unchecking it clears the store. These are the user's contact details — persisting PII,
  even only on-device, is a recorded choice rather than a silent side effect.
- Server-side validation runs regardless of prefill; prefilled values are not trusted.
- The enquiry payload carries the product's **id only**. Sales still needs to know what
  the enquiry is about, so the backend resolves title and price itself and puts them in
  the email — the context is kept, the client's authority over it is not.

## Accessibility and one-handed use

- **Primary CTAs sit in a sticky bottom bar** within thumb reach — Product's "Enquire",
  Enquiry's "Submit" — not inline at the end of scrollable content. Both need
  `imePadding()` and `navigationBarsPadding()` so the button sits above the keyboard, and
  scroll content needs bottom padding so its last item is not occluded.
- **The top app bar holds only secondary actions** (back, search entry). Back is also a
  system gesture, so top-left being outside thumb reach is acceptable.
- **48dp minimum touch targets**, enforced in the design-system components rather than
  applied per screen.
- **Category shelves scroll into the reachable band** rather than occupying a fixed
  position. Nothing on Home needs a precise tap: a shelf's tiles and its View all are both
  large targets, and the one control that must always be reachable — Product's "Enquire" —
  is pinned instead.
- Beyond reachability: `contentDescription` on product images and icon buttons, section
  titles as semantic headings, **labels on form fields rather than placeholder-only**
  (placeholders vanish on focus and are read unreliably), errors as `supportingText`
  announced via a live region, and no fixed-height text containers so a large font scale
  does not clip.

## State persistence

Three distinct problems, deliberately not solved with one mechanism:

| Case | Approach |
|---|---|
| Rotation / configuration change | Free — a ViewModel with `StateFlow` scoped to the nav entry survives it. |
| Process death, "don't keep activities" | `SavedStateHandle`, applied only to the search query and the enquiry form draft. |
| Returning to Home from a product | The Home payload is cached in the repository so Home does not re-shimmer. |

Engineered scroll-position restoration across process death is deliberately skipped;
`rememberLazyListState` covers rotation, which is the case users actually hit.

## Feature-level assumptions

- **"Top rated"** — top 5 products globally by `rating.rate` on the home page; top 5 by
  `rating.rate` within each category on the category page (same rule, scoped to the
  category), with "additional products" being the remainder of that category.
- **Category page edge case** — if a category has 5 or fewer products, all of them
  appear in the "top rated" section and the "additional products" section is simply
  omitted (not rendered as an empty section).
- **Search** — case-insensitive substring match against product title and description.
  **No relevance ranking**: no fuzzy matching, no term weighting, no scoring of where or
  how often the term appears — that is out of scope for a PoC. Results are still
  *ordered*, by the same deterministic rating rule used everywhere else, so output is
  reproducible and therefore testable. "Not ranked" means no relevance model, not
  arbitrary order.
- **Email provider** — local SMTP catcher (Mailhog) via docker-compose, so the "sales
  inbox" email is visible with zero signup, behind the same `EmailService` interface a
  real provider would implement later.
- **Enquiry fields** — name and email required; phone and message optional.
  Server-side validation on required fields and email format. The request identifies the
  product by **id only**; the backend resolves title and price itself rather than
  trusting the client, so a caller cannot have an invented price emailed to sales as
  fact.
- **Sales inbox** — a single fixed address from backend env config, not a real mailbox.

## Testing

Tests cover the **critical path** and exist to make the code safe to change, not to hit a
coverage number. The full matrix is in [SPEC.md](SPEC.md) §11.

- **Backend-heavy, client-core.** Backend gets unit tests over the rules most likely to
  change (ranking and tie-breaks, featured-id override, category split, search filtering,
  validation, error-to-envelope mapping) plus an integration test of the enquiry endpoint
  against a stubbed SMTP. The client gets unit tests over use cases, ViewModel state
  transitions, envelope mapping and the recently-viewed LRU — all in `commonMain`, so
  they run without a device.
- **Compose UI instrumentation tests are deliberately cut.** They need an emulator, are
  slow to write and slower to run, and would consume the budget that unit coverage of the
  rules uses better. The cost is that layout regressions are caught by eye rather than by
  CI. Recorded as a trade-off, not an omission.
- **Deterministic ranking exists partly for testability.** "Top 5 by rating" is untestable
  when rates tie, so the tie-breaks (`rate` desc → `count` desc → `id` asc) are part of
  the contract rather than an implementation detail.

## Security posture

`POST /enquiries` is unauthenticated and causes email to be sent, which makes it the one
genuinely abusable surface in the system. Treating it as "just a form endpoint" would
leave an open relay. Specified in [SPEC.md](SPEC.md) §10.3; the reasoning:

- **Rate limiting counts attempts, not successes**, so repeated validation failures can't
  be used to get around it.
- **Email body values are escaped.** The enquiry text reaches a human inbox; unescaped
  input there is an injection vector pointed at our own staff.
- **`developerMessage` is sanitized where it is produced.** An earlier draft claimed no
  stack trace could reach a customer *because the client doesn't render that field* —
  which is simply false, since the field ships in the response body. A guarantee that
  depends on the client choosing not to look is not a guarantee. The field is now a short
  stable string, with raw diagnostics logged server-side against a `traceId`.
- **Upstream calls are bounded** by a timeout and a limited retry, so a hung Fake Store
  degrades us rather than exhausting us.
- **CORS is an env allowlist, not `*`.**

Not done, and deliberately: no auth, no CAPTCHA, no bot detection. There are no accounts
to protect, and a CAPTCHA on the one conversion point in a PoC costs more than the spam
it would prevent at this scale.

## "Top rated" as a configurable list

The stated criterion describes the five featured products as ones that have been
*chosen*, which reads as curation rather than computation. Rather than pick one reading
and hope, `FEATURED_PRODUCT_IDS` is an env var: set it and those products are featured in
that order; leave it unset and the backend computes the top 5 by rating.

This costs one environment variable and satisfies both readings. It also demonstrates the
premise that the product data source is expected to change — the entrepreneur can
re-curate the home page without a deploy, let alone a client release. Ids that no longer
resolve are skipped and logged rather than returning an error, so a stale config degrades
the row instead of breaking the home page.

## Upstream caching

The product list is cached in memory with a short, configurable TTL. Two reasons, one
functional and one about the review itself:

- Ranking needs the whole catalogue, so without a cache every single request re-fetches
  all products from Fake Store.
- If Fake Store is slow or unreachable while someone is running this, a warm cache keeps
  every screen working instead of failing all of them. An external dependency that can
  take the entire demo down is a real risk, and the mitigation is nearly free.

No cache invalidation beyond the TTL — the upstream catalogue is static and this is a
PoC. A real deployment would want a shared cache rather than per-instance memory.

**The enquiry path reads the same cache**, which means the price emailed to sales can be
up to one TTL old, and a product removed upstream inside that window can still be
enquired about. That is accepted rather than engineered around: resolving the product
server-side exists to stop a *client* asserting a false price, not to guarantee
real-time pricing against a catalogue that does not change. Giving the enquiry its own
uncached path would put an upstream call on the single conversion flow and make the most
important request the most fragile. If prices ever became volatile, the fix is a shorter
TTL or a revalidation step — both local changes.

When the upstream is unreachable and cached data exists, it is served stale up to a
bounded maximum rather than failing. Degrading beats a dead catalogue; past that bound
the data stops being useful and the request fails honestly.

## Android emulator networking

The Android emulator cannot reach the host machine at `localhost` — `localhost` inside
the emulator is the emulator. The host is `10.0.2.2`. Separately, Android 9+ blocks
cleartext HTTP by default, so `http://10.0.2.2:8080` fails silently without a debug
network-security configuration.

Both are specified and documented in the README setup, because someone following those
steps on a clean machine would otherwise hit two silent failures in a row with nothing
pointing at the cause. The base URL is a build-config field, defaulting to the emulator
address for debug builds, so pointing the app at a different backend needs no code edit.

## Dropped / simplified requirements

- No auth or user accounts.
- No persistence of submitted enquiries (sent by email only) — noted under "next steps."
- No pagination — Fake Store API's catalogue is small enough to return in full.
- No cart/checkout — not in the acceptance criteria.
- Minimal styling investment — look and feel is explicitly expected to change.
- iOS target planned to be scaffolded but not run/validated for this submission (see
  CMP justification above).
- No deeplink registration (intent filters, App Links, iOS associated domains) — no
  domain or hosting exists to verify against. The navigation is built to accept them
  later; see "Designed for deeplinks, not deeplinked" above.
- Recently viewed is not tracked server-side — that is telemetry, not this feature.
- No scroll-position restoration across process death.
- No Compose UI instrumentation tests — see "Testing" above for the trade-off.
- No CAPTCHA or bot detection on the enquiry form — see "Security posture".

## Infrastructure

No cloud infrastructure is provisioned for this PoC — everything runs locally via
docker-compose (the API and the Mailhog email catcher). If infrastructure is added
later, it will be AWS-compatible, consistent with the expectation that this is a
foundation to build on rather than a throwaway prototype.

## Time-box

The original target was 2-3 hours. Defining the user journey added work beyond the base
five screens: splash, two local stores, the two-tier enquiry flow, recently viewed,
sticky bottom CTAs, the response envelope plus client-side error mapping, and
`SavedStateHandle` wiring. Estimated at 4-5 hours.

Tests are inside that estimate, not on top of it — they are part of the deliverable
rather than an extension of it.

This was flagged before building rather than discovered mid-build, and the overrun was
accepted deliberately: the friction-reduction work (saved contact details, recently
viewed, one-handed layout) is the most substantive part of the solution, and cutting it
to protect the box would have produced a less interesting result. The alternative — had
the box been held firm — was to drop recently viewed and the saved-form reuse and list
both as next steps. Actual time spent is recorded in [README.md](README.md).

## How this supports growth beyond the PoC

- Swapping the product data source, email provider, or client look-and-feel is an
  adapter/package change behind an existing interface, not a rewrite.
- CMP's `commonMain` business logic (domain + presentation layers) is designed to be
  shared across Android and iOS once built, so completing the iOS target is additive,
  not a parallel build.

## What I'd do next with another day

- Persist submitted enquiries (e.g., to a database) instead of relying on email as the
  only record of a submission.
- Build and validate the iOS target end-to-end.
- Revisit the single-module client structure if reuse pressure or team size grows.
- Add Compose UI instrumentation tests over the critical screens (deliberately cut from
  this submission — see "Testing" below).
- Register deeplinks once a domain exists — the routes already accept them.
- Load category shelves lazily as they scroll into view, once the catalogue outgrows a
  single `GET /categories` response.
- Serve resized thumbnails from our own backend. Upstream images are ~0.25 MP shipped as
  RGBA PNGs at a median 238 KB — Home pulls about 4.3 MB of them against 10.6 KB of JSON.
- Track product views server-side as telemetry, if that becomes a goal, rather than
  relying on the device-local recently-viewed list.
- Swap Mailhog for a real transactional email provider behind the existing
  `EmailService` interface.
