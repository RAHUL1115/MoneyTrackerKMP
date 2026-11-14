---
name: kotlin-readable-code
description: Use when writing, refactoring or reviewing Kotlin or Compose code in this app — splitting MoneyRepository into stores, moving screen logic into state holders, narrowing platform interfaces, branching on TxnKind, coroutines/Flow, or error handling.
---

# Readable Kotlin for Money Tracker

The bar: a newcomer reads a file top to bottom and understands it on the first pass. Every SOLID letter, KISS and DRY score 8+/10, and readability wins any tie: choose the plain, obvious shape over the clever one.

Every change is a **pure move**: behaviour, database schema and backup format stay exactly as they were. Logic gets relocated, not rewritten. If a move exposes a bug, write it down and leave it for a separate change.

## Size limits

These are what detekt enforces. New code stays inside them:

| Thing | Limit |
|---|---|
| Class or file | ≤ 400 lines |
| Public functions per class | ≤ 25 |
| Function | ≤ 40 lines, ≤ 4 levels of nesting |
| Composable | Draws only: no database calls, no `withContext`, no business rules |

## Where code goes

Code is grouped by feature under `app.moneytracker` (why: `docs/adr/0018-package-by-feature.md`).

| New thing | Goes in |
|---|---|
| Screen | `<feature>/fresh/` for the new layout, `<feature>/classic/` for the classic one |
| State holder shared by both layouts | the `<feature>/` root |
| Store, coordinator, database code | `data/` (Money Manager formats in `data/moneymanager/`) |
| Money rule or parser with no UI | `domain/` (smart entry in `domain/ai/`) |
| Shared Compose part | `designsystem/fresh/` or `designsystem/classic/` |
| Platform capability interface | `core/Platform.kt`, with its Android side in `androidMain/.../platform/` |
| Widget, receiver, service, activity | the `androidMain` package root, so the manifest names stay the same |

Features: `home`, `activity`, `entry`, `accounts`, `insights`, `messages`, `settings`, `backup`, `webdashboard`, `lists`. A test sits in the same package as the code it tests.

## Names and comments

- Name things in the app's own words from `GLOSSARY.md` (entry, repeat, capture, adjustment). Avoid generic names like `Manager`, `Helper`, `Util` or `Data`.
- Functions are verbs that say the result: `balancesOn(date)`, `saveAccount(a)`. A `Now` suffix means a blocking one-off read; a bare noun means a `Flow`. Keep that existing convention.
- Doc comments are one or two plain-English sentences on *why* or *what it guarantees*, in the style already used in `data/EntryEditor.kt`. Leave self-explanatory lines uncommented.

## Data layer: stores (S, D)

- One store per area of the app (`AccountStore`, `TxnStore`, `SettingsStore`, …). Each store gets the shared `Db` holder (database, `io` dispatcher, clock, cache) through its constructor and nothing else, except another store it truly needs.
- A store owns its SQL queries and its row → domain mapping. Rules that span areas (saving an entry with photos, tags and SMS capture) live in a small coordinator such as `EntryEditor`, not in a store.
- Store functions stay blocking, as they are today. The caller (a state holder) moves the work off the main thread with `withContext(db.io)`. Converting to `suspend` is a separate change, not part of the split.
- Add an interface only where a test needs a fake or a second implementation exists. Use one concrete class everywhere else.

## Screens: state holders (S, D, DRY)

Follow the existing `settings/SettingsModel.kt` pattern: **one plain class shared by both layouts**, with each layout only drawing it.

```kotlin
class AccountsModel(private val accounts: AccountStore, private val scope: CoroutineScope) {
    val state: StateFlow<AccountsState> = …        // immutable data class the UI draws
    fun onHide(id: String) { scope.launch { withContext(io) { accounts.setHidden(id, true) } } }
}

@Composable fun AccountsScreen(model: AccountsModel) {      // wiring: collects, passes down
    val state by model.state.collectAsState()
    AccountsContent(state, onHide = model::onHide)
}

@Composable fun AccountsContent(state: AccountsState, onHide: (String) -> Unit) { … }  // drawing only
```

- The holder owns database calls, validation and launching. Its scope comes from `rememberCoroutineScope()` in the wiring composable. No ViewModel library: the app does not use one.
- The content composable takes immutable state plus callbacks, so it can be built with sample data and no app graph.
- Keep UI mechanics (`LazyListState`, `FocusRequester`, scroll, sheet state) in composition, out of the holder.
- Local state that only one composable cares about (a toggle, a text field) stays a local `remember { mutableStateOf(…) }`. Add a holder only when there's logic worth naming.
- Effects: `LaunchedEffect(key)` keyed on the value that should restart it, `DisposableEffect` always paired with `onDispose`, `rememberCoroutineScope()` for work started by a tap.
- The new layout is the reference implementation. The classic layout switches to the same holder and gets no logic of its own.

## Interfaces at the platform edge (I, D)

- One interface per capability, named for what it does in common terms: `Photos`, `Voice`, `PinSecurity`, `MessageCapture`. Common signatures carry no Android types (`Context`, `Uri`, `Activity`).
- A screen asks only for the capabilities it uses. `AndroidPlatform` may implement many small interfaces. That's fine, because callers see only the slice they need.
- Business rules stay in common code. Android implementations only translate.

## Branching (O)

- Behaviour that depends on the transaction kind lives on `TxnKind` itself (`isAdjustment`, `isTransfer`, `sign`). Callers ask the kind instead of listing kinds.
- A `when` over an enum, sealed type or Boolean names every case and has **no `else`**, so the compiler flags every site when a case is added. Keep `else` only for open input (parsed text, server data).
- Use `when (subject)` when every branch tests one value. Return early only to drop invalid or null input from the main path.
- Smart casts beat `!!` and `as`. If a value can't be null, make its type say so. If it can, handle the null.

## Errors and coroutines

- Catch the specific exception the operation throws (`IOException`, `SQLiteException`, `NumberFormatException`). A broad catch is allowed only at a platform edge, and it must log through `Log` with a reason.
- Around suspending code, a broad catch rethrows `CancellationException` first. `runCatching` around a suspend call needs the same guard.
- A non-UI class exposes blocking or `suspend` functions and never keeps its own `CoroutineScope`. Only state holders launch.
- Use `StateFlow` for current, drawable state. Model loading or absence explicitly (`null`, a sealed state) instead of inventing placeholder data.

## Done means

- `./gradlew :composeApp:testDebugUnitTest` passes, and detekt reports no findings (there is no baseline; a deliberate exception is a local `@Suppress` with a reason).
- Every moved function has the same behaviour, checked by the existing tests or a new one written before the move.
- Every file you touched reads cleanly top to bottom: names say intent, each function does one thing, no dead code is left behind.

---
Adapted in part from [chrisbanes/skills](https://github.com/chrisbanes/skills) (compose-state-and-effects, kotlin-api-design, kotlin-control-flow, kotlin-concurrency-and-flow), Apache License 2.0, © Chris Banes. Rewritten and condensed for this project.
