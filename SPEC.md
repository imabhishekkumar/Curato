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

### ProductSummary

Everything a card renders. Returned by every endpoint that produces a **list**; the full
`Product` is returned only by `GET /products/{id}`.

| Field | Type | Notes |
|---|---|---|
| `id` | int | |
| `title` | string | |
| `price` | number | |
| `currency` | string | |
| `category` | `CategoryRef` | |
| `imageUrl` | string | |
| `rating` | `{ rate: number, count: int }` | |

`ProductSummary` is `Product` without `description`. No list screen renders a
description — it appears on the product screen alone — and against the live catalogue
`description` is **60% of a list payload** (299 characters on average, 10.6 KB of JSON
for 20 products). The split is for honesty before weight: an endpoint whose callers
render cards should not return a field no card can show.

**Not a query flag.** `?lightweight=true` or equivalent would make the response shape
conditional, so `description` becomes optional on a type the product screen requires it
on, the DTO stops describing what a caller will receive, and every endpoint needs
contract tests twice. Each endpoint returns one shape, always.

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

| Endpoint | Returns | Read by |
|---|---|---|
| `GET /home` | `ProductSummary` list | Home — the global shelf |
| `GET /categories` | `ProductSummary` lists | Home — one shelf per category |
| `GET /categories/{slug}/products` | `ProductSummary` lists | Category screen |
| `GET /products/search` | `ProductSummary` list | Search |
| `GET /products/{id}` | **Full `Product`** | Product screen, Enquiry screen |
| `POST /enquiries` | — | Enquiry screen |
| `GET /health` | — | docker-compose, reviewer |

**Home opens two calls in parallel** — `/home` and `/categories` — neither waiting on the
other, each owning its own sections. One may fail while the rest of the screen works
(§7.2).

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

**Upstream adapter contract.** Fake Store does not use HTTP status to signal absence, so
the adapter translates rather than trusts. Verified against the live API:

| Upstream request | Upstream answers | Adapter maps to |
|---|---|---|
| `/products/999` (unknown id) | `200`, `content-length: 0` | Domain not-found → `NOT_FOUND` |
| `/products/abc` (non-numeric) | `200`, `content-length: 0` | Domain not-found → `NOT_FOUND` |
| `/products/category/nonsense` | `200 []` | Empty category, not an error |
| `/products` | `200`, ordered by `id` | Ranked here; see §4.1 |

A body is checked for emptiness **before** it is parsed. Parsing an empty body throws,
and a thrown parse surfaces as `INTERNAL_ERROR` (500) for a product that merely does not
exist — the wrong code, the wrong screen, and a Retry offered where retrying cannot help.
In practice every read resolves from the cached full catalogue, so this binds whoever
later adds a direct upstream fetch, which is why it belongs to the adapter and not to a
route.

**Upstream offers no rating sort** — its `?sort=` orders by `id` only. Every ordering in
this system is therefore computed here, which is what makes the ranking rule below a
requirement rather than a preference.

### 4.1 `GET /home`

The global shelf, and nothing else.

```json
{ "featured": { "source": "computed", "products": [ ProductSummary ] } }
```

`categories` is **not** in this payload — the category list and the products beneath it
come from §4.2 together, so the list of categories exists in exactly one response and
cannot disagree with itself.

- **Selection is configurable.** The stated criterion describes the 5 featured products
  as ones that have been *chosen* — curation, not computation. Honouring both readings
  costs one env var, and demonstrates the premise that the product data source is
  expected to change.

  | `FEATURED_PRODUCT_IDS` | `source` | `products` contains |
  |---|---|---|
  | Unset (default) | `"computed"` | The computed top 5 by rating — or all products when the catalogue holds fewer than 5. |
  | Set (e.g. `1,4,9,12,18`) | `"curated"` | **Up to 5**, as configured: the ids that resolve, in the order given. |

- **`source` exists so the client cannot state something false.** A curated list is not
  ranked by rating, so a shelf headed "Top rated" containing it would be a lie on the
  first screen anyone sees: `FEATURED_PRODUCT_IDS=8,4` resolves to the two
  **lowest-rated** products in the catalogue. The client renders the heading **"Top
  rated"** for `computed` and **"Featured"** for `curated`, branching on `source` alone.
  The backend never sends display copy.

  **The curated list is authoritative — it is never backfilled.** If the configured list
  yields three products, Home shows three. Topping it up with highest-rated products the
  curator did not choose would silently override the choice the config exists to express;
  showing fewer is the honest outcome of a short or stale config.

  **Normalization, in order:**

  1. Split and trim; drop entries that are not positive integers, with a warning log.
  2. De-duplicate — first occurrence wins, later repeats dropped.
  3. Take the first 5; log that the list was truncated if longer.
  4. Resolve each id; skip those that do not resolve, with a warning log naming the id.
  5. **If nothing resolves, fall back to the computed top 5** and report
     `source: "computed"` — the response describes what was actually produced, never what
     was configured. Home is never empty, and a fully stale config degrades to a sensible
     default rather than a blank screen.
  6. Otherwise return what resolved, in configured order, however few that is.

  A malformed or stale config is a logged warning, never a 5xx — the home page is the
  first thing anyone sees, and it must not be takeable down by a typo in an env var.
- **Ranking (deterministic):** `rating.rate` desc → `rating.count` desc → `id` asc. The
  tie-breaks exist so the response is reproducible and therefore testable. They are not
  ceremonial: in the live catalogue, electronics holds two products at `2.9` separated
  only by `count` (470 against 250), and that pair straddles the cut at five.
- Errors: `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.2 `GET /categories`

Every category with the head of its ranking, in one response. Home renders one shelf per
entry (§7.2).

```json
{
  "categories": [
    { "category": { "slug": "electronics", "name": "Electronics" },
      "topRated": [ ProductSummary ] }
  ]
}
```

- One entry per category in the slug map (§3), in that order. **The backend owns the
  order**, as it owns the ranking and the slug map; the client renders the sequence it is
  given.
- `topRated` — **up to 5** per category, the ranking rule of §4.1 scoped to that
  category. Fewer when the category holds fewer; never padded.
- **Never curated.** `FEATURED_PRODUCT_IDS` governs §4.1 only. There is no per-category
  curation hook — a deliberate omission, since the global hook already demonstrates
  curation overriding computation, and four more env vars is disproportionate here.
- `description` is omitted (`ProductSummary`), which matters most on this endpoint: it
  carries more products than any other.
- **Shelves are a selection, not the category.** Against the live catalogue, electronics
  and women's clothing hold 6 products each, so one product in each never reaches Home
  and is found through search or the category screen. Raising the shelf to 6 would render
  this catalogue in full and make "View all" pointless.
- Errors: `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.3 `GET /categories/{slug}/products`

```json
{
  "category":   { "slug": "mens-clothing", "name": "Men's Clothing" },
  "topRated":   [ ProductSummary ],
  "additional": [ ProductSummary ]
}
```

The category screen's endpoint, reached from a shelf's **View all** or from a deeplink.
It differs from §4.2 in holding the category's *complete* contents: §4.2 returns the head
of every category, this returns all of one.

- `topRated` — up to 5, same ranking rule scoped to the category.
- `additional` — the remainder, `id` asc. **An empty array when the category holds 5 or
  fewer products**; the client omits the section entirely rather than rendering it empty.
- Unknown slug → `NOT_FOUND`.
- Errors: `NOT_FOUND`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.4 `GET /products/search?q={query}`

```json
{ "query": "bag", "results": [ ProductSummary ] }
```

- Case-insensitive substring match against `title` **and** `description`. **The match
  reads a field the response does not return** — matching happens server-side against the
  cached full catalogue, while results carry `ProductSummary`. A product can therefore be
  returned for a term that appears nowhere in its visible card, which is correct and is
  why the result count names the term searched (§7.4).
- `q` is trimmed; **minimum 2 characters** after trimming, else `VALIDATION_FAILED` with
  `fieldErrors.q`. The client disables submission below 2 characters, so this is a
  defence, not a normal path.
- No match → `200` with `results: []`. An empty result is a successful search, not an
  error.
- Results ordered by the standard ranking rule, so the best-rated match leads.
- Errors: `VALIDATION_FAILED`, `UPSTREAM_UNAVAILABLE`, `INTERNAL_ERROR`.

### 4.5 `GET /products/{id}`

Returns one **full `Product`** — the only endpoint that does, and the only source of
`description`. Unknown id → `NOT_FOUND`.

This endpoint is load-bearing for deeplink-readiness: the product screen resolves its own
data from an id alone and never depends on data passed by the list that opened it. **The
enquiry screen calls it too** (§7.6): routes carry primitives only, so
`product/{id}/enquire` arrives holding an id and must resolve the product it is about.

### 4.6 `POST /enquiries`

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

### 4.7 `GET /health`

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
| Top rated / Featured | `GET /home` | Section-level error with Retry if the call fails. |
| One shelf per category | `GET /categories` | Section-level error with Retry if the call fails. |

**Two calls, fired in parallel, each owning its sections.** Neither waits on the other, so
either can fail while the rest of the screen stays usable. Recently viewed is device-local
and paints before either lands.

- Section order is fixed: search → recently viewed → top rated → category shelves, in the
  order §4.2 returns them.
- **Every category is a shelf.** There is no chip row and no filter: each shelf carries a
  **View all** into `category/{slug}` (§7.3). Nothing on the screen is hidden behind an
  interaction, and **after first paint Home issues no further requests** — scrolling and
  browsing it are free.
- The shelf heading from `/home` is **"Top rated"** when `featured.source` is `computed`
  and **"Featured"** when it is `curated` (§4.1). The client branches on `source`; it
  never inspects the products to guess.
- Loading: shimmer per section, one shared clock. Recently viewed renders instantly (no
  network), so it is visible while the rest shimmers.
- Both payloads are cached, so returning from a product does **not** re-shimmer Home.
- When **both** calls fail, Home shows one error with one Retry, not two panels saying the
  same thing.

**AC-2** With `FEATURED_PRODUCT_IDS` unset, Home shows the computed top 5 under the
heading "Top rated" — or all products when the catalogue holds fewer than 5.
**AC-2a** With it set, Home shows the configured ids that resolve, in configured order and
no others, under the heading "Featured".
**AC-2c** A list containing duplicates shows each product once, at its first position.
**AC-2d** A list longer than 5 shows only the first 5.
**AC-2e** A list where some ids no longer resolve shows the remainder — fewer than 5,
never backfilled with unconfigured products.
**AC-2f** A list where no id resolves falls back to the computed top 5, reports
`source: "computed"`, and is headed "Top rated"; Home is never empty and never 5xx.
**AC-2b** Ranking is deterministic: two products with an equal `rate` order by `count`
desc, then `id` asc.
**AC-3** Recently viewed is absent on first run and present, newest-first, after viewing a
product.
**AC-4** Returning to Home from a product shows content immediately, not a shimmer.
**AC-5** A failed `GET /home` shows a retryable error scoped to its own section, not a
blank screen.
**AC-23** Home renders one shelf per category returned by §4.2, in that order.
**AC-24** A shelf shows every product the response carried for it, up to 5, and is never
padded to a fixed count; a category of 2 renders 2 cards.
**AC-25** After first paint, scrolling and browsing Home issue no further network
requests.
**AC-26** The two calls fail independently: one failing leaves the other's sections fully
usable, and its Retry re-runs only the call that failed. When both fail, Home shows a
single error and a single Retry.
**AC-27** A shelf's **View all** opens `category/{slug}` for that shelf's category.

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
- **The image is inset on a panel, never full-bleed.** Upstream images top out at 646 px
  on the long edge (§10.4); a full-width hero at 360dp is 1080 px at xxhdpi and would
  upscale visibly. Drawn at roughly 240dp, it is close to native.
- **The displayed price is captured into the enquiry draft on navigation**, and is what
  `seenPrice` carries (§3). It is the price *this customer saw*; re-reading it when the
  enquiry screen resolves the product would compare the backend against itself and always
  agree, making the drift note worthless. If the draft holds no price — a cold restore
  after process death — `seenPrice` is omitted, which §6 already tolerates.
- **"Enquire" is pinned to a sticky bottom bar** within thumb reach, never inline at the
  end of the scroll. Scroll content is bottom-padded so nothing hides behind it.
- On successful load, the product is recorded to the device-local recently-viewed store.
- Unknown id → `NOT_FOUND` error state with back.

**AC-9** The product screen loads correctly when entered with only an id.
**AC-10** "Enquire" is reachable without scrolling, at any content length.
**AC-11** Viewing a product adds it to the front of recently viewed, de-duplicated.

### 7.6 Enquiry — `product/{id}/enquire`

A full screen, not a sheet.

**It resolves its own product.** Routes carry primitives only (§8), so this route arrives
holding an id and calls `GET /products/{id}` (§4.5) for the title, price and image shown
above the form — an enquiry with no visible subject is a form the customer cannot check
before sending. Sharing a ViewModel with the product screen would save one request and
break the property that makes every route addressable by URL alone.

That gives this screen two states **before the form is usable**:

| Product resolution | Screen shows |
|---|---|
| In flight | The form, inert: fields visible, Submit disabled, product header shimmering |
| `NOT_FOUND` | "This product is no longer available", one action back. No form, no Retry |
| `UPSTREAM_UNAVAILABLE` / network | Full-screen retryable error; the form is not composed |

Two modes once resolved:

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
| Written | After submission success, only with consent checked. A failed write is dropped: it never undoes a confirmed send | Form values continuously as the user types; completion state on success, before the contact store is written |
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

**AC-28** The enquiry screen loads its product from its id alone, with no object passed
from the product screen.
**AC-29** While the product is resolving, Submit is disabled; an unresolvable product
shows the unavailable state instead of a submittable form.
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

The snapshot holds **no rating**, so `ProductCard` takes an optional rating and omits the
star line for these cards. Storing a rating would put a value on the device that nothing
revalidates.

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

### 10.4 Imagery and formatting

**Images are the payload.** Measured across the live catalogue: each product image is only
~0.25 MP (370–640 px wide, 333–646 px tall) but ships as an 8-bit RGBA PNG at 105–465 KB,
median 238 KB — roughly eight times the same picture as JPEG. Home requests 18 distinct
images, about **4.3 MB against 10.6 KB of JSON**. The format is upstream's and not ours to
fix; what follows is how the client behaves given it.

- **`ContentScale.Fit`, never `Crop`.** Aspect ratios run **0.58 to 1.92** — fourteen
  portrait, six landscape, mixed inside one shelf, and one landscape image sits in the
  global top five. Cropping the widest to a square discards 48% of it, and on a product
  photographed end to end against white that removes the product. Every thumbnail fits
  inside a square `surfaceContainerHigh` panel, which is why that panel is a visible
  surface rather than a placeholder waiting to be covered.
- **Every request declares the size it draws**, so decode is bounded by the view and not
  by the file. Thumbnail and hero are separate requests at different sizes.
- A placeholder holds the panel from first layout, so a shelf never shifts as images
  arrive.
- The image host sends `cache-control: public, max-age=14400`, so the disk cache carries a
  returning customer for four hours with no network.
- **Out of scope, stated:** a resizing image proxy on our backend would serve thumbnails
  at the drawn size. That is a service of its own and is named as the next step rather
  than attempted.

**Prices are formatted from the response, never the locale.** `currency` is always `USD`
while the device may be anywhere; the platform's default currency formatter would render
`$55.99` as `₹55.99` on an Indian device — the right symbol for the wrong currency, beside
a Send button. One `formatPrice(price, currency)` in `ui/designsystem` is driven by the
response's `currency`, and every price on every screen goes through it.

### 10.5 Operability

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
| **Backend unit** | Ranking and tie-breaks (§4.1); featured-id override, its fallback, and the `source` it reports; the per-category shelf cut in §4.2; category top/additional split; search filtering; validation rules (§6); error → envelope mapping | Pure functions over fixed input. Fastest feedback, and these hold the rules most likely to be changed. |
| **Backend integration** | `POST /enquiries` end to end against a stubbed SMTP: 2xx only after acceptance, `EMAIL_SEND_FAILED` on rejection, backend-resolved price in the email body, `VALIDATION_FAILED` shape with dotted `fieldErrors` keys, rate limiting | The enquiry path is the only state-changing flow and the only one that can send real email. |
| **Backend adapter** | Upstream `200` with an empty body and `200 []` map to domain not-found and empty, never to a thrown parse — asserted against recorded upstream responses | The one place where upstream's refusal to use status codes can turn a missing product into a 500. |
| **Backend contract** | Every endpoint returns a well-formed envelope; `data`/`error` mutually exclusive; `developerMessage` carries no stack frame; list endpoints return `ProductSummary` and never `description`, while `GET /products/{id}` always does. **Plus the paths no route handler owns:** an oversized body, a malformed body, an unknown route and a wrong verb each return a valid envelope | One test guards the invariant every client behaviour depends on — including the framework-generated responses that are the easiest place for it to break. |
| **Client unit** | Use cases; ViewModel transitions (loading → content / empty / error); Home's two calls failing independently; the shelf heading branching on `featured.source`; envelope → sealed-result mapping; recently-viewed LRU, cap and de-dupe; draft restore honouring `submitted` | The logic that is wrong silently. All in `commonMain`, so it runs without a device. |

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
| Home shows the 5 chosen/top-rated products | §7.2, §4.1 | AC-2, AC-2a, AC-2b, AC-2f, AC-5 |
| Home browses the whole catalogue by category | §7.2, §4.2 | AC-23, AC-24, AC-25, AC-26, AC-27 |
| Category page: top rated + additional products | §7.3, §4.3 | AC-6, AC-6a, AC-6b, AC-6c |
| Search shows only products matching the term | §7.4, §4.4 | AC-7, AC-8, AC-8a, AC-8b, AC-8c |
| Product detail page | §7.5, §4.5 | AC-9, AC-10, AC-11 |
| Enquiry → sales inbox email + "Thank you" | §7.6, §4.6 | AC-12 … AC-22, AC-28, AC-29 |
| Swappable product data source | §4, §11.1, AGENTS.md | AC-2a (config-driven selection) |
| Swappable email provider | §4.6, §11.1 | AC-12 |
| Evolving look and feel | §7, §10.1 | AC-10 |
| Runs locally in under 10 minutes | §10.5, README | — (verified by dry-run) |
| Confidence to change the code | §11 | — (the test suite itself) |

**Scored areas and where each is addressed:** solution design → §3–§5, §8;
decisions and assumptions → DECISIONS.md; implementation quality → §5, §6, §10.3, §10.4;
testing → §11; AI engineering practice → AI_USAGE.md.
