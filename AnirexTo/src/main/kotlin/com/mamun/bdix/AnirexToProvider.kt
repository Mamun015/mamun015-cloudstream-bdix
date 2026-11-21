package com.mamun.bdix

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * AnirexTo — Anime from anirex.to with sub/dub via embed iframes.
 *
 * API:
 *   GET /api/v1/homepage                 -> homepage feed
 *   GET /api/anime/{slug}               -> anime info
 *   GET /api/anime/{slug}/episodes      -> episode list
 *   GET /api/search?q=...               -> search
 *   GET /api/v1/watch/{anilist_id}/{ep} -> video sources (iframe embeds)
 */
class AnirexToProvider : MainAPI() {
    override var mainUrl = "https://anirex.to"
    override var name = "AnirexTo"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.ONA,
        TvType.Special,
        TvType.Others,
    )

    override val mainPage: List<MainPageData>
        get() = mainPageOf(
            "featured" to "Featured",
            "latest" to "Latest Episodes",
            "trending" to "Trending",
            "new" to "New On Site",
            "upcoming" to "Upcoming",
        )

    // --- Homepage ------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val body = fetchText("$apiUrl/v1/homepage?featuredLimit=5&latestLimit=14&trendingLimit=24&newLimit=14&upcomingLimit=14")
        val wrapper = mapper.readValue(body, HomepageWrapper::class.java)
        val items = when (request.data) {
            "featured" -> wrapper.data.featured
            "latest" -> wrapper.data.latestEpisodes
            "trending" -> wrapper.data.trending
            "new" -> wrapper.data.newOnSite
            "upcoming" -> wrapper.data.upcoming
            else -> null
        }
        val parsed = items?.mapNotNull { it.toSearch() } ?: emptyList()
        return newHomePageResponse(request.name, parsed, false)
    }

    // --- Search --------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val body = fetchText("$apiUrl/search?q=${encode(term)}&sort=popularity&page=1&per_page=28")
        val wrapper = mapper.readValue(body, AnimeListWrapper::class.java)
        return wrapper.data.mapNotNull { it.toSearch() }
    }

    // --- Load (Detail) -------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromUrl(url) ?: return null
        val infoBody = fetchText("$apiUrl/anime/$slug")
        val infoWrapper = mapper.readValue(infoBody, AnimeSingleWrapper::class.java)
        val anime = infoWrapper.data ?: return null

        val title = anime.titleEnglish ?: anime.titleRomaji ?: return null
        val epBody = fetchText("$apiUrl/anime/$slug/episodes?per_page=2500")
        val epWrapper = mapper.readValue(epBody, EpisodeListWrapper::class.java)

        val poster = anime.coverImageUrl
        val plot = anime.description?.takeIf { it.isNotBlank() }
        val score = anime.averageScore?.let { Score.from10(it / 10.0) }
        val year = anime.seasonYear
        val genres = anime.genres ?: emptyList()
        val type = anime.format.toAnirexTvType() ?: TvType.Anime
        val status = anime.status.toAnirexStatus()

        if (epWrapper.data.isNotEmpty()) {
            val episodes = epWrapper.data.map { ep ->
                newEpisode("$slug/${ep.episodeNumber}") {
                    this.name = ep.title ?: "Episode ${ep.episodeNumber}"
                    this.season = 1
                    this.episode = ep.episodeNumber
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = genres
                this.status = status
            }
        }

        return newMovieLoadResponse(title, url, type, slug) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = genres
            this.status = status
        }
    }

    // --- Links ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("/")
        if (parts.size != 2) return false
        val slug = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false

        val infoBody = fetchText("$apiUrl/anime/$slug")
        val infoWrapper = mapper.readValue(infoBody, AnimeSingleWrapper::class.java)
        val anime = infoWrapper.data ?: return false
        val anilistId = anime.anilistId ?: anime.id ?: return false

        val watchBody = fetchText("$apiUrl/v1/watch/$anilistId/$epNum")
        val watch = mapper.readValue(watchBody, WatchResponse::class.java)
        val servers = watch.servers ?: return false

        val referer = "$mainUrl/watch/$slug/$epNum"
        var count = 0
        for (server in servers) {
            val playerUrl = server.playerUrl ?: continue
            val audio = server.audio?.lowercase() ?: ""
            val label = when {
                audio in listOf("dub", "english") -> "Anirex [DUB]"
                else -> "Anirex [SUB]"
            }
            callback(
                newExtractorLink(name, label, playerUrl) {
                    this.type = ExtractorLinkType.FRAME
                    this.referer = referer
                    this.quality = 720
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                    )
                }
            )
            count++
        }
        return count > 0
    }

    // --- Helpers -------------------------------------------------------------

    private fun slugFromUrl(url: String): String? {
        val trimmed = url.trimEnd('/')
        val parts = trimmed.split("/")
        return parts.lastOrNull { it.isNotBlank() && it.matches(Regex("^[a-z][a-z0-9-]+\\d+$")) }
    }

    private suspend fun fetchText(url: String): String =
        app.get(url, headers = HEADERS, timeout = TIMEOUT).text

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun SearchAnime.toSearch(): SearchResponse? {
        val title = this.titleEnglish ?: this.titleRomaji ?: return null
        val slug = this.slug ?: return null
        val poster = this.coverImageUrl
        val type = this.format.toAnirexTvType() ?: TvType.Anime
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", type) {
            this.posterUrl = poster
            this.year = this@toSearch.seasonYear
            this.score = this@toSearch.averageScore?.let { Score.from10(it / 10.0) }
        }
    }

    private companion object {
        const val TIMEOUT = 20L
        const val apiUrl = "https://anirex.to/api"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
        val mapper = ObjectMapper()
    }
}

// --- JSON wrapper classes (simple, not in models file to avoid clashes) -------

data class HomepageWrapper(val data: HomepageData)
data class AnimeSingleWrapper(val data: AnimeDTO?)
data class AnimeListWrapper(val data: List<SearchAnime>)
data class EpisodeListWrapper(val data: List<EpisodeDTO>)