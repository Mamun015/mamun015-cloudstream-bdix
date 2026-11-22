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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * AnirexTo — anime from anirex.to with sub/dub via embed iframes.
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
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage: List<MainPageData>
        get() = mainPageOf(
            "featured" to "Featured",
            "latestEpisodes" to "Latest Episodes",
            "trending" to "Trending",
        )

    // --- Homepage ------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val rows = app.get("$apiUrl/v1/homepage", headers = HEADERS, timeout = TIMEOUT)
            .parsed<HomepageDTO>().data ?: emptyMap()
        val items = when (request.data) {
            "featured" -> rows["featured"]
            "latestEpisodes" -> rows["latestEpisodes"]
            "trending" -> rows["trending"]
            else -> null
        } ?: return newHomePageResponse(request.name, emptyList(), false)
        return newHomePageResponse(request.name, items.mapNotNull { it.toSearch() }, false)
    }

    // --- Search --------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val data = app.get(
            "$apiUrl/search?q=${encode(term)}&sort=popularity&page=1&per_page=28",
            headers = HEADERS, timeout = TIMEOUT,
        ).parsed<SearchDTO>().data
        return data.mapNotNull { it.toSearch() }
    }

    // --- Load ----------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromUrl(url) ?: return null
        val anime = app.get("$apiUrl/anime/$slug", headers = HEADERS, timeout = TIMEOUT)
            .parsed<AnimeSingleWrapper>().data ?: return null
        val title = anime.titleEnglish ?: anime.titleRomaji ?: return null

        val episodes = app.get("$apiUrl/anime/$slug/episodes?per_page=2500", headers = HEADERS, timeout = TIMEOUT)
            .parsed<EpisodeListWrapper>().data
            .mapNotNull { ep ->
                val num = ep.episodeNumber ?: return@mapNotNull null
                newEpisode("$slug/$num") {
                    this.name = ep.title ?: "Episode $num"
                    this.season = 1
                    this.episode = num
                }
            }

        val poster = anime.coverImageUrl
        val plot = anime.description?.takeIf { it.isNotBlank() }
        val score = anime.averageScore?.let { Score.from10(it / 10.0) }
        val year = anime.seasonYear
        val genres = anime.genres ?: emptyList()
        val type = anime.toTvType()

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, type, slug) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = genres
            }
        }
    }

    // --- Links ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parts = data.split("/", limit = 2)
        if (parts.size != 2) return false
        val slug = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false

        val anime = app.get("$apiUrl/anime/$slug", headers = HEADERS, timeout = TIMEOUT)
            .parsed<AnimeSingleWrapper>().data ?: return false
        val anilistId = anime.anilistId ?: anime.id ?: return false

        val watch = app.get("$apiUrl/v1/watch/$anilistId/$epNum", headers = HEADERS, timeout = TIMEOUT)
            .parsed<WatchDTO>() ?: return false
        val servers = watch.servers ?: emptyList()

        var count = 0
        for (server in servers) {
            val playerUrl = server.playerUrl ?: continue
            val resolved = FlixCloud.resolve(playerUrl) ?: continue

            resolved.subtitles.forEach { (lang, url) ->
                subtitleCallback(newSubtitleFile(lang, url))
            }

            val isHls = resolved.url.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    name,
                    "Anirex [" + (server.audio?.uppercase() ?: "SUB") + "]",
                    resolved.url,
                ) {
                    this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.referer = "https://flixcloud.cc/"
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://flixcloud.cc/",
                    )
                }
            )
            count++
        }
        return count > 0
    }


    // --- Helpers -------------------------------------------------------------

    private fun slugFromUrl(url: String): String? =
        url.trimEnd('/').split("/").lastOrNull {
            it.isNotBlank() && it.matches(Regex("^[a-z][a-z0-9-]+\\d+$"))
        }

    private fun encode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun AnimeDTO.toSearch(): SearchResponse? {
        val title = titleEnglish ?: titleRomaji ?: return null
        val slug = slug ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", toTvType()) {
            this.posterUrl = coverImageUrl
            this.year = seasonYear
            this.score = averageScore?.let { Score.from10(it / 10.0) }
        }
    }

    private fun AnimeDTO.toTvType(): TvType = when (format) {
        "TV" -> TvType.TvSeries
        "MOVIE" -> TvType.Movie
        "OVA", "SPECIAL" -> TvType.AnimeMovie
        "ONA" -> TvType.Anime
        else -> TvType.Anime
    }

    private companion object {
        const val TIMEOUT = 20L
        const val apiUrl = "https://anirex.to/api"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        val HEADERS = mapOf("User-Agent" to USER_AGENT)
    }
}

// --- JSON wrappers --------------------------------------------------------------

data class AnimeDTO(
    val id: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("anilist_id") val anilistId: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("title_romaji") val titleRomaji: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("title_english") val titleEnglish: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("cover_image_url") val coverImageUrl: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("banner_image_url") val bannerImageUrl: String? = null,
    val format: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val slug: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("average_score") val averageScore: Int? = null,
    val description: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("season_year") val seasonYear: Int? = null,
)

data class AnimeSingleWrapper(val data: AnimeDTO?)

data class HomepageDTO(
    val success: Boolean? = null,
    val data: Map<String, List<AnimeDTO>>? = null,
)

data class SearchDTO(
    val success: Boolean? = null,
    val data: List<AnimeDTO> = emptyList(),
)

data class EpisodeListWrapper(val data: List<EpisodeDTO>)

data class EpisodeDTO(
    @com.fasterxml.jackson.annotation.JsonProperty("episode_number") val episodeNumber: Int? = null,
    val title: String? = null,
)

data class WatchDTO(
    val servers: List<WatchServerDTO> = emptyList(),
)

data class WatchServerDTO(
    @com.fasterxml.jackson.annotation.JsonProperty("player_url") val playerUrl: String? = null,
    val audio: String? = null,
)