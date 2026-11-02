# AI Usage Notes

How AI tooling was used to plan and build this project, and how its output was
reviewed. Maintained incrementally alongside [DECISIONS.md](DECISIONS.md) — see
[AGENTS.md](AGENTS.md) for the working agreement behind that.

## Tools and models

- Claude Code (Sonnet 5) — wrote the initial agent instruction files and planning docs
  (AGENTS.md, DECISIONS.md, README.md, this file).
- Claude Code (Opus 5) — user-journey definition onward; planning, scaffolding, and
  implementation.

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

The five that changed the shape of the solution, rather than its details.

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

_Also shaped by:_ making AI-usage logging an unprompted agreement in AGENTS.md so this
log is a by-product of the work rather than a reconstruction; adding a "Start here"
onboarding order and a file-ownership rule across the five docs; scoping the design
system to a `commonMain` package instead of a Gradle module; writing SPEC.md as the
buildable statement of the settled decisions; and adopting Conventional Commits with a
PR template.

## Where AI got something wrong and how it was caught

_Appended as the build progresses._

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
