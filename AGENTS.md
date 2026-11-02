# AGENTS.md

Instructions for any AI coding agent working in this repository (Claude Code, Cursor, etc.).

## Start here

Before answering questions about this project, planning, or changing any code, read
these in order — they carry the project's context and its current shape, and this file
alone is not enough:

1. **This file (AGENTS.md)** — stack, architecture, conventions, working agreements.
2. **[SPEC.md](SPEC.md)** — the specification: screens and their states, the API
   contract, validation rules, error codes, the test strategy, and the numbered
   acceptance criteria. This is what "done" means. Build against it; do not infer requirements from the feature list
   in README.md when the spec covers them.
3. **[DECISIONS.md](DECISIONS.md)** — every assumption, scope cut, and architectural
   choice made so far, with its rationale. Read this before proposing anything
   structural; the reasoning behind a decision is usually already recorded here, and
   re-opening it without reading it wastes a turn.
4. **[README.md](README.md)** — the user-facing feature set, plus setup and run
   instructions.
5. **[AI_USAGE.md](AI_USAGE.md)** — which tools and models did what, the prompts and
   discussions that shaped the solution, and anything the AI got wrong. Doubles as a
   log of how the project arrived at its current state.

**Which file owns what**, so a change lands in the right one: SPEC.md owns *what* gets
built, DECISIONS.md owns *why* and what was rejected, AGENTS.md owns the *rules* binding
implementation, README.md owns *how to run* it. A single change often touches several —
keep them consistent rather than letting one drift.

Then establish the **current shape** rather than assuming the docs describe something
that exists: these docs state intent, and parts of the project are still unbuilt and
marked as such. Check what is actually on disk (`git ls-files`, `git status`, the
directory tree) and reconcile it against the docs before acting. Where they disagree,
the working tree is the truth about what exists and the docs are the truth about what
was intended — flag the gap instead of quietly picking one.

## Project

A product catalogue PoC: customers browse/search products and submit an enquiry.

## Stack

- **Backend:** Node.js + TypeScript. Wraps the Fake Store API behind our own REST
  contract — the client never calls Fake Store directly.
- **Client:** Kotlin Compose Multiplatform. Android is the primary target; the iOS
  target is planned to be structurally scaffolded (shared `commonMain` code, `iosMain`
  source set) but, like the rest of the client, not yet built or validated.

## Architecture

**Backend** — ports/adapters at the boundaries expected to change:
- `ProductRepository` interface → `FakeStoreProductRepository` implementation.
- `EmailService` interface → local dev implementation (Mailhog), swappable for a real
  provider later.

**Client** — Clean Architecture + MVVM, single Gradle module with clean package
boundaries (no multi-module split yet — not justified at this scope):
- `data/` — DTOs, repository implementations (Ktor client against our backend).
- `domain/` — models, repository interfaces, use cases.
- `presentation/` — ViewModels exposing `StateFlow<UiState>` per screen.
- `ui/` — Compose screens.
- `ui/designsystem/` — reusable components (buttons, fields, product card, shimmer,
  loading/error/empty states), centralized theme tokens on Material 3. Lives in
  `commonMain` as a package, not a separate module, for the same reason.

## User journey

Settled before scaffolding. Screen-by-screen states and acceptance criteria are in
[SPEC.md](SPEC.md) §7; the rationale is in [DECISIONS.md](DECISIONS.md) under "User
journey and navigation". In short:

```
Splash → Home (search bar · recently viewed · top rated · category chips)
Home → Search Results | Category | Product
Category / Search Results → Product
Product → Enquiry → Thank you (pops back to Product)
```

## Non-negotiable implementation rules

These are load-bearing — breaking one costs a refactor, not a tweak. The precise
contracts are in [SPEC.md](SPEC.md); the rationale for each is in
[DECISIONS.md](DECISIONS.md).

- **Every response uses the fixed envelope**, success and failure alike:
  `{ success, data, error: { code, userMessage, developerMessage, fieldErrors } }`.
  `data` and `error` are mutually exclusive, both always present, one null. The client
  parses it through a single generic `ApiResponse<T>` mapped to a sealed result type —
  never per-endpoint error handling.
- **The client branches on `error.code`, never on message text.** `userMessage` is the
  only *general* error string shown; `fieldErrors` render inline against the inputs their
  keys name; `traceId` is surfaced only on `INTERNAL_ERROR`, in a copyable form.
  `developerMessage` is never shown — and because it ships in the response body anyway,
  it is sanitized where it is produced, never a stack trace, exception text or internal
  path.
- **Nav routes carry primitives only** — product id, category slug, search query. Never
  pass a `Product` (or any object) between destinations.
- **The product screen loads from an id alone** and fetches its own data. It must not
  depend on data handed down by whichever list opened it, or deeplinks become impossible
  without a rewrite.
- **Category names are slugged** (`mens-clothing`), with the slug ↔ name map on the
  backend. Never put Fake Store's raw names (`men's clothing`) in a path.
- **Ranking is backend-only.** The API returns pre-ranked, pre-split payloads; the client
  never sorts or filters a product list to decide what is "top rated".
- **Primary CTAs go in a sticky bottom bar** with `imePadding()` and
  `navigationBarsPadding()`, never inline at the end of scrollable content. 48dp minimum
  touch targets, enforced in `ui/designsystem`.
- **Form fields get real labels**, not placeholder-only, with errors in `supportingText`
  driven by `error.fieldErrors`.
- **Recently viewed and saved contact details are client-local**, no endpoints. Contact
  details are saved only behind a visible, default-on "Remember my details" consent
  checkbox; unchecking it clears the store. The enquiry message is **never written to
  that reusable store** — it does live in the ephemeral draft that survives process
  death. Two stores, opposite lifetimes; see [SPEC.md](SPEC.md) §7.6.
- **Server-side validation always runs**, even on prefilled values.
- **Never trust client-supplied data about a server-owned entity.** The enquiry request
  carries a product **id**; the backend resolves title and price itself. `seenPrice` is
  explicitly untrusted and may only be used to report drift, never as the value of
  record.
- **`developerMessage` is sanitized where it is produced** — it ships in the response
  body, so no exception text, stack frame, upstream body, internal host or file path may
  reach it. Raw diagnostics are logged server-side against `error.traceId`.
- **Offer Retry only where retrying can help.** `NOT_FOUND` is not retryable; see the
  per-code table in [SPEC.md](SPEC.md) §7.6.
- **Rate-limit `POST /enquiries`** — it is unauthenticated and sends email.
- **Every response passes through the envelope serializer**, including framework-
  generated rejections (malformed JSON, oversized body, unknown route, wrong verb). A
  catch-all handler is not optional; without it the universal contract is only true of
  the paths someone remembered to write.
- **Validation and resolution are separate failures.** A malformed `productId` is
  `VALIDATION_FAILED` with a field error; a well-formed one that does not resolve is
  `NOT_FOUND` with none.
- **A bug fix lands with a regression test in the same commit.** Ranking, validation and
  envelope mapping are never changed without a test change.

## Conventions

- DI: Koin.
- Networking: Ktor client (multiplatform-compatible; Retrofit is not).
- Device-local storage: androidx DataStore (Preferences), in `commonMain`. Its `Flow`
  API feeds the same `StateFlow` pattern as every other source — do not introduce a
  second persistence library.
- Navigation: Navigation Compose Multiplatform.
- State: `StateFlow` in ViewModels, collected via `collectAsState()` in Compose.
  Rotation is covered by the ViewModel; `SavedStateHandle` is used only for the search
  query and the enquiry form draft. The Home payload is cached so returning to Home does
  not re-shimmer.
- Loading states: shimmer placeholders per section, from `ui/designsystem`.
- Testing: backend-heavy plus client core, per [SPEC.md](SPEC.md) §11. Compose UI
  instrumentation tests are a deliberate cut — do not add them without discussion.
- The backend exposes `GET /health` for the compose healthcheck and reviewer sanity
  checks.
- Splash: the platform splash screen API — never a custom timed screen.
- No secrets committed — use `.env.example` for backend config.
- No paid services or sign-ups required anywhere in the stack.
- Should be able to run the backend (+ Mailhog) locally in under 10 minutes via
  `docker compose up`; the Android client builds and runs against it via the emulator.
- Any infrastructure beyond local docker-compose (if added) must be AWS-compatible.

## Working agreements

- **Challenge the decision when required.** If an instruction conflicts with an existing
  architectural decision, looks like it'll cause rework, or seems like a bad idea for the
  stated goals, say so and propose an alternative before implementing it. Don't silently
  comply with something you think is wrong.
- **Maintain decisions in DECISIONS.md.** Every assumption, scope cut, or architectural
  choice gets recorded there as it's made — not reconstructed afterward from memory.
- **Do not commit unless asked.** Never run `git add` / `git commit` / `git push`
  without an explicit instruction in the current turn, even after finishing a logical
  chunk of work.
- **Log AI usage in AI_USAGE.md as it happens.** Unprompted, without being asked: after
  any planning discussion, design decision, or chunk of implementation, append an entry
  covering the tool and model used (note it per entry — the model in use changes), the
  prompt or discussion that shaped the outcome, and anything the AI got wrong along with
  how it was caught. Same standard as DECISIONS.md: written as it happens, never
  reconstructed at the end.
- **No meta commentary.** Write about the product, never about the process of writing
  about it. Documents, commit messages, PR descriptions and code comments say what the
  thing is and why it is that way — not what an earlier draft said, what got corrected,
  or how the text was produced. "Overlapping documents drift" is a reason; "the docs had
  started to drift, so I split them" is a confession about work the reader cannot see.
  This qualifies the AI_USAGE.md rule above: log substantive technical errors — a trust
  boundary specified backwards, a security claim that does not hold — and leave out
  process slips in the writing itself. The reader is a reviewer meeting this repository
  for the first time, with no prior drafts to compare against.
- Time-box: originally 2–3 hours, revised to **4–5 hours** when the user journey added
  scope — tests included, not on top. See "Time-box" in [DECISIONS.md](DECISIONS.md).
  Track actual time honestly against it; an overrun stated plainly beats a silent one.
- Prefer a small, well-reasoned solution over a large, unexplained one — stay on the
  critical path rather than maximizing feature count.

## Submission workflow

- Build on a feature branch (`feat/catalogue-mvp`), not directly on `main`.
- Raise a PR from that branch into `main` in this repo and leave it open — the PR
  description is part of what gets assessed, so it should carry the same clarity as
  these docs.

## Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/). This is a convention
for *how* to write a commit, not permission to make one — the "Do not commit unless
asked" agreement above still holds, without exception.

### Format

```
<type>(<scope>): <subject>

<body — why, not what; wrapped at 72 chars>

<footer — refs, breaking changes, trailers>
```

### Types

| Type | Use for |
|---|---|
| `feat` | A new user-facing capability |
| `fix` | A bug fix |
| `refactor` | Behaviour-preserving restructure |
| `docs` | Documentation only |
| `test` | Adding or correcting tests |
| `build` | Build config, dependencies, Gradle, Docker |
| `chore` | Tooling and housekeeping with no src/ or docs impact |
| `perf` | A change made for performance |

### Scopes

`backend`, `api`, `client`, `home`, `product`, `category`, `search`, `enquiry`,
`designsystem`, `nav`, `di`, `docs`, `spec`, `infra`. Omit the scope only when a change genuinely
spans the whole repo.

### Rules

- **Subject:** imperative mood ("add", not "added" or "adds"), lowercase, no trailing
  period, 72 characters or fewer. It must complete the sentence "this commit will…".
- **Body:** explain *why* the change was made and what alternative was rejected. The diff
  already shows what changed. Omit the body only when the subject is genuinely
  self-sufficient (typo fixes, dependency bumps).
- **Atomic:** one logical change per commit. Never mix a refactor with a feature — split
  them, with the refactor first.
- **Docs travel with the change.** A commit that makes a decision updates
  [DECISIONS.md](DECISIONS.md) in that same commit, and one shaped by AI usage updates
  [AI_USAGE.md](AI_USAGE.md) in that same commit. No trailing "update docs" commit —
  those files are written as the work happens, and the git history is the evidence of it.
- **Never commit secrets.** `.env` stays local; `.env.example` is what gets committed.
- Reference a decision by its DECISIONS.md section heading when a commit implements one.

### Trailers

End every commit message with:

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

Attribute the model actually used — it changes across the project, and
[AI_USAGE.md](AI_USAGE.md) tracks which did what.

### Examples

```
feat(enquiry): reuse saved contact details on repeat enquiries

First enquiry shows the full form; later ones collapse to a summary with
an Edit affordance, cutting the flow to a single tap. Details are stored
only behind a visible, default-on consent toggle — see "Enquiry friction"
in DECISIONS.md. The message field is deliberately never persisted, since
restoring a previous product's message would mislead rather than help.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

```
fix(api): return fieldErrors on enquiry validation failure

The envelope defined fieldErrors but the validator collapsed every failure
into a single userMessage, leaving the client unable to place an error under
the input that caused it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

## Pull requests

The PR title follows the same Conventional Commits format as a commit subject. The
description is assessed, so it carries the same standard as these docs: specific, honest
about what was cut, and never padded.

Fill in every section; delete a heading only if it is genuinely not applicable, and say
why rather than leaving it blank.

### Template

````markdown
## What and why

One paragraph: the problem this solves and the shape of the solution. Lead with the
outcome a reader cares about, not a list of files touched.

## Scope

**In:** what this PR delivers.

**Deliberately out:** what was cut and why — scope cuts are a decision, not an omission.
Link the relevant DECISIONS.md section.

## Key decisions

The two or three choices a reviewer would otherwise have to reverse-engineer from the
diff, each with the alternative that was rejected and why. Link to DECISIONS.md rather
than restating it in full.

## How to verify

Exact steps to run it, in order, with the commands to copy. Assume a clean checkout:

```bash
cp .env.example .env
docker compose up
```

Then what to click, and what the expected result is.

## Testing

What is covered, what is not, and what was verified by hand instead. State untested
areas explicitly — an honest gap reads better than an implied claim of coverage.

## Screenshots / recordings

For any user-facing change. A short screen recording for a multi-step flow such as the
enquiry journey.

## Accessibility

Touch targets, labels, font scaling, and one-handed reachability for any UI change — see
"Accessibility and one-handed use" in DECISIONS.md.

## AI usage

What AI tooling contributed, and anything it got wrong that review caught. Links to
[AI_USAGE.md](AI_USAGE.md) for the full log.

## Time spent

Actual, split across planning / building / testing / reviewing, against the stated
time-box.

## Checklist

- [ ] Acceptance criteria from SPEC.md verified, or listed as not yet covered
- [ ] SPEC.md updated if this PR changed a contract or a screen's behaviour
- [ ] DECISIONS.md updated for every decision this PR makes
- [ ] AI_USAGE.md updated, including anything AI got wrong
- [ ] README.md setup steps verified from a clean checkout
- [ ] No secrets committed; `.env.example` covers every required variable
- [ ] Backend runs via `docker compose up`; client builds against it
- [ ] Commits are atomic and conventionally formatted
````

End the PR body with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
