package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import android.util.Base64

class AnimeWorldIndia(
    final override val lang: String,
    private val language: String,
) : ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeWorld India"

    override val baseUrl = "https://watchanimeworld.net"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/page/$page/")

    override fun popularAnimeSelector() = "div.aa-cn, article.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("abs:src") ?: element.selectFirst("img")?.attr("abs:data-src")
        title = element.selectFirst("h2")?.text() ?: element.selectFirst("h3")?.text() ?: ""
    }

    override fun popularAnimeNextPageSelector() = "a.next, ul.page-numbers li:has(span.current) + li a"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/series/page/$page/")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val searchParams = AnimeWorldIndiaFilters().getSearchParams(filters)
            GET("$baseUrl/series/page/$page/$searchParams")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun getFilterList() = AnimeWorldIndiaFilters().filters

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.entry-title")?.text() ?: document.selectFirst("h1")?.text() ?: ""
        genre = document.select("a[href*='/genre/']").joinToString { it.text() }
        description = document.selectFirst("div.entry-content p")?.text() ?: document.selectFirst("div.description")?.text()
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episodios li, div.episodios-list a, article.episodes"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        val epText = element.selectFirst("div.episodiotitle")?.text() ?: link.text()
        name = if (epText.isBlank()) {
            val href = link.attr("href")
            "Episode " + href.substringBeforeLast("/").substringAfterLast("-")
        } else {
            epText
        }
        episode_number = name.substringAfter("Episode ").substringBefore(" ").toFloatOrNull() ?: 1f
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = document.select(episodeListSelector()).map { episodeFromElement(it) }
        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoListSelector() = "iframe"

    @Serializable
    data class PlayerData(
        val language: String,
        val link: String,
    )

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            val dataSrc = iframe.attr("abs:data-src")
            
            val url = if (src.isNotBlank() && !src.contains("about:blank")) src else dataSrc
            
            if (url.contains("zephyrflick.top")) {
                videos.add(Video(url, "ZephyrFlick", url))
            } else if (url.contains("player1.php?data=")) {
                val data = url.toHttpUrl().queryParameter("data")
                if (data != null) {
                    try {
                        val decoded = String(Base64.decode(data, Base64.DEFAULT))
                        val players = json.decodeFromString<List<PlayerData>>(decoded)
                        players.forEach { player ->
                            if (player.link.contains("short.icu")) {
                                val abyssUrl = player.link.replace("short.icu", "abyss.to")
                                videos.add(Video(abyssUrl, "Abyss [${player.language}]", abyssUrl))
                            } else {
                                videos.add(Video(player.link, player.language, player.link))
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore decoding errors
                    }
                }
            }
        }
        
        return videos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "240")
    }
}
