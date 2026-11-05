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

- **A stated prerequisite that contradicted a stated constraint.** The README listed
  Docker Desktop as the prerequisite while AGENTS.md promises "no paid services or
  sign-ups required anywhere in the stack". Docker Desktop's licence requires a paid
  subscription past an organisation-size threshold, so the setup instructions broke the
  project's own rule for precisely the reader most likely to be evaluating it from a
  company laptop. Neither statement was wrong in isolation, which is why it survived
  several passes over both files; it surfaced only when the stack was actually installed
  and the licence had to be reckoned with. Now verified on Colima, with the runtime named
  as a choice rather than a requirement.

- **A dependency-injection graph where a type depended on itself.** The shared Koin
  module registered `HttpClient` built from `createHttpClient(get())`, while each
  platform module also registered an `HttpClient` for its engine. Resolving the
  singleton therefore asked the container for the very type it was constructing, and
  the app died on launch with a `StackOverflowError` several hundred frames deep in
  Koin. Nothing about it is visible in a compile: the types line up, and the recursion
  only exists at resolution time. It surfaced the first time the app was actually run
  on a device, which is the argument for running it rather than trusting a green build.
  The platform half now supplies `HttpClientEngine` — a different type — so the cycle
  cannot be expressed.

- **Tests that were green on the platform that does not ship.** `commonTest` compiled
  and ran for the iOS simulator only, because the AGP Kotlin-Multiplatform library
  plugin does not enable host-side unit tests unless asked. Thirty-five passing tests
  said nothing about Android, which is the only target this submission claims to
  validate. Caught by reading which Gradle tasks had actually executed rather than the
  reassuring summary line. Both targets now run the same suite.

- **An iOS compile caught a portability bug the Android build hid.** Navigation
  arguments were read with `getString`/`getInt`, which exist on Android's `Bundle` but
  not on the multiplatform `SavedState` the shared code actually receives. The Android
  build was perfectly happy; `linkDebugFrameworkIosSimulatorArm64` was not. This is the
  concrete return on declaring the iOS target rather than merely intending to — the
  claim that the shared layer is portable gets checked instead of asserted.


- **A grid measures its children differently from a list, and the layout said so.** The
  Category screen's loading placeholder asked for a 140dp title bar and got a full-width
  one. Nothing was wrong with the component: `LazyVerticalGrid` measures items at a
  *fixed* width rather than a maximum, so `width(140.dp)` could not shrink below the
  slot, and the placeholder read as a banner instead of a heading. Invisible to the
  compiler and to every unit test — the only thing that catches it is looking at the
  screen. Found by stalling a proxy in front of the backend so the loading state stayed
  up long enough to photograph, which is the only way that state is ever observed on a
  local machine where the API answers in nine milliseconds.

- **Two components had quietly hard-coded Home's layout.** `ProductCard` set its own
  156dp width and `SectionHeader` its own 16dp gutter, both correct for the only screen
  that existed. Building the second screen made them wrong: a grid cell cannot override
  a width the component applies to itself, and a heading that supplies its own gutter
  inside a grid that already supplies one indents to 32dp while the cards beside it sit
  at 16dp. Worth recording because the fix was not a new abstraction — it was deleting
  two lines and letting the caller decide, which is what the design kit's own CSS had
  been saying all along.

- **Centring an error panel was a bug the first screen hid.** `StatePanel` spaced its
  children but never aligned them vertically, so Home's full-screen failure state sat at
  the top with a hand-tuned `padding(top = 48.dp)` compensating for it. On Category the
  same panel looked plainly wrong. The real fix was one argument —
  `Arrangement.spacedBy(space, Alignment.CenterVertically)` — and it let the compensating
  padding come out of Home too. A magic number that exists to offset a missing rule is
  usually pointing at the rule.

- **A registered dependency that nothing had ever called.** `RecordProductViewed` was
  wired into Koin during the foundation pass and referenced exactly three times — all
  declarations. No screen invoked it, so the device-local store was never written, so
  Home's recently-viewed section could not appear, so **two acceptance criteria were
  quietly untestable** rather than failing. A green build and a green suite both reported
  success throughout. What surfaced it was asking, before starting the product screen,
  which use cases had call sites — a question worth asking of any codebase where the DI
  container is the only thing holding a reference.

- **The verification harness was worth more than the feature code this time.** Three of
  this screen's states cannot occur on a developer's machine: the backend answers in
  nine milliseconds, so loading is never seen, and the app only ever navigates with ids
  the catalogue just returned, so `NOT_FOUND` is unreachable by tapping. A small proxy
  that either stalls every request or answers `/products/<id>` with the backend's *own*
  404 envelope made both real. Fetching the canned envelope from the live backend at
  startup, rather than hand-writing one, is what keeps it a test of the mapping rather
  than a test of my idea of the mapping.

- **Two small things I wrote and immediately deleted.** A `stateSwap()` helper nothing
  called, carrying a comment that rationalised its own existence, and a
  `private val unusedWidthGuard` left behind while trimming an import. Both compiled;
  both were noise. Recording them because the failure mode is specific — writing a
  plausible-looking line to satisfy a half-finished thought, then letting a green build
  stand in for having read it back.
