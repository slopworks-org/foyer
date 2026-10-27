package app.foyer.search

import kotlin.math.abs
import kotlin.math.min

/** Edit costs, in tenths of a "typo". A neighbouring-key slip is cheaper than a random one. */
private const val COST_NEIGHBOUR = 6
private const val COST_SUBSTITUTE = 10
private const val COST_INDEL = 10
private const val COST_TRANSPOSE = 8

/** How much typo budget a query of [length] chars gets. Short queries must be exact. */
internal fun typoBudget(length: Int): Int = when {
    length <= 2 -> 0
    length <= 4 -> 10
    length <= 6 -> 15
    length <= 8 -> 20
    else -> 30
}

internal class FuzzyPrefix(val cost: Int, val end: Int)

/**
 * Weighted optimal-string-alignment distance between [q] and the best prefix of
 * [t] starting at [from]. Returns null if every prefix costs more than [budget].
 */
internal fun fuzzyPrefix(q: String, t: String, from: Int, budget: Int): FuzzyPrefix? {
    val m = q.length
    if (m == 0) return null
    val maxLen = min(t.length - from, m + budget / COST_INDEL)
    if (maxLen <= 0) return null
    // Short queries tolerate a typo but not in the very first letter.
    if (m <= 4 && q[0] != t[from]) return null

    val cols = maxLen + 1
    var prev2 = IntArray(cols)
    var prev = IntArray(cols) { it * COST_INDEL }
    var cur = IntArray(cols)
    for (i in 1..m) {
        cur[0] = i * COST_INDEL
        var rowMin = cur[0]
        for (j in 1 until cols) {
            val a = q[i - 1]
            val b = t[from + j - 1]
            val sub = when {
                a == b -> 0
                areNeighbourKeys(a, b) -> COST_NEIGHBOUR
                else -> COST_SUBSTITUTE
            }
            var v = minOf(prev[j - 1] + sub, prev[j] + COST_INDEL, cur[j - 1] + COST_INDEL)
            if (i > 1 && j > 1 && a == t[from + j - 2] && q[i - 2] == b && a != b) {
                v = min(v, prev2[j - 2] + COST_TRANSPOSE)
            }
            cur[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > budget) return null
        val tmp = prev2
        prev2 = prev
        prev = cur
        cur = tmp
    }
    var best = -1
    for (j in 1 until cols) {
        if (prev[j] > budget) continue
        if (best < 0 || prev[j] < prev[best] || (prev[j] == prev[best] && abs(j - m) < abs(best - m))) best = j
    }
    if (best < 0) return null
    return FuzzyPrefix(prev[best], from + best)
}

/** A scored match of one query form against one prepared target. Spans are in target text. */
internal class Match(val score: Int, val spans: List<IntRange>)

internal object Score {
    const val LABEL_PREFIX = 1000
    const val WORD_PREFIX = 860
    const val INITIALS = 800
    const val FUZZY_LABEL_PREFIX = 720
    const val FUZZY_WORD_PREFIX = 650
    const val SUBSTRING = 480
    const val PER_TYPO = 9 // per cost unit, so one full typo = -90
    const val COVERAGE = 40
}

private fun coverage(q: Int, t: Int) = if (t == 0) 0 else Score.COVERAGE * min(q, t) / t

/** Matches a single compact query (no spaces) against [p]. */
internal fun matchCompact(q: String, p: Prepared, fuzzy: Boolean = true): Match? {
    val t = p.text
    if (q.isEmpty() || t.isEmpty()) return null
    val cov = coverage(q.length, t.length)

    if (t.startsWith(q)) return Match(Score.LABEL_PREFIX + cov, listOf(0 until q.length))

    var best: Match? = null
    fun offer(m: Match?) {
        if (m != null && (best == null || m.score > best!!.score)) best = m
    }

    for (w in 1 until p.wordCount) {
        val s = p.wordStarts[w]
        if (t.startsWith(q, s)) {
            offer(Match(Score.WORD_PREFIX + cov - 4 * w, listOf(s until s + q.length)))
            break
        }
    }
    if (best != null) return best

    if (q.length >= 2 && p.wordCount >= 2) offer(matchInitials(q, p, cov))

    if (!fuzzy) return best

    val budget = typoBudget(q.length)
    if (budget > 0) {
        fuzzyPrefix(q, t, 0, budget)?.let {
            offer(Match(Score.FUZZY_LABEL_PREFIX + cov - Score.PER_TYPO * it.cost, listOf(0 until it.end)))
        }
        for (w in 1 until p.wordCount) {
            val s = p.wordStarts[w]
            fuzzyPrefix(q, t, s, budget)?.let {
                offer(Match(Score.FUZZY_WORD_PREFIX + cov - Score.PER_TYPO * it.cost - 4 * w, listOf(s until it.end)))
            }
        }
    }

    if (q.length >= 3) {
        val at = t.indexOf(q)
        if (at > 0) offer(Match(Score.SUBSTRING + cov - min(at, 20), listOf(at until at + q.length)))
    }
    return best
}

/**
 * "ytm" → **Y**ou**T**ube **M**usic, "tgx" → **T**ele**g**ram **X**: the query is cut into
 * chunks, each one a prefix of a later word. Skipping words costs a little.
 */
private fun matchInitials(q: String, p: Prepared, cov: Int): Match? {
    val t = p.text
    val memo = HashMap<Long, Pair<Int, List<IntRange>>?>()

    // best (penalty, spans) for matching q[qi..] using words strictly after `afterWord`
    fun solve(qi: Int, afterWord: Int): Pair<Int, List<IntRange>>? {
        if (qi == q.length) return 0 to emptyList()
        val key = qi.toLong() shl 32 or (afterWord + 1).toLong()
        if (memo.containsKey(key)) return memo[key]
        var best: Pair<Int, List<IntRange>>? = null
        for (w in afterWord + 1 until p.wordCount) {
            val s = p.wordStarts[w]
            val e = p.wordEnd(w)
            var len = 0
            while (qi + len < q.length && s + len < e && q[qi + len] == t[s + len]) len++
            val skipPenalty = (w - afterWord - 1) * 15
            for (l in len downTo 1) {
                val rest = solve(qi + l, w) ?: continue
                val pen = skipPenalty + rest.first
                if (best == null || pen < best.first) best = pen to (listOf(s until s + l) + rest.second)
            }
        }
        memo[key] = best
        return best
    }

    val res = solve(0, -1) ?: return null
    if (res.second.size < 2) return null
    return Match(Score.INITIALS + cov - res.first, res.second)
}

/** Best match of one query token against any single word of [p] (for multi-word queries). */
internal fun matchToken(q: String, p: Prepared, fuzzy: Boolean = true): Match? {
    val t = p.text
    var best: Match? = null
    fun offer(m: Match) {
        if (best == null || m.score > best!!.score) best = m
    }
    val budget = if (fuzzy) typoBudget(q.length) else 0
    for (w in 0 until p.wordCount) {
        val s = p.wordStarts[w]
        val e = p.wordEnd(w)
        val cov = coverage(q.length, e - s)
        if (t.startsWith(q, s) && s + q.length <= e) {
            offer(Match(Score.WORD_PREFIX + cov, listOf(s until s + q.length)))
            continue
        }
        if (budget > 0) {
            fuzzyPrefix(q, t.substring(0, e), s, budget)?.let {
                offer(Match(Score.FUZZY_WORD_PREFIX + cov - Score.PER_TYPO * it.cost, listOf(s until it.end)))
            }
        }
        if (fuzzy && q.length >= 3) {
            val at = t.indexOf(q, s)
            if (at > s && at + q.length <= e) offer(Match(Score.SUBSTRING + cov, listOf(at until at + q.length)))
        }
    }
    return best
}
