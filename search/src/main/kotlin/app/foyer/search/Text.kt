package app.foyer.search

import java.text.Normalizer

/**
 * A label (or query) reduced to a matchable form: lowercase words glued together
 * without separators. [src] maps every char of [text] back to the index of the
 * original char it came from, so matches can be highlighted in the original label.
 */
internal class Prepared(
    val text: String,
    val src: IntArray,
    val wordStarts: IntArray,
) {
    val wordCount get() = wordStarts.size

    fun wordEnd(word: Int): Int = if (word + 1 < wordStarts.size) wordStarts[word + 1] else text.length

    fun wordOf(pos: Int): Int {
        var w = 0
        while (w + 1 < wordStarts.size && wordStarts[w + 1] <= pos) w++
        return w
    }

    /** Original-label indices covered by [from] until [to] in [text]. */
    fun sourceIndices(from: Int, to: Int, into: MutableSet<Int>) {
        for (i in from until to) into += src[i]
    }

    companion object {
        val Empty = Prepared("", IntArray(0), IntArray(0))
    }
}

/** Splits [label] into word ranges: on non-alphanumerics, camelCase humps and letter/digit borders. */
internal fun splitWords(label: String): List<IntRange> {
    val words = ArrayList<IntRange>()
    var start = -1
    for (i in label.indices) {
        val c = label[i]
        if (!c.isLetterOrDigit()) {
            if (start >= 0) words += start until i
            start = -1
            continue
        }
        if (start >= 0 && isBoundary(label, i)) {
            words += start until i
            start = i
        }
        if (start < 0) start = i
    }
    if (start >= 0) words += start until label.length
    return words
}

private fun isBoundary(s: String, i: Int): Boolean {
    val prev = s[i - 1]
    val c = s[i]
    if (prev.isDigit() != c.isDigit()) return true
    if (prev.isLowerCase() && c.isUpperCase()) return true
    // "HTMLParser": boundary before the "P"
    if (prev.isUpperCase() && c.isUpperCase() && i + 1 < s.length && s[i + 1].isLowerCase()) return true
    return false
}

/** Lowercases, folds ё→е and strips diacritics, keeping one char per input char. */
internal fun normalizeChar(c: Char): Char {
    val lower = c.lowercaseChar()
    if (lower == 'ё') return 'е'
    if (lower.code < 0x80) return lower
    if (lower in 'а'..'я') return lower
    val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
    return decomposed.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: lower
}

/** Native (same-script) form of a label. */
internal fun prepareNative(label: String): Prepared {
    val sb = StringBuilder(label.length)
    val src = ArrayList<Int>(label.length)
    val starts = ArrayList<Int>()
    for (word in splitWords(label)) {
        starts += sb.length
        for (i in word) {
            sb.append(normalizeChar(label[i]))
            src += i
        }
    }
    return Prepared(sb.toString(), src.toIntArray(), starts.toIntArray())
}

/**
 * Latin "sound" form: Cyrillic is transliterated, then both sides go through the
 * same folding so that "ютуб" and "YouTube", "телеграм" and "Telegram" meet halfway.
 */
internal fun latinize(native: Prepared, keepSigns: Boolean = false): Prepared {
    val sb = StringBuilder(native.text.length + 8)
    val src = ArrayList<Int>(native.text.length + 8)
    val starts = ArrayList<Int>()
    for (w in 0 until native.wordCount) {
        // 1. transliterate the word, keeping a source index per output char
        val raw = StringBuilder()
        val rawSrc = ArrayList<Int>()
        for (i in native.wordStarts[w] until native.wordEnd(w)) {
            val c = native.text[i]
            // Labels drop ь/ъ; a query keeps them as a mark so "неь" does not shrink to "ne".
            val t = if (keepSigns && (c == 'ь' || c == 'ъ')) "'" else TRANSLIT[c] ?: c.toString()
            for (ch in t) {
                raw.append(ch)
                rawSrc += native.src[i]
            }
        }
        // 2. fold spelling variants; collapse doubled letters
        val wordStart = sb.length
        var i = 0
        while (i < raw.length) {
            val rule = FOLD_RULES.firstOrNull { raw.startsWith(it.first, i) }
            val out = rule?.second ?: raw[i].toString()
            for (ch in out) {
                if (sb.length > wordStart && sb[sb.length - 1] == ch && ch.isLetter()) continue
                sb.append(ch)
                src += rawSrc[i]
            }
            i += rule?.first?.length ?: 1
        }
        if (sb.length > wordStart) starts += wordStart
    }
    return Prepared(sb.toString(), src.toIntArray(), starts.toIntArray())
}

private val TRANSLIT: Map<Char, String> = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
    'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
    'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
    'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y",
    'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
)

/** Ordered: longer patterns first. Identity rules stop shorter ones from firing inside them. */
private val FOLD_RULES: List<Pair<String, String>> = listOf(
    "shch" to "sh", "dzh" to "j", "sch" to "sh", "you" to "u",
    "kh" to "h", "ph" to "f", "wh" to "v", "ck" to "k", "ch" to "ch", "ts" to "ts",
    "yu" to "u", "ou" to "u", "oo" to "u", "ee" to "i",
    "c" to "k", "q" to "k", "w" to "v", "x" to "ks",
)

/** Retypes text as if the keyboard layout had been switched (ЙЦУКЕН ↔ QWERTY). */
internal fun swapLayout(s: String): String = buildString(s.length) {
    for (c in s) append(LAYOUT[c] ?: c)
}

private val LAYOUT: Map<Char, Char> = buildMap {
    val en = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~"
    val ru = "йцукенгшщзхъфывапролджэячсмитьбюёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ"
    for (i in en.indices) {
        put(en[i], ru[i])
        put(ru[i], en[i])
    }
}

private val KEY_ROWS = listOf(
    listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
)

private val NEIGHBOURS: Map<Char, Set<Char>> = buildMap<Char, MutableSet<Char>> {
    fun link(a: Char, b: Char) {
        getOrPut(a) { mutableSetOf() } += b
        getOrPut(b) { mutableSetOf() } += a
    }
    for (rows in KEY_ROWS) {
        for ((r, row) in rows.withIndex()) {
            for (i in row.indices) {
                if (i + 1 < row.length) link(row[i], row[i + 1])
                val below = rows.getOrNull(r + 1) ?: continue
                below.getOrNull(i)?.let { link(row[i], it) }
                below.getOrNull(i - 1)?.let { link(row[i], it) }
            }
        }
    }
}

internal fun areNeighbourKeys(a: Char, b: Char): Boolean = NEIGHBOURS[a]?.contains(b) == true
