package app.foyer.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexTest {

    private val apps = listOf(
        "Telegram", "Tele2", "Телефон", "YouTube", "YouTube Music", "YT Studio", "WhatsApp",
        "Госуслуги", "Сбербанк Онлайн", "Яндекс Карты", "Яндекс Музыка", "Chrome", "Calendar",
        "Camera", "Calculator", "Gmail", "Google Maps", "Settings", "Spotify", "Discord",
        "ВКонтакте", "Ozon", "Wildberries", "Т-Банк", "Keep Notes", "Jira", "Контакты",
    ).map { SearchItem(key = "app/$it", label = it) }

    private val shortcuts = listOf(
        SearchItem("sc/tg/mama", "Мама", ItemKind.Shortcut, parentLabel = "Telegram"),
        SearchItem("sc/tg/saved", "Избранное", ItemKind.Shortcut, parentLabel = "Telegram"),
        SearchItem("sc/cam/selfie", "Take a selfie", ItemKind.Shortcut, parentLabel = "Camera"),
        SearchItem("sc/maps/home", "Домой", ItemKind.Shortcut, parentLabel = "Google Maps"),
    )

    private val index = SearchIndex(apps + shortcuts)

    private fun top(q: String) = index.search(q).firstOrNull()?.item?.label
    private fun labels(q: String) = index.search(q).map { it.item.label }

    @Test fun exactPrefix() {
        assertEquals("Telegram", top("teleg"))
        assertEquals("Spotify", top("sp"))
    }

    @Test fun threeRightFourthWrongStillOpensTheApp() {
        assertEquals("Telegram", top("telrg")) // r next to e
        assertEquals("Spotify", top("spox"))
        assertEquals("Discord", top("disv"))
        assertEquals("WhatsApp", top("whar"))
        assertEquals("Госуслуги", top("госк"))
        assertEquals("Wildberries", top("wilf"))
    }

    @Test fun typoLowersScoreButKeepsTheApp() {
        val exact = index.search("telegram").first { it.item.label == "Telegram" }.score
        val typo = index.search("telegrsm").first { it.item.label == "Telegram" }.score
        assertTrue(typo < exact)
        assertEquals("Telegram", top("telegrsm"))
        assertEquals("Telegram", top("tleegram")) // transposition
        assertEquals("Telegram", top("telgram")) // missing letter
    }

    @Test fun wrongLayout() {
        assertEquals("Telegram", top("ntktuhfv"))
        assertEquals("YouTube", top("нщгегиу"))
        assertEquals("Госуслуги", top("ujcecke"))
        assertEquals("Яндекс Карты", top("zyltrc rfhns"))
    }

    @Test fun transliterationBothWays() {
        assertEquals("Telegram", top("телеграм"))
        assertEquals("Госуслуги", top("gosuslugi"))
        assertEquals("Сбербанк Онлайн", top("sberbank"))
        assertEquals("YouTube", top("ютуб"))
        assertEquals("WhatsApp", top("вотсап"))
        assertEquals("Jira", top("джира"))
        assertEquals("Контакты", top("kontakty"))
    }

    @Test fun initials() {
        assertEquals("YouTube Music", top("ytm"))
        assertEquals("Google Maps", top("gm").let { labels("gm").first { l -> l == "Google Maps" } })
        assertEquals("Яндекс Музыка", top("ям"))
        assertEquals("Keep Notes", top("kn"))
    }

    @Test fun shortcuts() {
        assertEquals("Мама", top("мама"))
        assertEquals("Мама", top("vfvf")) // layout-swapped shortcut
        assertEquals("Take a selfie", top("selfie"))
        assertEquals("Мама", top("tele мам"))
        val hit = index.search("tele мам").first()
        assertEquals(listOf(0..2), hit.highlights) // only the shortcut's own label is highlighted
    }

    @Test fun appsRankAboveShortcutsOnEqualMatch() {
        val l = labels("tele")
        assertTrue(l.indexOf("Telegram") < l.indexOf("Мама").let { if (it < 0) Int.MAX_VALUE else it })
    }

    @Test fun widgets() {
        val idx = SearchIndex(
            listOf(
                SearchItem("app/spotify", "Spotify"),
                SearchItem("w/spotify", "Now playing", ItemKind.Widget, parentLabel = "Spotify"),
                SearchItem("w/clock", "Часы", ItemKind.Widget, parentLabel = "Clock"),
            ),
        )
        // By the widget's own name, typo and wrong layout included.
        assertEquals("Now playing", idx.search("now pla").first().item.label)
        assertEquals("Часы", idx.search("xfcs").first().item.label)
        // By the app's name: below the app itself, with nothing highlighted in the widget's name.
        assertEquals(listOf("Spotify", "Now playing"), idx.search("spot").map { it.item.label })
        assertEquals(emptyList(), idx.search("spot")[1].highlights)
    }

    @Test fun wrongLayoutDoesNotAddNoise() {
        val idx = SearchIndex(listOf(SearchItem("a", "YouTube Music"), SearchItem("b", "New conversation", ItemKind.Shortcut, "Messages")))
        assertEquals(listOf("YouTube Music"), idx.search("ytm").map { it.item.label })
    }

    @Test fun emptyAndGarbageQueriesFindNothing() {
        assertEquals(emptyList(), index.search(""))
        assertEquals(emptyList(), index.search("   "))
        assertEquals(emptyList(), index.search("qqqqzzzz"))
        assertEquals(emptyList(), index.search("!!!"))
    }

    @Test fun pureFunctionOfQuery() {
        val queries = listOf("t", "te", "tel", "tele", "teleg", "ytm", "ntk", "гос", "ка")
        val first = queries.associateWith { index.search(it) }
        // interleave in a different order, several times, including "erase a letter" sequences
        repeat(3) {
            for (q in queries.reversed()) assertEquals(first[q], index.search(q))
        }
        val typed = "telegram"
        val forward = (1..typed.length).map { index.search(typed.take(it)) }
        val backward = (typed.length downTo 1).map { index.search(typed.take(it)) }.reversed()
        assertEquals(forward, backward)
    }

    @Test fun orderDoesNotDependOnInputOrder() {
        val shuffled = SearchIndex((apps + shortcuts).shuffled(java.util.Random(42)))
        for (q in listOf("t", "ca", "ya", "g", "ytm", "kontakt")) {
            assertEquals(index.search(q), shuffled.search(q), "query $q")
        }
    }

    @Test fun highlights() {
        val yt = index.search("ytm").first()
        assertEquals("YouTube Music", yt.item.label)
        assertEquals(listOf(0..0, 3..3, 8..8), yt.highlights)
        val tg = index.search("ntktuhfv").first()
        assertEquals(listOf(0..7), tg.highlights)
    }

    @Test fun wordSplitting() {
        assertEquals(listOf("You", "Tube", "Music"), splitWords("YouTube Music").map { "YouTube Music".substring(it) })
        assertEquals(listOf("HTML", "Parser"), splitWords("HTMLParser").map { "HTMLParser".substring(it) })
        assertEquals(listOf("Tele", "2"), splitWords("Tele2").map { "Tele2".substring(it) })
    }

    @Test fun aliases() {
        val idx = SearchIndex(apps + SearchItem("app/Сбербанк Онлайн!", "Сбербанк Онлайн", aliases = listOf("бабки", "money")))
        val hit = idx.search("бабки").first()
        assertEquals("Сбербанк Онлайн", hit.item.label)
        assertTrue(hit.highlights.isEmpty())
        assertEquals("Сбербанк Онлайн", idx.search(",f,rb").first().item.label) // wrong layout
        assertEquals("Сбербанк Онлайн", idx.search("babki").first().item.label) // transliterated
        assertEquals("Сбербанк Онлайн", idx.search("mon").first().item.label)
    }
}
