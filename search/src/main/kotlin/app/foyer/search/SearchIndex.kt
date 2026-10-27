package app.foyer.search

enum class ItemKind { App, Shortcut, Widget }

/**
 * Something that can be found. [key] must be unique and stable; it is the last tie-breaker.
 * For shortcuts and widgets, [parentLabel] is the owning app ("Telegram" for a "Мама" chat).
 * [aliases] are the user's own names for it ("tg", "телега"): they find it like its label does.
 */
data class SearchItem(
    val key: String,
    val label: String,
    val kind: ItemKind = ItemKind.App,
    val parentLabel: String? = null,
    val aliases: List<String> = emptyList(),
)

/** [highlights] are index ranges into [SearchItem.label]. */
data class SearchHit(
    val item: SearchItem,
    val score: Int,
    val highlights: List<IntRange>,
)

/**
 * Immutable index over a fixed set of items. [search] is a pure function of the query:
 * no history, no usage statistics, no randomness. Ties are broken by kind, label and key,
 * so the same text always yields the same list in the same order with the same highlights.
 */
class SearchIndex(items: List<SearchItem>) {

    private class Entry(
        val item: SearchItem,
        val native: Prepared,
        val latin: Prepared,
        val parentNative: Prepared,
        val parentLatin: Prepared,
        val aliases: List<Pair<Prepared, Prepared>>,
        val sortLabel: String,
    )

    private val entries: List<Entry> = items.map { item ->
        val native = prepareNative(item.label)
        val parentNative = item.parentLabel?.let(::prepareNative) ?: Prepared.Empty
        Entry(
            item = item,
            native = native,
            latin = latinize(native),
            parentNative = parentNative,
            parentLatin = latinize(parentNative),
            aliases = item.aliases.map(::prepareNative).filter { it.wordCount > 0 }.map { it to latinize(it) },
            sortLabel = native.text,
        )
    }

    val size get() = entries.size

    fun search(query: String, limit: Int = 60): List<SearchHit> {
        val forms = queryForms(query)
        if (forms.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Entry, SearchHit>>()
        for (e in entries) {
            var best: Pair<Int, Set<Int>>? = null
            for (f in forms) {
                val m = matchEntry(f, e) ?: continue
                if (best == null || m.first > best.first) best = m
            }
            if (best == null) continue
            val score = best.first - when (e.item.kind) {
                ItemKind.App -> 0
                ItemKind.Shortcut -> SHORTCUT_PENALTY
                ItemKind.Widget -> WIDGET_PENALTY
            }
            hits += e to SearchHit(e.item, score, toRanges(best.second))
        }
        hits.sortWith(
            compareByDescending<Pair<Entry, SearchHit>> { it.second.score }
                .thenBy { it.first.item.kind.ordinal }
                .thenBy { it.first.sortLabel }
                .thenBy { it.first.item.key },
        )
        return hits.asSequence().take(limit).map { it.second }.toList()
    }

    /** One way of reading the query: in its own script or transliterated, as typed or with the layout swapped. */
    private class QueryForm(val tokens: List<String>, val latin: Boolean, val penalty: Int, val fuzzy: Boolean) {
        val compact = tokens.joinToString("")
    }

    private fun queryForms(query: String): List<QueryForm> {
        val typed = query.trim()
        if (typed.isEmpty()) return emptyList()
        val forms = ArrayList<QueryForm>(4)
        // A typo on top of a wrong layout is mostly noise: swapped forms must match exactly.
        fun add(text: String, penalty: Int, fuzzy: Boolean) {
            val native = prepareNative(text)
            if (native.text.isEmpty()) return
            forms += QueryForm(words(native), latin = false, penalty = penalty, fuzzy = fuzzy)
            forms += QueryForm(words(latinize(native, keepSigns = true)), latin = true, penalty = penalty + TRANSLIT_PENALTY, fuzzy = fuzzy)
        }
        add(typed, 0, fuzzy = true)
        val swapped = swapLayout(typed)
        if (swapped != typed) add(swapped, LAYOUT_PENALTY, fuzzy = false)
        return forms.filter { it.compact.isNotEmpty() }
    }

    private fun words(p: Prepared): List<String> =
        (0 until p.wordCount).map { p.text.substring(p.wordStarts[it], p.wordEnd(it)) }

    /** Best (score, highlighted label indices) for [f] against [e], or null. */
    private fun matchEntry(f: QueryForm, e: Entry): Pair<Int, Set<Int>>? {
        val label = if (f.latin) e.latin else e.native
        val parent = if (f.latin) e.parentLatin else e.parentNative
        var best: Pair<Int, Set<Int>>? = null
        fun offer(score: Int, spans: Set<Int>) {
            val s = score - f.penalty
            if (best == null || s > best!!.first) best = s to spans
        }

        matchCompact(f.compact, label, f.fuzzy)?.let { m ->
            offer(m.score, label.indicesOf(m.spans))
        }
        // An alias counts as much as the label itself, but has nothing in the label to highlight.
        for ((native, latin) in e.aliases) {
            matchCompact(f.compact, if (f.latin) latin else native, f.fuzzy)?.let { m -> offer(m.score, emptySet()) }
        }
        // A widget is also found by its app's name alone ("spotify" → Spotify's widgets),
        // a notch below a match on its own label.
        if (e.item.kind == ItemKind.Widget && parent.wordCount > 0) {
            matchCompact(f.compact, parent, f.fuzzy)?.let { m -> offer(m.score - WIDGET_BY_APP_PENALTY, emptySet()) }
        }

        if (f.tokens.size > 1) {
            // Every token must land somewhere; for shortcuts the app name counts too ("tg мама").
            var minScore = Int.MAX_VALUE
            var hitsLabel = false
            val spans = HashSet<Int>()
            for (tok in f.tokens) {
                val inLabel = matchToken(tok, label, f.fuzzy)
                val inParent = if (parent.wordCount > 0) matchToken(tok, parent, f.fuzzy) else null
                val m = when {
                    inLabel == null -> inParent
                    inParent == null -> inLabel
                    inParent.score > inLabel.score -> inParent
                    else -> inLabel
                } ?: return best
                if (m === inLabel) {
                    hitsLabel = true
                    spans += label.indicesOf(m.spans)
                }
                minScore = minOf(minScore, m.score)
            }
            if (hitsLabel) offer(minScore + MULTI_TOKEN_BONUS, spans)
        }
        return best
    }

    private fun Prepared.indicesOf(spans: List<IntRange>): Set<Int> {
        val out = HashSet<Int>()
        for (r in spans) sourceIndices(r.first, r.last + 1, out)
        return out
    }

    private fun toRanges(indices: Set<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.sorted()
        val out = ArrayList<IntRange>()
        var start = sorted[0]
        var prev = start
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v != prev + 1) {
                out += start..prev
                start = v
            }
            prev = v
        }
        out += start..prev
        return out
    }

    private companion object {
        const val TRANSLIT_PENALTY = 15
        const val LAYOUT_PENALTY = 35
        const val SHORTCUT_PENALTY = 60
        const val WIDGET_PENALTY = 70
        const val WIDGET_BY_APP_PENALTY = 30
        const val MULTI_TOKEN_BONUS = 20
    }
}
