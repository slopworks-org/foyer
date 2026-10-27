## Project

Foyer is a personal Android home launcher: one vertical feed of apps,
shortcuts and widgets that stays where you put it, a fixed dock, and a fuzzy
app search. Kotlin, Jetpack Compose, Android 12+ (API 31). See `README.md`
for what it does; this file covers how to work on it.

### Commands

```bash
./gradlew check                 # lint + unit tests for every module; the gate
./gradlew :search:test          # search engine tests only
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk
./gradlew :app:installRelease   # install on the connected device
```

The release build is signed with the debug key and is not debuggable, so
`adb shell run-as app.foyer` does not work on it. After installing, make Foyer
the home app with
`adb shell cmd role add-role-holder android.app.role.HOME app.foyer 0`.

`lefthook install` sets up a pre-commit hook that runs `./gradlew check`
(`lefthook.yml`). `LEFTHOOK=0 git commit` skips it once.

### Layout

- `search/`: plain JVM module, no Android dependencies. `SearchIndex` is a pure
  function of its items and the query: ranking, keyboard layout swap,
  transliteration, fuzzy matching. Tests live in `search/src/test`.
- `app/src/main/kotlin/app/foyer/`
  - `FoyerApp.kt`: the `Graph` (app-wide singletons) and the `Searchable`
    snapshot fed to search.
  - `data/`: `Apps.kt` (installed apps and shortcuts), `HomeLayout.kt` (the
    feed, dock, aliases and settings, persisted as `home.json`; every change
    goes through `Edits`).
  - `system/`: launching apps and shortcuts, widget host, status bar.
  - `ui/`: `LauncherState` holds UI state and menu actions; `Root`, `Home`,
    `Search` and `Overlays` are the screens.

### Conventions

- **Strings:** English in `res/values/strings.xml`, Russian in
  `res/values-ru/strings.xml`. Add every new string to both.
- **Search stays pure:** no history, no usage statistics, deterministic
  tie-breaks. Behaviour changes in `search/` come with a test.
- **Layout changes go through `Edits`** and `graph.layout.edit { … }`, never
  by writing `home.json` directly.

### Definition of done

- **Everything in the repo is written in English** — code, comments, docs, commit messages, issues and PRs (title and body), whatever language the task was discussed in. The only exception is localization: translations into other languages.
- **Never commit to main.** Every task starts on a fresh branch off `origin/main`, named `<type>/<short-task>` — `fix/`, `feat/`, `refactor/`, `chore/`.
- **Repo not on GitHub yet** (no `origin` remote): the project is in very active development. Work straight on `main`, but still split the work into small commits, one per logical change, with conventional-commit messages and no AI attribution. The branch, PR and merge rules below start once the repo is on GitHub.
- **One task = one branch = one PR.** Past ~300 changed lines, split it into several PRs. `size:XL` or `size:XXL` means split the PR, not "explain why it is large".
- **PR title is the squash commit subject:** conventional commit with a scope, enforced by `.github/workflows/pr-title.yml`.
  - Type: `feat` `fix` `refactor` `chore` `docs` `test` `ci` `perf` `revert`.
  - Scope (required): a Gradle module — `app` `search` — or `deps` `github`. A new module means adding it to the `scopes:` list in that workflow.
  - Subject: lowercase start, imperative, no trailing period.
  - The body describes this PR alone, following the PR template.
- **No AI attribution.** No `Assisted-by:` / `Co-Authored-By: <LLM>` trailers and no "Generated with …" lines. The PR body becomes the squash commit body, so the PR title workflow fails a PR that carries one.
- **Before opening:** `./gradlew check` green, then open the PR and report its URL.
- **Never merge locally.** Merging happens on GitHub, squash only.
