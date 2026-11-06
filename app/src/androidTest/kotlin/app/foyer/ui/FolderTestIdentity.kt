package app.foyer.ui

import app.foyer.data.AppKey
import app.foyer.data.FolderContent

/** Only for existing unique-app fixtures. Duplicate tests always address captured entry IDs. */
internal fun LauncherState.testEntryId(folder: Long, app: AppKey): String =
    (graph.layout.layout.value.items.first { it.id == folder }.content as FolderContent).entries.single { it.app == app }.id
