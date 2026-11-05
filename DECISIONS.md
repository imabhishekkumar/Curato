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

## Backend implementation choices

Settled when the backend was built. The spec fixes the contract; these are the choices
about how to honour it that the spec deliberately left open.

- **Express 5, not Fastify.** Express 5 propagates a rejected promise from an async
  handler to the error handler on its own, which removes the wrapper utility Express 4
  needed and is the single feature that matters most here — every route is async. Fastify
  is faster and ships schema validation, but its built-in error behaviour fights the
  error registry rather than filling gaps in it: a bad `Content-Type` is a 415 where
  SPEC §5.1 wants a 400 `MALFORMED_REQUEST`, so we would be overriding its decisions
  instead of making our own. Throughput is not the constraint on a service whose data
  fits in 11 KB.
- **Node 22 LTS, pinned in `.nvmrc` and `engines`.** The runtime is pinned rather than
  left to whatever is installed, because the Docker image and a local `npm run dev` have
  to agree. Node 18 was the version on hand and is end-of-life; it is also below the
  floor for the current test runner and mail client, so building against it would have
  meant pinning two dependencies back to ship an unsupported runtime.
- **Zod for request *and* environment validation.** One detail earns it: `issue.path`
  joined with dots *is* the `fieldErrors` key the envelope specifies, so there is no
  translation layer between a validation failure and the error the client renders under
  an input. Reusing it for env parsing means a missing `SALES_INBOX_ADDRESS` fails at
  boot with every other problem listed alongside it, rather than at the first enquiry.
- **Vitest and Supertest.** Vitest runs TypeScript with no separate build step, so the
  suite runs between edits as SPEC §11.3 requires. Supertest drives the app object
  without binding a port, which is what makes the contract tests cheap enough to cover
  every endpoint.
- **A route table, because 405 has no framework support.** Express answers a known path
  under an unsupported verb with 404, not the 405 the registry defines. Rather than
  maintain a second list of paths for the fallback to consult, the routes are registered
  *from* one table and the fallback reads the same table, so the two cannot drift. The
  alternative — hand-written `.all()` handlers per path — is the same information written
  twice.
- **Body parsing is scoped to the one endpoint that has a body**, not mounted
  app-wide. Mounted globally it runs before routing, so a POST to a GET-only path gets
  judged on its `Content-Type` and answered 400 when the honest answer is that the verb
  is wrong, whatever the body claims to be. This was caught by the contract test for
  405, not by reading the code.
- **The catalogue cache de-duplicates concurrent refreshes.** Home fires `/home` and
  `/categories` in parallel by design, so without single-flight both miss a cold cache
  and both fetch upstream — on the first request of every session. The cache holds the
  in-progress refresh and the second caller awaits it.
- **`POST /enquiries` answers 200, not 202.** 202 Accepted promises processing that has
  not happened yet, which is the opposite of this endpoint's guarantee: the mail server
  has already accepted the message by the time the response is written. Using 202 would
  contradict in a status code the thing the response body exists to assert.
- **Shutdown is graceful, and reports one outcome.** Compose sets
  `restart: unless-stopped`, so the process is sent SIGTERM on every `docker compose
  down` and every restart. Tearing down mid-request would, in the worst case, show a
  customer a failure for an enquiry SMTP had already accepted — so in-flight requests get
  a bounded grace period, idle keep-alive sockets are released so they do not hold the
  server open for the whole of it, and the exit code distinguishes a clean stop from an
  abandoned one. The grace period exists because the alternative to a bound is hanging
  forever on one stuck request.
- **The rate limiter keys on the socket IP, with `trust proxy` left unset.** Correct for
  local docker-compose, where nothing sits in front of the service. Behind a reverse
  proxy or load balancer it would be wrong in a way that fails quietly — every client
  collapses into one bucket keyed by the proxy, so the first few enquiries from anyone
  exhaust the window for everyone. Named here rather than pre-configured, because
  guessing a proxy depth that does not exist yet is how `trust proxy` becomes a
  header-spoofing hole.

## Client toolchain

Resolved against a real build rather than a compatibility table, because the current
matrix is recent enough that guessing wastes more time than probing: **Gradle 9.8.2, AGP
9.3.0, Kotlin 2.4.21, Compose Multiplatform 1.12.2, compileSdk 37, minSdk 26, JDK 21.**

- **AGP is held one minor behind its own latest on purpose.** 9.5.0 is the current stable
  and the build is green on it; Android Studio 2026.1 refuses to sync it, because its
  bundled AGP is a `9.4.0-dev` build and its stable ceiling is 9.3.0. The command line
  does not care — `assembleDebug` and the full test suite pass identically on 9.3.0,
  9.3.3 and 9.5.0 — so the only thing the newer version buys is a version number, and the
  only thing it costs is that the project cannot be opened in a released IDE. For a
  project whose point is to be read by someone else, that trade is the wrong way round.
  Revisit when a Studio that supports 9.5.0 ships.
- **JDK 21 is pinned, not inherited.** This machine defaults to JDK 26, which AGP does
  not support. `gradle/gradle-daemon-jvm.properties` states a version criterion rather
  than a path, so the build does not depend on whoever set `JAVA_HOME` last and still
  resolves against a locally installed JDK.
- **Two modules, because AGP 9 requires it.** Since AGP 9.0 the `com.android.application`
  plugin cannot be applied alongside Kotlin Multiplatform, so the Android entry point has
  to be its own module: `:shared` holds every layer, `:androidApp` holds an Activity, an
  Application and a manifest. The documented preference for a single module was about not
  splitting the *architecture* into modules, and that still holds — `data`, `domain`,
  `presentation` and `ui` remain one unit with package boundaries. The alternative was
  AGP's `android.builtInKotlin=false` escape hatch, which its own error message calls a
  temporary bypass.
- **No `iosX64`.** Compose Multiplatform 1.12 stopped publishing artifacts for the Intel
  simulator, so declaring the target fails to resolve. Device `iosArm64` and
  `iosSimulatorArm64` are the two that exist.
- **The iOS framework is linked in CI-able form, with no Xcode project.** `./gradlew
  :shared:linkDebugFrameworkIosSimulatorArm64` is a check a reviewer can actually run,
  which is worth more than an `iosApp/` directory nothing here can build.

## Forward compatibility of the error contract

`ErrorCode` carries an `UNKNOWN` member and parses any unrecognised wire value into it.

The backend may add a code before the client is rebuilt — the two ship separately, and
the whole point of branching on `code` rather than message text is that the contract can
evolve. Without a fallback, a new code would fail deserialization and turn a
forward-compatible API change into a crash on the screen that received it. Degrading to
a generic retryable error is the behaviour a customer would choose.

`developerMessage` is dropped at the mapping boundary rather than carried into the UI
layer. It ships in the response body and is never displayed, so discarding it where the
DTO becomes a domain error means no layer above can render it by accident.

## Reaching the backend from a device

The documented default is `http://10.0.2.2:8080` — inside the emulator, `localhost` is
the emulator, and `10.0.2.2` is the host.

**It does not work everywhere.** On the machine this was built on, the emulator's NAT
answers ICMP but never completes a TCP connection to the host, so the app times out with
the backend plainly running and the failure looks like an app bug. `adb reverse` tunnels
over the adb connection instead, sidestepping the NAT entirely, and is also the route
that works for a physical device over USB:

```
adb reverse tcp:8080 tcp:8080
./gradlew :androidApp:installDebug -Pcurato.apiBaseUrl=http://localhost:8080
```

The base URL is a Gradle property feeding a build-config field, so neither path needs a
code edit. Named here because a reviewer hitting the NAT problem would otherwise have no
reason to suspect their emulator rather than the app.

## Who owns a component's placement

Two design-system components were changed while building Category, both the same way and
for the same reason: **`ProductCard` no longer sets its own width, and `SectionHeader` no
longer sets its own gutter.** Their callers do.

Both had hard-coded the value that was right for Home. `ProductCard` applied
`width(156.dp)` internally, which a grid cell cannot override — a component's own
modifier is applied last, so a `fillMaxWidth` from the caller silently loses to it, and a
two-column grid of 156dp cards is not a grid. `SectionHeader` applied a 16dp horizontal
gutter, which in a grid whose `contentPadding` already supplies one indents the heading
to 32dp while the cards beside it sit at 16dp.

The screen kit draws the same distinction in its own CSS: `.a-card` has a fixed width on
a shelf and `.a-grid .a-card { width: auto }` in a grid, and `.a-sh` and `.a-grid` are
siblings that each carry their own padding rather than nesting. A component decides how
it looks; its container decides where it sits and how big it is.

## Passing a route argument to a ViewModel

`CategoryViewModel` takes its slug as a plain constructor `String`, supplied through
Koin's `parametersOf`. Not a `SavedStateHandle`.

The slug is already part of the route, so it lives in the back-stack entry and Navigation
restores it across process death on its own — a handle would store a second copy of
something that is already durable. Keeping the constructor a `String` is also what lets
the ViewModel tests build one with no Koin, no Android and no navigation library in
sight. `SavedStateHandle` earns its place where a screen holds state the route does not
carry, which is the Search screen's query (SPEC §7.4) rather than this.

## Category's state is not Home's

Home holds a `SectionState` per section because it fires two independent calls and either
can fail alone. Category fires one, and both its sections come out of that single
response — there is no state in which "Top rated" loaded while "More in" failed.

So Category has its own three-state type rather than reusing Home's. Sharing it would
have meant importing a shape from `presentation.home` in order to model a situation that
cannot arise, and the saving would have been about fifteen lines. The screen's one real
rule — that an empty `additional` omits the second section entirely — is a named property
on the content state, so it is a thing a test asserts rather than a condition buried in a
composable.

## Carrying the seen price to the enquiry

The price a customer was looking at travels as a route query parameter —
`product/{id}/enquire?seenPrice=55.99` — rather than in a shared object or a handle
passed between screens.

SPEC §7.5 describes it as captured "into the enquiry draft on navigation", and §10.2 puts
the draft in a `SavedStateHandle`. The route is the better carrier for the same
information. A `Double` is as much a primitive as an id, so §8's rule that routes carry
primitives only still holds, and `product/{id}/enquire` stays addressable by URL alone.
It is optional by construction: a deeplink, or any caller with no price to offer, omits
the parameter, and §6 already tolerates an absent `seenPrice` because it is a diagnostic
rather than an input.

One deliberate divergence: because the route lives in the back stack, the price now
survives process death instead of being dropped on a cold restore as §7.5 anticipated.
That is strictly more useful — the drift note appears more often — and it costs nothing,
because the value was never authoritative. The backend resolves the product itself and
emails the price *it* holds; `seenPrice` only ever adds "customer saw $X" beside it.

It is also deliberately a `String` in the route rather than `NavType.FloatType`. Prices
are `Double`, and rounding one through a float to save four bytes is how 109.95 becomes
109.94999 in an email to sales. Absent, blank and unparseable all collapse to null.

## Search as you type, with a debounce

Results arrive 300ms after the field stops changing, with no submit control. SPEC §7.4
says only that "submission is disabled below 2 characters" and never mentions debounce
either way, so this is a choice rather than a reading.

The 300ms is the Screen Kit's own `emphasized` duration rather than a new number. It is
what turns typing `backpack` into one request instead of eight — measured through a
proxy on a device, not assumed — while a deliberate pause mid-word still produces two,
which is the behaviour a customer correcting a term actually wants.

**The debounce applies to typing, and only to typing.** A query restored after process
death, and a Retry after a failure, both fire immediately: neither is mid-keystroke, and
making a restored screen wait 300ms behind a "Keep typing" panel it is certain to replace
is a flicker for nothing.

Two smaller rules fall out of it. A term is skipped if it equals the query the server
already echoed, so `"bag"` → `"bag "` does not spend a request redrawing an identical
grid. And shortening a term below two characters cancels the search already in flight,
rather than letting its results land on a screen that stopped asking for them.

The alternative — an explicit submit key — would have read §7.4 more literally and kept
timing out of the tests. It was rejected because a 20-product catalogue answers in
milliseconds, and making someone press a key to see that is friction with nothing behind
it.

## A `SavedStateHandle`, for the two screens that need one

`CategoryViewModel` and `ProductViewModel` both take their route parameter as a plain
constructor argument, and both carry a comment saying a `SavedStateHandle` would buy
nothing — the slug and the id already live in the back-stack entry, and Navigation
restores them. `SearchViewModel` and `EnquiryViewModel` take one. Those positions do not
conflict: a slug and an id cannot change while the screen is open, whereas a search term
changes with every keystroke and an enquiry form is being typed into — in both cases the
route stops describing the screen's contents the moment the customer touches it.

Enquiry is the interesting one, because it needs *both*: the product id is a route
parameter that cannot change, so it stays a plain constructor argument, and the form
draft goes in the handle. Splitting them that way keeps each mechanism doing the thing it
is good at rather than forcing one to cover both.

This is what §7.4 asks for and what the table in *State persistence* above already
anticipated. It arrives through the existing `parametersOf(...)` path as
`entry.savedStateHandle`, so it needs no new Koin capability — and the query is read with
`getStateFlow`, which makes the field's text persisted *by construction*: there is no
second copy held beside it and nothing to forget to write. Navigation seeds that handle
from the route's arguments, so `search?q=bag` is read from the same place, with no
separate seed parameter that could disagree with it.

Verified the only way it can be: killing the process with the task backgrounded and
resuming it. A new pid comes up with the term still in the field and the search re-run.

## The enquiry's two stores, and why they are kept apart

[SPEC.md](SPEC.md) §7.6 says conflating the reusable contact store with the ephemeral
form draft "is the easiest way to implement this screen wrongly", so they are not merely
documented apart, they are built apart: a DataStore record keyed `saved_contact`, and one
serialized value in the route entry's `SavedStateHandle`. They have opposite lifetimes —
one survives app restarts indefinitely and is cleared the instant consent is withdrawn;
the other survives process death only and dies with the route.

The subtler half is **inside** the draft. Its two parts are cleared at different moments:
field values go on a successful submission, and the completion state (`submitted`,
`acceptedAt`) stays until the route itself leaves the back stack. Clearing the draft
wholesale on success would destroy `submitted` at the exact moment it starts mattering,
and a screen restored after process death would present a filled form with a live Submit
for an enquiry that had already been sent.

**The completion state carries two fields beyond the spec's table** — the email the
confirmation was addressed to, and the product title it named. Both are form values,
which §7.6 clears on success, yet the thank-you's copy reads them. Holding copies here is
what lets a restored thank-you render *with no network call at all*. That is not an
optimisation: with a fetch, an unreachable catalogue or a product removed in the meantime
could replace a send the backend had already confirmed with an error panel. Verified by
stopping the backend entirely, killing the process, and relaunching — the thank-you comes
back whole. Neither field makes anything submittable, which is the rule §7.6 is actually
protecting.

## The client's validation mirror is deliberately looser than the server's

§6 calls client-side validation "mirrored for immediate feedback" and makes the server
authoritative. That asymmetry decides what to do where the two cannot be made identical.

Of the two ways to disagree with the backend, only one is harmful. A client rule
*stricter* than the server's refuses to send something the backend would have accepted,
and the customer has no way to argue with it. A client rule *looser* costs one round trip
and comes back as a 422 the screen already renders against the same inputs. So the email
check is intentionally permissive — one `@`, a dotted domain, no whitespace, a length cap
— and leaves the judgement calls to Zod. Name, phone and message mirror §6 exactly,
because those rules are mechanical and cannot drift.

Every message is a constant holding the backend's exact string, and the envelope tests
assert them against a real 422 payload. The banner copy is the same: `VALIDATION_FAILED`'s
`userMessage` is needed as a constant precisely because the client half can fail *without
a round trip*, so there is no envelope to read it from.

## One dependency added for one date

`kotlinx-datetime`, for a single line: the thank-you's `Accepted 5 Nov 2026, 11:53`.

The backend sends `acceptedAt` as a UTC instant. The Kotlin standard library will parse
it — `kotlin.time.Instant` does that much — but carries no time zone database, so there
is no way to render it on the customer's own clock without a library. The alternatives
were a stamp labelled UTC, which is correct and useless to someone in IST reading a time
five and a half hours off their own, or dropping the stamp, which loses the one part of
the thank-you that evidences the backend actually confirmed.

On Android it resolves the `jvm` variant and reads `java.time`, available natively from
API 26 — exactly this project's `minSdk`, so no core-library desugaring is required.

An unparseable timestamp yields **no stamp**, never an error and never a placeholder: the
enquiry has already been accepted by then, and a cosmetic problem must not be allowed to
make a confirmed send look conditional.

## Two things the device found that the tests could not

Both were caught by looking at screenshots, and both had passed a green suite.

**A banner nobody could see.** Submit lives in the sticky bar at the bottom, so a
validation failure almost always arrives with the form scrolled to its end — and the
banner is composed at the top. The field outlines turned red and the sentence explaining
why sat off-screen above. The form now scrolls itself to the banner when one appears. A
unit test asserting `banner != null` passes either way; this is only visible on a screen.

**A button that named an attempt that never happened.** The label keyed off "is a banner
showing" rather than "did a request fail", so the client's own validation rejecting a
form relabelled Submit to *Retry*. The kit draws all three frames and they disagree on
this — *Send enquiry* on the validation frame, *Retry* on the send-failure one, *Try
again* on the rate-limited one — which makes it a rule rather than a preference. The
banner now carries which frame it is, and the label is read off that.

A third, smaller one came from the same pass: arriving at the enquiry screen for a
product that no longer resolves used to say "Your enquiry was not sent", which describes
an attempt the customer had not made. That copy is right after a submission and wrong
before one, so the state carries which path produced it.

## Where the Screen Kit contradicts itself

Three places, each resolved toward the drawn artifact over the written table.

**The search field.** The kit's component inventory lists `AppTextField` — "label always
rendered", 10dp radius — against Search. The three Search frames it actually draws are a
label-less 999px pill in the app bar, with a leading magnifier and no clear control. The
mockup is the more specific of the two, so Search gets its own `SearchField`, and the
labelled variant went to Enquiry, where it is the thing the kit actually draws — both now
exist, each matching the frames it appears in. There is also no clear affordance and no
glyph for one anywhere in the kit's sprite, so none was invented.

**The enquiry's error copy.** The kit draws warmer strings than the backend's registry —
"We could not send your enquiry. Your details are saved — try again." against the
registry's "We could not send your enquiry. Please try again." The server's wins, for two
reasons. §5 makes `userMessage` "the only *general* error string displayed", so client-side
substitution would duplicate copy that is meant to change without a client release. And
in this specific case the kit's wording asserts something that is false exactly when
consent is unchecked: the details are not saved. The kit's glyph distinction is kept,
because that is about which *kind* of failure this is rather than about wording.

**The below-minimum copy.** The kit says this panel uses "the same copy as the server's
`fieldErrors.q`", then draws *"Enter at least 2 characters to search."* where the server
sends *"Enter at least 2 characters"*. The caption's intent beats its own drawing: there
is one constant, holding the server's exact string, and a test asserts it equals the
`fieldErrors.q` a real 422 carries. The claim is now mechanically true rather than nearly
true.

The kit draws no loading and no error frame for Search, though both are reachable — §4.4
lists `UPSTREAM_UNAVAILABLE` and `INTERNAL_ERROR`, and the client adds
`NETWORK_UNAVAILABLE` and `TIMEOUT`. Both are derived from Category's drawn equivalents,
since the two screens share a grid.

## A clock, injected

`RecentProduct.viewedAt` had never been written: nothing called `record()` until the
product screen existed, so there was no time source anywhere in `shared/` and no
`kotlinx-datetime` dependency. `ProductViewModel` takes `now: () -> Long`, defaulting to
`kotlin.time.Clock.System` from the Kotlin 2.4 standard library.

Injected rather than called inline for the same reason the backend's cache takes a clock:
a test can then assert the recorded timestamp instead of tolerating whatever the machine
happened to say. "Recently viewed shows the newest first" is an ordering claim, and
ordering claims are only testable when time is an input.

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

## Android app identity

The Catalogue mark uses three product cards to represent browsing. One vector
serves as the adaptive launcher foreground, the themed launcher silhouette, and
the system splash mark. The launcher and splash icon use the kit's teal and mint,
with the splash window matching the app surface in each theme.

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

## Container runtime

Verified with **Colima**, not Docker Desktop. Docker Desktop's licence requires a paid
subscription for organisations past a size threshold, and this project commits to needing
no accounts and no paid services anywhere in the stack — so standardising the setup
instructions on it would contradict that commitment for exactly the reader most likely to
be evaluating it from a company laptop. Colima is Apache-2.0, installs entirely inside
Homebrew's prefix with no privileged helper and no password prompt, and exposes an
ordinary Docker socket, so `docker compose` is the same command either way.

Nothing in the compose file or the Dockerfile is Colima-specific. Docker Desktop, Rancher
Desktop, Podman or a remote daemon all work; the README names Colima because it is what
the measured numbers came from, not because it is required.

## Local SMTP catcher

Mailhog, kept despite being unmaintained and publishing an amd64-only image — which means
emulation on Apple Silicon and a 572 MB pull against our own 257 MB service image. The
platform is declared explicitly in the compose file so the mismatch is a stated fact
rather than a warning on every `up` that reads like a misconfiguration.

**Mailpit is the better choice and was not taken.** It is maintained, has native arm64
images at roughly a tenth the size, and is a drop-in replacement on the same two ports.
The reason to stay is that Mailhog is named throughout the specification, the README and
the screen kit, and swapping it would edit four documents to change a development
dependency that works. Worth doing the moment anything else about the mail path changes;
not worth a doc sweep on its own.

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
