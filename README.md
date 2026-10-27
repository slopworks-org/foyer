# Foyer

A personal Android launcher. The home screen is a place, not a list: everything stays
where you put it.

- **Feed**: one vertical, continuously scrolling grid (5 columns) of apps, shortcuts and
  widgets. Every item has explicit cells, so removing or uninstalling one never shifts
  the rest. Nothing is sorted, suggested or added automatically; the first run puts only
  the usual four apps in the dock. Icons have no labels; proportions follow Pixel Launcher.
- **Dock**: an edge-to-edge card with a grab handle, 5 fixed slots and a search bar.
- **Pull down** at the top of the feed: notification shade.
- **Pull the dock up** (or tap its search bar or free space): app search grows out of it,
  keyboard first.
  Pull the results down or go back to close it.
- **Long-press** an item: its shortcuts (`+` pins one to home) and actions; keep holding
  and drag to move it. Long-press empty space: widgets, wallpaper, settings.
  Long-press a widget and keep holding to move it; after release, drag a handle of its frame
  to resize it. Scrolling the feed lets go of the selection.

## Search

`search/` is a plain JVM module with no Android dependencies. `SearchIndex.search(query)`
is a pure function: no history, no usage statistics, deterministic tie-breaks.

Ranking, best first: label prefix → word prefix → initials (`ytm` → YouTube Music) →
fuzzy prefix (weighted Damerau, neighbouring keys are cheaper) → substring. Each query
is also read with the keyboard layout swapped (`ntktuhfv` → Telegram, exact matches only)
and in a shared Latin form (`ютуб` ↔ YouTube, `gosuslugi` ↔ Госуслуги). App shortcuts
are indexed too (`tg мама`). Apps can be given aliases from their long-press menu
("Aliases"); an alias finds the app the same way its own name does. The widget picker has
its own search on the same index: widgets by their own name or their app's (`clock` →
Clock's widgets).

Enter opens the best hit; with no hits it does nothing. "Search the web" is a separate
row that only a tap triggers, so Enter never ends up in the browser.

## Build

```sh
./gradlew check                 # lint and tests
./gradlew :search:test          # search engine tests only
./gradlew :app:installRelease   # signed with the debug key, for sideloading
adb shell cmd role add-role-holder android.app.role.HOME app.foyer 0
```

Requires Android 12+ (API 31). App shortcuts only work while Foyer is the default home app.

## License

[AGPL-3.0-or-later](LICENSE)
