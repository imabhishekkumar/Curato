# AI Usage Notes

How AI tooling was used to plan and build this project, and how its output was
reviewed. Maintained incrementally alongside [DECISIONS.md](DECISIONS.md) — see
[AGENTS.md](AGENTS.md) for the working agreement behind that.

## Tools and models

- Claude Code (Sonnet 5) — wrote the initial agent instruction files and planning docs
  (AGENTS.md, DECISIONS.md, README.md, this file).
- Claude Code (Opus 5) — user-journey definition onward: planning, the screen kit, the
  API contract, and the backend implementation and its test suite.

Used conversationally throughout, with every change reviewed before being kept.

## Agent instruction files

Committed to this repo:
- [AGENTS.md](AGENTS.md) — primary instructions: stack, architecture, conventions, and
  working agreements (challenge decisions when warranted, keep DECISIONS.md current,
  don't commit without being asked).
- [CLAUDE.md](CLAUDE.md) — defers entirely to AGENTS.md, exists only for Claude Code's
  auto-discovery.

## Plan / spec

Planning ran conversationally rather than from a written brief: stack and architecture
choices, then client-specific decisions (Compose Multiplatform, Clean Architecture +
MVVM, Koin, Ktor, Navigation Compose Multiplatform), then the user journey — each
discussed and settled before any scaffolding, with the rationale captured in
[DECISIONS.md](DECISIONS.md) as it was made rather than reconstructed afterward.

[SPEC.md](SPEC.md) was then written from those settled decisions, converting them into
buildable statements: per-screen states, endpoint contracts, an error-code registry,
validation rules, and numbered acceptance criteria. It was produced *after* the
decisions rather than before, which is deliberate — the discussion was where the thinking
happened, and a spec written first would have been a guess that the discussion then
contradicted.

## Key prompts / moments that shaped the solution

The seven that changed the shape of the solution, rather than its details.

1. **Backend as the boundary.** Putting `ProductRepository` and `EmailService` behind
   interfaces, in response to the requirement for a foundation that can grow rather than
   a throwaway prototype. Swapping the product source or the email provider is a new
   adapter, not a rewrite.

2. **Interpreting the client option as Compose Multiplatform.** CMP is not among the
   named client options; its Android target is nonetheless a first-class native Kotlin
   Android app, and `commonMain` makes iOS additive instead of a second build. A judgment
   call made explicitly and argued in [DECISIONS.md](DECISIONS.md) rather than assumed
   quietly — the decision most likely to be challenged, and deliberately the best
   documented.

3. **Defining the user journey before scaffolding.** Tracing the flow screen by screen —
   rather than reading the features as a list — exposed three gaps the list had hidden:
   the category page had no screen hosting a link to it, the enquiry carried no product
   context (sales would have received a contact form with no subject), and "top rated"
   ranking belonged on the backend so the rule can change without a client release.

4. **Collapsing the response envelope to one displayable string.** The first draft
   carried `message`, `userMessage` and `errorMessage` together, leaving the client with
   no defined answer to "which do I render?", and omitted both a machine-readable `code`
   and per-field errors. Reduced to `code` (the only thing the client branches on), one
   `userMessage`, a sanitized `developerMessage`, `traceId`, and `fieldErrors` keyed by
   dotted request path.

5. **Auditing the docs against the actual requirements, before writing code.** The
   planning docs had been built from a working understanding rather than a line-by-line
   reading. Re-reading the requirements directly showed that **testing is a scored area
   while our docs had tests under "what I'd do next with another day"**, that security
   basics are scored while `POST /enquiries` had no abuse controls at all, and that a
   reviewer following our README would fail to connect the Android emulator to the
   backend for two reasons we had never written down. Corrected while the repo still had
   zero commits.

6. **Designing every screen before writing the backend.** Drawing all six routes and
   each state they can reach — as a rendered design kit rather than a description —
   settled questions the contract had left to the implementation, and changed it. Home
   became a shelf per category rather than a filter, which split `GET /home` and added
   `GET /categories`; the enquiry screen turned out to need product data its route cannot
   carry; and list endpoints stopped returning a `description` no card renders. Each of
   those is cheaper as a drawing than as a migration.

7. **Building the backend against a frozen contract.** The spec was written and agreed
   before any code existed, so implementation was a matter of honouring it rather than
   discovering it. That ordering paid off in a specific way: the contract tests were
   written from SPEC §5 and §11 rather than from the code, so they tested what was
   promised instead of what was built — and one of them failed, correctly, against a
   real defect. The three open choices the spec deliberately left (framework, runtime,
   test runner) were decided explicitly and recorded in DECISIONS.md instead of being
   settled by whichever import got typed first.

_Also shaped by:_ making AI-usage logging an unprompted agreement in AGENTS.md so this
log is a by-product of the work rather than a reconstruction; adding a "Start here"
onboarding order and a file-ownership rule across the five docs; scoping the design
system to a `commonMain` package instead of a Gradle module; writing SPEC.md as the
buildable statement of the settled decisions; and adopting Conventional Commits with a
PR template.

## Where AI got something wrong and how it was caught

Appended as the work progresses. Substantive technical errors only — a trust boundary
specified backwards, a security claim that does not hold, a quantity asserted without
measuring it.

- **Two "specified" behaviours had undefined edges.** The configurable featured list
  promised five products while skipping ids that no longer resolve, and the enquiry
  promised a current price while reading a five-minute cache. Both read as complete and
  were not: duplicates, over-long lists, stale ids and expired-cache behaviour had no
  defined answer. Resolved as decisions — the curated list is authoritative and never
  backfilled, and the cache staleness bound is stated plainly instead of engineered
  around.
- **A trust boundary was specified backwards.** The enquiry contract accepted a
  client-supplied product title and price while validating only the id — so any caller
  could have an invented price emailed to sales as fact. Caught in review of SPEC.md.
  Fixed by removing the data rather than validating it harder: the request now carries
  an id, and the backend resolves the rest.

- **A security claim that did not hold.** SPEC §10.3 asserted no stack trace could reach
  a customer *because the client does not render `developerMessage`* — but the field
  ships in the response body, readable by anyone who can call the API. A guarantee that
  depends on the client choosing not to look is not a guarantee. Now sanitized where it
  is produced, with raw diagnostics kept server-side behind a `traceId`.

- **A proposed response envelope had three overlapping message fields.** The first draft
  of the API contract carried `message`, `userMessage` and `errorMessage` together, which
  left the client with no defined answer to "which string do I render?" — and omitted
  both a stable machine-readable `code` and per-field validation errors, the latter being
  a hard requirement for placing an inline error under the right form input. Caught while
  reviewing the contract against what the enquiry form actually needed, before any code
  was written. Collapsed to one displayable string plus `code`, `developerMessage` and
  `fieldErrors`.

- **The enquiry had no product context.** The feature list described the enquiry form
  without the product it concerned, which would have sent the sales inbox a bare contact
  form. Caught by walking the journey end to end instead of reading the features as a
  list; product id, title and price added to the payload.

- **The category page was unreachable.** Documented as opening "from a category link"
  with no screen hosting one. Same cause, same catch — surfaced only by tracing the flow
  screen by screen.

- **Upstream behaviour was assumed rather than checked.** The spec treated an unknown
  product id as an upstream error. Probing the live Fake Store API showed it answers
  `/products/999` with `200` and an empty body, and an unknown category with `200 []` —
  so a naive parse throws and a missing product reaches the customer as `INTERNAL_ERROR`
  (500) with a Retry that cannot help. The adapter now translates absence rather than
  trusting status codes. Nothing in the docs had been wrong on its face; it had simply
  never been verified against the thing it describes.

- **A shelf heading that could state something false.** `GET /home` returned one field
  named `topRated` for two different rules. With `FEATURED_PRODUCT_IDS` set, the list is
  curated and not ranked at all — `8,4` resolves to the two lowest-rated products in the
  catalogue, under a heading reading "Top rated". Caught by asking what the screen
  claims rather than what the endpoint returns. The response now reports which rule
  produced it and the client picks the heading.

- **Quantities asserted from memory, then contradicted by measurement.** Two numbers
  used to justify design choices were wrong: the Home card count (23, not 25) and the
  source resolution of product imagery (~0.25 MP, not the 1500 px the filenames imply).
  The second mattered — it had produced a recommendation about decode tuning, when the
  real constraints are that the images are PNGs at eight times a comparable JPEG and
  that their aspect ratios span 0.58 to 1.92, which is what rules out cropping them to
  square. Caught by measuring all twenty rather than reasoning about them.

- **A middleware ordering bug that made 405 unreachable.** The `Content-Type` guard was
  mounted app-wide, ahead of routing. A `POST` to a `GET`-only path was therefore judged
  on its content type and answered `400 MALFORMED_REQUEST`, when the honest answer is
  that the verb is wrong whatever the body claims to be — so three of the registry's
  error codes were reachable only by accident of request shape. Caught by a contract test
  written from SPEC §5.1 rather than from the code: it asserted `POST /home` is a 405 and
  got a 400. Body parsing is now scoped to the single endpoint that has a body.

- **A cold-start double fetch, found by reading the spec against the design.** SPEC §4
  fires `/home` and `/categories` in parallel. Nothing in the first cache design stopped
  both from missing a cold cache and both from fetching the whole catalogue — on the
  first request of every session, which is exactly when a reviewer is watching. Found
  while writing the cache, not while debugging it, and fixed with single-flight: the
  cache holds the in-progress refresh and later callers await it.

- **An HTTP status that contradicted the guarantee underneath it.** `POST /enquiries`
  was first written to answer `202 Accepted`. 202 means the work is queued for later,
  which is the precise opposite of this endpoint's one promise — that the mail server has
  already accepted the message before the response is written. The body asserted one
  thing and the status code another. Changed to 200 before it reached the suite.

- **A shutdown handler that could report success over an abandoned shutdown.** The
  graceful-shutdown path has two exits — a clean close, and a forced one when the grace
  period expires with requests still in flight — and both could fire for the same
  signal, because a request that finishes just after the deadline triggers the close
  callback after the forced exit has already run. The late clean close reported exit 0
  over a shutdown that had given up on work. In production `process.exit` happens to
  make the second call unreachable, which is exactly why it would never have been
  noticed: the invariant held by accident of the runtime rather than by the code. Caught
  by a unit test that injected the exit function instead of letting it kill the process,
  and fixed with a latch so the first outcome wins.

