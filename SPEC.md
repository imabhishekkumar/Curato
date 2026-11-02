# SPEC.md — Curato Product Catalogue

Functional and technical specification. This is the **what**: screens, states, contracts,
validation rules, and acceptance criteria concrete enough to build and test against.

| | |
|---|---|
| **Status** | Agreed, pre-implementation |
| **Last updated** | 2026-11-03 |
| **Scope** | Backend API + Android client (Compose Multiplatform) |

**Companion documents** — read alongside, not instead of:

- [DECISIONS.md](DECISIONS.md) — the **why** behind everything here, with the
  alternatives that were rejected. Where this spec states a rule, that file states the
  reasoning. Do not re-open a choice here without reading it there first.
- [AGENTS.md](AGENTS.md) — the **rules that bind implementation**, including the
  non-negotiables (envelope shape, primitive-only routes, backend-owned ranking).
- [README.md](README.md) — **how to run** it, and the user-facing feature summary.
- [AI_USAGE.md](AI_USAGE.md) — how this spec was produced and reviewed.

---

## 1. Purpose and non-goals

A customer browses a product catalogue, finds a product, and submits an enquiry about it.
There is no ordering, so **the enquiry is the only conversion point in the product** and
is specified in the most detail.

**Non-goals**, each with its rationale in DECISIONS.md: authentication or user accounts,
cart or checkout, enquiry persistence (email is the only record), pagination, server-side
view tracking, deeplink registration, and a validated iOS build.

## 2. Glossary

| Term | Definition |
|---|---|
| **Top rated** | The highest `rating.rate` products, ranked and limited by the backend. Globally on Home; scoped to a category on the Category screen. |
| **Additional products** | The remainder of a category after its top rated are removed. |
| **Enquiry** | A customer's request for information about one specific product, delivered by email. |
| **Recently viewed** | Products this device has opened, newest first. Device-local; never sent to the backend. |
| **Envelope** | The fixed response wrapper every endpoint returns, success or failure. |

---

## 3. Domain model

Field names below are the **API contract** — the client's DTOs mirror them exactly, and
the upstream Fake Store shape is mapped to this at the adapter boundary, never leaked
through.

### Product

| Field | Type | Notes |
|---|---|---|
| `id` | int | Upstream id. Stable. |
| `title` | string | |
| `price` | number | Major units, 2dp. |
| `currency` | string | `"USD"` — upstream prices are USD; stated explicitly so the client never assumes a locale currency. |
| `description` | string | |
| `category` | `CategoryRef` | |
| `imageUrl` | string | Absolute URL. |
| `rating` | `{ rate: number, count: int }` | `rate` 0–5. |

### CategoryRef

| Field | Type | Notes |
|---|---|---|
| `slug` | string | URL-safe. The only form that ever appears in a path. |
| `name` | string | Display form, title-cased. |

**Slug map** (backend-owned, the single source of truth):

| Upstream name | `slug` | `name` |
|---|---|---|
| `electronics` | `electronics` | Electronics |
| `jewelery` | `jewelery` | Jewelery |
| `men's clothing` | `mens-clothing` | Men's Clothing |
| `women's clothing` | `womens-clothing` | Women's Clothing |

> **Upstream quirk, deliberately preserved:** Fake Store spells the category `jewelery`
> (one `l`). We title-case it for display but do **not** correct the spelling —
> re-labelling source data would make our catalogue diverge from the catalogue we are
> wrapping, for cosmetic gain. Revisit if a real product catalogue replaces Fake Store.

### Enquiry (request)

```json
{
  "productId": 1,
  "seenPrice": 109.95,
  "contact":   { "name": "Ada Lovelace", "email": "ada@example.com", "phone": "+91 9876543210" },
  "message":   "Is this available in black?"
}
```

| Field | Trust | Notes |
|---|---|---|
| `productId` | Validated | Required. The **only** product input. |
| `seenPrice` | **Untrusted** | Optional. The price displayed to the customer. Never authoritative. |
| `contact.*`, `message` | Validated | Per §6. |

**The backend resolves the product itself.** Title, price and category in the enquiry
email come from `GET`-ing the product by `productId` server-side — never from the request
body. An earlier draft accepted a client-supplied `title` and `price` while validating
only the id, which let any caller submit a real product with an invented price and have
the backend email it to sales as fact.

`seenPrice` exists only to detect drift: if it differs from the resolved price, the email
notes both ("customer saw $X; current price $Y"). It is never stored, echoed as
authoritative, or used in place of the resolved value. A missing or absurd `seenPrice` is
ignored, not rejected — it is a diagnostic, not an input.

---

## 4. API specification

Base URL is configured per environment. All endpoints return the envelope in §5.

**Upstream caching.** The product list is fetched from Fake Store and held in an
in-memory cache with a short TTL (`CACHE_TTL_SECONDS`, default 300). **Every endpoint
reads through it, `POST /enquiries` included.** Two reasons: ranking requires the whole
catalogue, so without a cache each request re-fetches it; and if the upstream is slow or
down, a warm cache keeps the app usable instead of failing every screen.

| Cache state | Behaviour |
|---|---|
| Fresh (within TTL) | Serve from cache. No upstream call. |
| Expired, refresh succeeds | Refresh synchronously, then serve. |
| Expired, refresh fails, data still held | **Serve the stale data**, up to `CACHE_MAX_STALE_SECONDS`, and log it. Degrading is better than failing. |
| Cold (no data) and refresh fails | `UPSTREAM_UNAVAILABLE`. |
| Stale beyond `CACHE_MAX_STALE_SECONDS`, refresh fails | `UPSTREAM_UNAVAILABLE` — there is a limit to how old is still useful. |

**Consequence, stated rather than hidden:** reads are *eventually* consistent with
upstream, bounded by the TTL. A product removed upstream can still be served, and
enquired about, for up to one TTL. This is accepted: server-side product resolution
exists to stop a **client** supplying false data, not to guarantee real-time pricing,
and the upstream catalogue is static. A deployment where prices actually move would
shorten the TTL or revalidate on the enquiry path.

### 4.1 `GET /home`

Home's entire networked payload in one call.

```json
{ "topRated": [ Product ], "categories": [ CategoryRef ] }
```

- **Selection is configurable.** The stated criterion describes the 5 featured products
  as ones that have been *chosen* — curation, not computation. Honouring both readings
  costs one env var, and demonstrates the premise that the product data source is
  expected to change.

  | `FEATURED_PRODUCT_IDS` | `topRated` contains |
  |---|---|
  | Unset (default) | The computed top 5 by rating — or all products when the catalogue holds fewer than 5. |
  | Set (e.g. `1,4,9,12,18`) | **Up to 5**, as configured: the ids that resolve, in the order given. |

  **The curated list is authoritative — it is never backfilled.** If the configured list
  yields three products, Home shows three. Topping it up with highest-rated products the
  curator did not choose would silently override the choice the config exists to express;
  showing fewer is the honest outcome of a short or stale config.

  **Normalization, in order:**

  1. Split and trim; drop entries that are not positive integers, with a warning log.
  2. De-duplicate — first occurrence wins, later repeats dropped.
  3. Take the first 5; log that the list was truncated if longer.
  4. Resolve each id; skip those that do not resolve, with a warning log naming the id.
  5. **If nothing resolves, fall back to the computed top 5.** Home is never empty, and
     a fully stale config degrades to a sensible default rather than a blank screen.
  6. Otherwise return what resolved, in configured order, however few that is.

  A malformed or stale config is a logged warning, never a 5xx — the home page is the
  first thing anyone sees, and it must not be takeable down by a typo in an env var.
- **Ranking (deterministic):** `rating.rate` desc → `rating.count` desc → `id` asc. The
  tie-breaks exist so the response is reproducible and therefore testable.
- Errors: `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.2 `GET /categories/{slug}/products`

```json
{
  "category":   { "slug": "mens-clothing", "name": "Men's Clothing" },
  "topRated":   [ Product ],
  "additional": [ Product ]
}
```

- `topRated` — up to 5, same ranking rule scoped to the category.
- `additional` — the remainder, `id` asc. **An empty array when the category holds 5 or
  fewer products**; the client omits the section entirely rather than rendering it empty.
- Unknown slug → `NOT_FOUND`.
- Errors: `NOT_FOUND`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.3 `GET /products/search?q={query}`

```json
{ "query": "bag", "results": [ Product ] }
```

- Case-insensitive substring match against `title` **and** `description`.
- `q` is trimmed; **minimum 2 characters** after trimming, else `VALIDATION_FAILED` with
  `fieldErrors.q`. The client disables submission below 2 characters, so this is a
  defence, not a normal path.
- No match → `200` with `results: []`. An empty result is a successful search, not an
  error.
- Results ordered by the standard ranking rule, so the best-rated match leads.
- Errors: `VALIDATION_FAILED`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.4 `GET /products/{id}`

Returns one `Product`. Unknown id → `NOT_FOUND`.

This endpoint is load-bearing for deeplink-readiness: the product screen resolves its own
data from an id alone and never depends on data passed by the list that opened it.

### 4.5 `POST /enquiries`

Request body per §3. Response:

```json
{ "acceptedAt": "2026-11-03T10:15:30Z" }
```

- **A 2xx is returned only after the SMTP server has accepted the message.** Never on
  receipt of the request. This is what makes the client's "Thank you" truthful.
- Processing order: validate fields → **resolve the product server-side** → compose →
  send → respond. The product is resolved before sending, so an enquiry about a product
  the backend no longer knows fails before an email goes out.
- Resolution reads through the cache like every other endpoint. The email therefore
  carries **the price the backend holds, which may be up to one TTL old** — authoritative
  against the client, not against the instant. A product removed upstream within the
  current TTL may still be accepted. See the cache table at the top of §4.
- No reference id is returned: nothing is persisted, so an id the customer could not look
  up would be theatre.
- Validation runs server-side on every submission, including values the client prefilled.
- Recipient is a single fixed address from backend env config.
- Request values are escaped when composing the email body (§10.3).
- Rate limited per §10.3.
- Errors: `VALIDATION_FAILED`, `NOT_FOUND` (unknown `productId`), `UPSTREAM_UNAVAILABLE`
  (product resolution failed), `EMAIL_SEND_FAILED`, `RATE_LIMITED`, `INTERNAL_ERROR`.

### 4.6 `GET /health`

```json
{ "status": "ok", "upstream": "reachable", "smtp": "reachable" }
```

Unauthenticated, no envelope-breaking special cases — it returns the standard envelope
like everything else. Used by the docker-compose healthcheck and by a reviewer
confirming the stack is up before opening the app. Returns `200` when the service itself
is healthy even if a dependency is degraded; the body reports which.

---

## 5. Response envelope

Every endpoint, success and failure alike.

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
    "developerMessage": "contact.email failed format validation",
    "traceId": "01JBQ7F3K2",
    "fieldErrors": { "contact.email": "Enter a valid email address" }
  }
}
```

- `data` and `error` are mutually exclusive, both always present, exactly one null.
- **No response leaves the service without passing through the envelope serializer.**
  A catch-all error handler converts every unhandled exception *and every
  framework-generated rejection* — malformed JSON, an oversized body, an unknown route,
  a wrong verb — into this shape. Without it the universal contract is only true of the
  paths we remembered to write, and the client's single `ApiResponse<T>` parser fails on
  exactly the responses it has no other way to interpret.
- `fieldErrors` is omitted unless the failure is field-level. Keys are **dotted request
  paths** (`contact.email`), so the client maps an error to an input without guessing.
- `traceId` correlates the response with the server log that holds the full diagnostic.
  It is the only safe way to get from a customer-visible failure to the real cause.
- **`developerMessage` is a short, sanitized, stable string** — a category of failure,
  not a dump. It is part of the response body and therefore readable by anyone who can
  call the API, so it must never carry an exception message, a stack trace, an upstream
  response body, a SQL or HTTP detail, a file path, or a config value. Raw diagnostics
  are logged server-side against the `traceId` and never serialized.

  | | |
  |---|---|
  | Acceptable | `"contact.email failed format validation"`, `"upstream product fetch timed out"` |
  | Never | `"ECONNREFUSED 10.0.0.4:587"`, `"TypeError: Cannot read properties of undefined"`, any stack frame |

  An earlier draft of §10.3 claimed no stack trace could reach a customer *because the
  client does not render this field*. That reasoning was wrong: not rendering a field
  does not stop it being in the payload. The guarantee has to be enforced where the
  string is produced.

### 5.1 Error code registry

| `code` | HTTP | Raised when | Required client behaviour |
|---|---|---|---|
| `VALIDATION_FAILED` | 422 | Field validation failed | Show `fieldErrors` inline against each input. **Preserve all input.** Never navigate away. |
| `NOT_FOUND` | 404 | Unknown product id or category slug, **or an unknown route** | Full-screen error state with a back action. |
| `MALFORMED_REQUEST` | 400 | Body is not parseable JSON, or `Content-Type` is wrong | Generic error. Indicates a client bug, not user input. |
| `PAYLOAD_TOO_LARGE` | 413 | Body exceeded the size cap and was rejected before parsing (§10.3) | Generic error with the size limit in `userMessage`. |
| `METHOD_NOT_ALLOWED` | 405 | Known path, wrong verb | Generic error. Client bug. |
| `EMAIL_SEND_FAILED` | 502 | SMTP rejected or was unreachable | Stay on the enquiry form, input intact, with a Retry action. |
| `UPSTREAM_UNAVAILABLE` | 503 | Fake Store unreachable or erroring | Section-level or screen-level error with Retry. |
| `RATE_LIMITED` | 429 | The per-IP enquiry limit in §10.3 was exceeded | Retry action; **never auto-retry**. |
| `INTERNAL_ERROR` | 500 | Anything unexpected | Generic error with Retry. Surface the `traceId` in a copyable form so a report can be tied to a log line. |

**Client-side-only codes** — no envelope exists to read, so the client synthesises these
and supplies its own copy:

| Code | Raised when |
|---|---|
| `NETWORK_UNAVAILABLE` | No connectivity |
| `TIMEOUT` | Request exceeded its deadline |
| `UNEXPECTED_RESPONSE` | Body did not parse as the envelope |

The client branches on `code` only — **never on message text**.

**Which strings are shown to a customer:**

| Field | Shown? |
|---|---|
| `userMessage` | Yes — the only *general* error string displayed. |
| `fieldErrors` | Yes — rendered inline against the input each key names. |
| `traceId` | Only on `INTERNAL_ERROR`, in a copyable form, so a report can be tied to a log line. |
| `developerMessage` | **Never.** |

---

## 6. Validation rules

Enforced server-side (authoritative) and mirrored client-side for immediate feedback.

| Field | Required | Rule | `fieldErrors` message |
|---|---|---|---|
| `contact.name` | Yes | Trimmed, 2–80 chars | "Enter your name" |
| `contact.email` | Yes | Trimmed, ≤254 chars, valid format | "Enter a valid email address" |
| `contact.phone` | No | If present: 7–20 chars, digits and `+ - ( ) space` only. Valid: `+91 9876543210`, `(020) 7946 0991` | "Enter a valid phone number" |
| `message` | No | ≤1000 chars | "Message is too long" |
| `productId` | Yes | Present, integer, > 0 | "Select a product" |
| `seenPrice` | No | If present: numeric, > 0. **Ignored when absent or implausible — never rejected** (§3) | — |
| `q` (search) | Yes | Trimmed, ≥2 chars | "Enter at least 2 characters" |

**Validation is not resolution.** Whether a `productId` is well-formed and whether it
resolves to a product are different questions with different failures:

| Condition | Code | Response |
|---|---|---|
| `productId` missing, non-integer, or ≤ 0 | `VALIDATION_FAILED` | `fieldErrors.productId`; the user is on a broken client |
| Well-formed `productId` that does not resolve | `NOT_FOUND` | **No `fieldErrors`** — nothing the user can retype fixes it, and the client must take the non-retryable path in §7.6 |

Conflating the two would offer an inline "fix this field" error for a product that has
been removed from the catalogue.

Client-side validation runs **on blur and on submit**, not per keystroke — mid-typing
errors are noise. Server messages take precedence when both exist.

---

## 7. Screen specifications

Every screen implements four states — **loading, content, empty, error** — from
`ui/designsystem`. A screen with no specified empty state cannot reach one.

### 7.1 Splash

The platform splash screen API; dismissed as soon as Home's first frame is ready. No
timed delay, no custom screen, no branding animation.

**AC-1** No artificial delay is introduced between launch and Home.

### 7.2 Home — `home`

| Section | Source | Empty behaviour |
|---|---|---|
| Search bar | — | Always shown. Tapping navigates to Search. |
| Recently viewed | Device-local | **Section hidden entirely** when the store is empty — i.e. on first run. |
| Top rated (5) | `GET /home` | Error state with Retry if the call fails. |
| Categories (chips) | `GET /home` | Horizontal chip row, in the thumb-reachable band. |

- Loading: shimmer per section. Recently viewed renders instantly (no network), so it is
  visible while the rest shimmers.
- The payload is cached, so returning from a product does **not** re-shimmer Home.
- Section order is fixed: search → recently viewed → top rated → categories.

**AC-2** With `FEATURED_PRODUCT_IDS` unset, Home shows the computed top 5 — or all
products when the catalogue holds fewer than 5.
**AC-2a** With it set, Home shows the configured ids that resolve, in configured order,
and no others.
**AC-2c** A list containing duplicates shows each product once, at its first position.
**AC-2d** A list longer than 5 shows only the first 5.
**AC-2e** A list where some ids no longer resolve shows the remainder — fewer than 5,
never backfilled with unconfigured products.
**AC-2f** A list where no id resolves falls back to the computed top 5; Home is never
empty and never 5xx.
**AC-2b** Ranking is deterministic: two products with an equal `rate` order by `count`
desc, then `id` asc.
**AC-3** Recently viewed is absent on first run and present, newest-first, after viewing a
product.
**AC-4** Returning to Home from a product shows content immediately, not a shimmer.
**AC-5** A failed `GET /home` shows a retryable error, not a blank screen.

### 7.3 Category — `category/{slug}`

- Title is `category.name` from the response, never derived from the slug client-side.
- "Top rated in {name}" (up to 5), then "More in {name}" (the remainder).
- The "More in" section is **omitted** when `additional` is empty.
- Unknown slug → `NOT_FOUND` error state with back.

**AC-6** A category with ≤5 products renders only the top-rated section, with no empty
second section.
**AC-6a** With more than 5 products, `topRated` holds 5 and `additional` holds the rest;
the two sets are disjoint and together equal the category's full contents.
**AC-6b** Every product shown belongs to the requested category.
**AC-6c** An unknown slug yields `NOT_FOUND`, not an empty category page.

### 7.4 Search — `search?q={query}`

- Its own destination, not an in-place mutation of Home. Home retains its state, and back
  from a product returns to **these results**.
- Submission is disabled below 2 characters.
- Empty results → an empty state naming the term searched ("No products match 'xyz'"),
  with the query still editable. Not an error state.
- The query survives process death via `SavedStateHandle`.

**AC-7** Back from a product opened from search returns to the populated results.
**AC-8** A search with no matches shows an empty state, not an error.
**AC-8a** A term matching a product's title returns that product; a term matching only
its description also returns it.
**AC-8b** Non-matching products are absent from the results — the screen never shows the
full catalogue when a term was entered.
**AC-8c** Matching is case-insensitive, and the term is trimmed before matching.

### 7.5 Product — `product/{id}`

- Resolves its own data from `GET /products/{id}`. **Must not** accept a product object
  from the caller.
- Shows image, title, price with currency, category, rating (`rate` and `count`),
  description.
- **"Enquire" is pinned to a sticky bottom bar** within thumb reach, never inline at the
  end of the scroll. Scroll content is bottom-padded so nothing hides behind it.
- On successful load, the product is recorded to the device-local recently-viewed store.
- Unknown id → `NOT_FOUND` error state with back.

**AC-9** The product screen loads correctly when entered with only an id.
**AC-10** "Enquire" is reachable without scrolling, at any content length.
**AC-11** Viewing a product adds it to the front of recently viewed, de-duplicated.

### 7.6 Enquiry — `product/{id}/enquire`

A full screen, not a sheet. Two modes:

**First enquiry (no saved details)** — the full form: name*, email*, phone, message, plus
a **"Remember my details" checkbox, default on and visible**.

**Repeat enquiry (saved details present)** — a collapsed summary, "Enquiring as {name} ·
{email}", with an **Edit** action that expands the full form, plus the message field and
Submit. The target is a single tap to send.

- Submit sits in a sticky bottom bar with `imePadding()` and `navigationBarsPadding()`, so
  it stays above the keyboard.
- Submitting shows an in-place loading state; the button is disabled and non-re-entrant.
- **Success:** on 2xx, the thank-you state replaces the form content. It is a state of
  this route, not a destination, so back from it returns to Product and can never
  re-enter a submitted form.

#### Failure handling, per code

Not every failure is retryable, and offering Retry where retrying cannot help is worse
than offering nothing. An earlier draft made every non-validation error retryable,
which would have offered "Retry" for a product that no longer exists.

| `error.code` | Retryable? | Behaviour |
|---|---|---|
| `VALIDATION_FAILED` | n/a | Inline `fieldErrors` under each input. Stay on the form, all values intact. |
| `NOT_FOUND` | **No** | The product no longer exists — unchanged input cannot fix it. Show "This product is no longer available", with a single action back to Product. No Retry. |
| `EMAIL_SEND_FAILED` | Yes | Stay on the form, values intact, Retry. |
| `UPSTREAM_UNAVAILABLE` | Yes | Same as above — the catalogue was unreachable while resolving the product. |
| `RATE_LIMITED` | Yes, delayed | Retry offered but **never automatic**; copy says to try again shortly. |
| `INTERNAL_ERROR` | Yes | Retry, with the `traceId` shown in a copyable form. |
| `NETWORK_UNAVAILABLE` / `TIMEOUT` | Yes | Client-side copy; values intact, Retry. |

In every retryable case the form keeps **every** entered value, the message included.

#### Two distinct stores

These are frequently conflated; they are not the same thing and have opposite lifetimes.

| | Reusable contact store | Ephemeral draft |
|---|---|---|
| Holds | `name`, `email`, `phone` | **Form values:** `name`, `email`, `phone`, `message`, `consentChecked`<br>**Completion state:** `submitted`, `acceptedAt` |
| Lives in | Device-local persistence (§9.2) | `SavedStateHandle`, scoped to this route instance |
| Written | On submission success, only with consent checked | Form values continuously as the user types; completion state on success |
| Survives | App restarts, indefinitely | Process death only; dies with the route |
| Cleared | On unchecking consent | **Form values** on success; **completion state** when the route leaves the back stack |

The draft's two parts have deliberately different lifetimes. Clearing the whole draft on
success would destroy `submitted` at the exact moment it starts mattering, making the
thank-you state impossible to restore. Form values go; completion state stays until the
route itself is gone.

"The message is never saved" means **never written to the reusable contact store** — it
is product-specific, and restoring a previous product's message onto a new enquiry would
mislead. It *is* held in the ephemeral draft, because losing a half-typed message to a
backgrounded app is exactly the friction this screen exists to remove.

#### Restoring after process death

Completion state outlives the form values within the same route instance, so after a
successful submission the draft holds `submitted: true` and `acceptedAt` with no field
values. Restoration reads `submitted` **first**:

| `submitted` | Restores to |
|---|---|
| `true` | The thank-you state, using `acceptedAt`. Never the form. |
| `false` / absent | The form, with whatever field values the draft holds. |

Restoring field values without checking `submitted` would present a filled form with an
active Submit button for an enquiry that has already been sent, inviting a duplicate.

**AC-12** The thank-you state appears only after the backend confirms SMTP acceptance.
**AC-13** A send failure leaves every entered value in place and offers Retry.
**AC-14** A validation failure shows each message under its own input.
**AC-15** Back from thank-you lands on Product, never on the form.
**AC-16** A second enquiry is prefilled and submittable in one tap.
**AC-17** Unchecking consent and submitting leaves nothing stored.
**AC-18** A double-tap on Submit sends exactly one enquiry.
**AC-19** A `NOT_FOUND` on submit offers no Retry, only a route back to Product.
**AC-20** Process death on the thank-you state restores the thank-you state, not a
re-submittable form.
**AC-21** A half-typed message survives process death; it is never present in a fresh
enquiry for a different product.
**AC-22** An enquiry submitted with a tampered `seenPrice` emails the price resolved by
the backend, not the submitted one.

---

## 8. Navigation

| Route | Params | Reachable from |
|---|---|---|
| `home` | — | Launch |
| `search?q={query}` | query: string | Home |
| `category/{slug}` | slug: string | Home |
| `product/{id}` | id: int | Home, Search, Category, Recently viewed |
| `product/{id}/enquire` | id: int | Product |

**Routes carry primitives only** — ids, slugs and query strings, never objects. Every
screen is therefore addressable by URL alone, which is what makes deeplinks a later
addition rather than a refactor.

**Deeplink patterns reserved** (designed for, not implemented — no domain exists to
verify): `curato://product/{id}`, `curato://category/{slug}`, `curato://search?q={query}`.

---

## 9. Device-local persistence

No backend involvement. Two independent stores, both on **androidx DataStore**
(Preferences, JSON-serialized payloads) in `commonMain`, exposed as `Flow` so the UI
observes them like any other source. Reads must tolerate absent or unparseable data by
returning empty — a corrupt store degrades a row, it never crashes a screen.

### 9.1 Recently viewed

| | |
|---|---|
| Written | On successful product load |
| Shape | `[{ id, title, imageUrl, price, currency, viewedAt }]` |
| Cap | 10, LRU eviction |
| De-dupe | Re-viewing moves the existing entry to the front; never duplicates |
| Order | `viewedAt` desc |

A denormalised snapshot is stored rather than ids alone, so the row renders with zero
network calls. A stored price can therefore go stale; tapping through fetches fresh data,
so a stale value never reaches a decision point.

### 9.2 Saved contact details

| | |
|---|---|
| Written | On enquiry success, only with consent checked |
| Shape | `{ name, email, phone }` |
| Never stored | The enquiry message, and nothing product-specific |
| Cleared | Immediately on unchecking consent |

This is the **persistent** store. It is not the enquiry draft — the draft is ephemeral,
lives in `SavedStateHandle`, and does hold the message. §7.6 "Two distinct stores" sets
out the difference; conflating them is the easiest way to implement this screen wrongly.

---

## 10. Non-functional requirements

### 10.1 Accessibility

- Primary CTAs in a sticky bottom bar within thumb reach.
- 48dp minimum touch targets, enforced in `ui/designsystem` rather than per screen.
- `contentDescription` on every product image and icon button.
- Section titles as semantic headings.
- Form fields carry **real labels**, not placeholder-only — placeholders vanish on focus
  and are read unreliably. Errors go in `supportingText`, announced via a live region.
- No fixed-height text containers; layouts hold at large system font scales.
- The top app bar carries only secondary actions; back is also a system gesture.

### 10.2 State retention

| Case | Mechanism |
|---|---|
| Rotation / configuration change | ViewModel + `StateFlow` scoped to the nav entry |
| Process death | `SavedStateHandle` — the search query, and the enquiry draft defined in §7.6 (contact fields, message, consent, `submitted`, `acceptedAt`) |
| Return to Home | Cached Home payload |

Restoring the enquiry draft must honour `submitted`: see §7.6, "Restoring after process
death". This is distinct from the persistent contact store in §9.2, which never holds a
message.

Scroll-position restoration across process death is explicitly out of scope.

### 10.3 Security

`POST /enquiries` is an unauthenticated endpoint that causes email to be sent. That makes
it the one genuinely abusable surface in the system, and it is specified accordingly.

**Abuse control**

- **Rate limit** `POST /enquiries` per client IP (fixed window, defaults in
  `.env.example`) → `RATE_LIMITED`. Without this the endpoint is an open relay for
  anyone who finds it.
- **Request body size cap**, rejected before parsing rather than after →
  `PAYLOAD_TOO_LARGE`. Unparseable bodies → `MALFORMED_REQUEST`. Both go through the
  catch-all handler in §5, so neither can leak a framework error page.
- Field length caps from §6 enforced server-side regardless of the client.
- The rate limiter counts **attempts, not successes**, so repeated validation failures
  cannot be used to bypass it.

**Input handling**

- Every request is validated server-side, independently of client-side validation and
  including values the client prefilled from its own store.
- **Enquiry values are escaped when composing the email body.** A `name` or `message`
  containing HTML must not become markup in the message that reaches the sales inbox.
- Header-sensitive fields (anything reaching a mail header, e.g. the reply-to address)
  reject CR/LF outright, to prevent header injection.

**Transport and exposure**

- **CORS from an env allowlist**, never `*`.
- Standard security headers set (helmet or equivalent); framework fingerprint headers
  (`x-powered-by`) removed.
- **Upstream calls carry a timeout and a bounded retry**, so a hung Fake Store cannot
  exhaust the backend's connections.
- No response ever contains a secret, the upstream base URL, an internal hostname or an
  internal file path.
- `developerMessage` is sanitized at the point it is produced, per §5 — the guarantee
  lives in the backend, not in the client's choice not to render it.

**Configuration**

- No secrets committed. `.env.example` lists every required variable with a safe
  placeholder; real values stay local.
- The client never calls Fake Store directly; our backend is the only upstream it knows.

### 10.4 Operability

`docker compose up` brings the API and Mailhog up from a clean checkout in under 10
minutes, per AGENTS.md.

---

## 11. Test strategy

Tests exist to make the code **safe to change** — including changing it live, under time
pressure, without re-reading the whole repo. That goal, not a coverage percentage,
decides what is worth testing.

### 11.1 What is tested

| Layer | Covers | Why here |
|---|---|---|
| **Backend unit** | Ranking and tie-breaks (§4.1); featured-id override and its fallback; category top/additional split; search filtering; validation rules (§6); error → envelope mapping | Pure functions over fixed input. Fastest feedback, and these hold the rules most likely to be changed. |
| **Backend integration** | `POST /enquiries` end to end against a stubbed SMTP: 2xx only after acceptance, `EMAIL_SEND_FAILED` on rejection, backend-resolved price in the email body, `VALIDATION_FAILED` shape with dotted `fieldErrors` keys, rate limiting | The enquiry path is the only state-changing flow and the only one that can send real email. |
| **Backend contract** | Every endpoint returns a well-formed envelope; `data`/`error` mutually exclusive; `developerMessage` carries no stack frame. **Plus the paths no route handler owns:** an oversized body, a malformed body, an unknown route and a wrong verb each return a valid envelope | One test guards the invariant every client behaviour depends on — including the framework-generated responses that are the easiest place for it to break. |
| **Client unit** | Use cases; ViewModel transitions (loading → content / empty / error); envelope → sealed-result mapping; recently-viewed LRU, cap and de-dupe; draft restore honouring `submitted` | The logic that is wrong silently. All in `commonMain`, so it runs without a device. |

### 11.2 Deliberately not tested

- **Compose UI instrumentation tests.** They need an emulator, are slow to write and
  slower to run, and would consume the budget that unit coverage of the rules uses
  better. The trade-off is that layout regressions are caught by eye, not by CI. Stated
  as a cut, not left silent.
- Fake Store itself. It is stubbed; testing someone else's API is not our job.
- Real SMTP delivery. The interface boundary is stubbed; Mailhog covers the manual path.

### 11.3 Rules

- **A bug fix lands with a regression test** reproducing the bug, in the same commit.
- **Ranking, validation and envelope mapping are never changed without a test change.**
  These three are where a silent break is most expensive.
- Tests run from a clean checkout with one command per side, fast enough to run between
  edits.

## 12. Traceability

Each required capability maps to at least one criterion that tests the **behaviour**,
not merely the ability to navigate to the screen.

| Required capability | Where specified | Acceptance criteria |
|---|---|---|
| Home shows the 5 chosen/top-rated products | §7.2, §4.1 | AC-2, AC-2a, AC-2b, AC-5 |
| Category page: top rated + additional products | §7.3, §4.2 | AC-6, AC-6a, AC-6b, AC-6c |
| Search shows only products matching the term | §7.4, §4.3 | AC-7, AC-8, AC-8a, AC-8b, AC-8c |
| Product detail page | §7.5, §4.4 | AC-9, AC-10, AC-11 |
| Enquiry → sales inbox email + "Thank you" | §7.6, §4.5 | AC-12 … AC-22 |
| Swappable product data source | §4, §11.1, AGENTS.md | AC-2a (config-driven selection) |
| Swappable email provider | §4.5, §11.1 | AC-12 |
| Evolving look and feel | §7, §10.1 | AC-10 |
| Runs locally in under 10 minutes | §10.4, README | — (verified by dry-run) |
| Confidence to change the code | §11 | — (the test suite itself) |

**Scored areas and where each is addressed:** solution design → §3–§5, §8;
decisions and assumptions → DECISIONS.md; implementation quality → §5, §6, §10.3;
testing → §11; AI engineering practice → AI_USAGE.md.
