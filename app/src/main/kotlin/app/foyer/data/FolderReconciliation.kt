package app.foyer.data

/** Only an authoritative absence may remove a persisted member; unknown availability preserves it. */
internal enum class PackagePresence { Present, Missing, Unknown }

internal fun missingFolderApps(keys: List<AppKey>, presence: (AppKey) -> PackagePresence): Set<AppKey> {
    val missing = keys.distinctBy { it.pkg to it.user }.filter {
        runCatching { presence(it) }.getOrDefault(PackagePresence.Unknown) == PackagePresence.Missing
    }.map { it.pkg to it.user }.toSet()
    return keys.filter { (it.pkg to it.user) in missing }.toSet()
}
