package com.mamun.bdix

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Circle FTP, a BDIX media server.
 *
 * The site is a React app and everything useful is behind one JSON API:
 *   GET /api/posts?searchTerm=<q>&order=desc      -> { posts: [ ... ] }
 *   GET /api/posts?categoryExact=<id>&page=<n>    -> { posts: [ ... ] }
 *   GET /api/posts/<id>                           -> one post
 *
 * A post is either
 *   type = singleVideo, content = "<direct media url>"
 *   type = series,      content = [{ seasonName, episodes: [{ link, title }] }]
 *
 * The media files themselves are plain HTTP on ftpN.circleftp.net / indexN.circleftp.net,
 * so loadLinks hands the URL straight to the player.
 *
 * Only reachable from a BDIX-connected line. Every network failure degrades to an
 * empty list or null instead of an exception, so the app never reports a crash.
 */
class CircleFtpProvider : MainAPI() {
    override var mainUrl = "http://new.circleftp.net"
    override var name = "(BDIX) Circle FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.Others,
    )

    override val mainPage = mainPageOf(
        "80" to "Featured",
        "6" to "English Movies",
        "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series",
        "2" to "Hindi Movies",
        "5" to "Hindi TV Series",
        "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies",
        "3" to "South Indian Dubbed Movies",
        "4" to "South Indian Movies",
        "1" to "Animation Movies",
        "21" to "Anime Series",
        "85" to "Documentary",
        "15" to "WWE",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val posts = fetchPosts(
            "/api/posts?categoryExact=${request.data}&page=$page&order=desc&limit=10"
        )
        val items = posts.mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val posts = fetchPosts("/api/posts?searchTerm=${encode(term)}&order=desc", cacheTime = 0)
        return posts.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = idFromUrl(url) ?: return null
        val text = runCatching { apiText("/api/posts/$id") }.getOrNull() ?: return null
        val post = runCatching { AppUtils.parseJson<Post>(text) }.getOrNull() ?: return null

        val rawTitle = post.title?.takeIf { it.isNotBlank() } ?: return null
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val posterUrl = poster(post.imageSm ?: post.image)
        val yearValue = firstInt(post.year)
        val plot = post.metaData?.takeIf { it.isNotBlank() }

        // content is a JSON string for a movie and a JSON array for a series,
        // so the raw tree is easier here than a fixed data class.
        val content = runCatching { MAPPER.readTree(text).get("content") }.getOrNull()
        val movieUrl = content?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        if (post.type != TYPE_SERIES && movieUrl != null) {
            return newMovieLoadResponse(title, url, TvType.Movie, movieUrl) {
                this.posterUrl = posterUrl
                this.year = yearValue
                this.plot = plot
            }
        }

        val episodes = mutableListOf<Episode>()
        var seasonIndex = 0
        content?.takeIf { it.isArray }?.forEach { season ->
            seasonIndex++
            val seasonNumber = firstInt(season.get("seasonName")?.asText()) ?: seasonIndex
            var episodeIndex = 0
            season.get("episodes")?.takeIf { it.isArray }?.forEach { item ->
                val link = item.get("link")?.asText()?.takeIf { it.isNotBlank() }
                if (link != null) {
                    episodeIndex++
                    val label = item.get("title")?.asText().orEmpty()
                    val number = Regex("""[Ee][:\s]*(\d+)""").find(label)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: episodeIndex
                    episodes.add(
                        newEpisode(link) {
                            this.season = seasonNumber
                            this.episode = number
                            this.name = "Episode $number"
                        }
                    )
                }
            }
        }

        // A series tagged post that only carries one file still needs to play.
        if (episodes.isEmpty() && movieUrl != null) {
            episodes.add(
                newEpisode(movieUrl) {
                    this.season = 1
                    this.episode = 1
                }
            )
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = yearValue
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = data.trim()
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) return false
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = link,
            ) {
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept-Encoding" to "identity",
                )
            }
        )
        return true
    }

    // --- API -----------------------------------------------------------------

    /**
     * The API answers on the public name and on a raw BDIX address. Whichever
     * answered last is tried first next time, and is also used for posters.
     */
    private suspend fun apiText(path: String, cacheTime: Int = 30): String {
        val order = if (apiBase == API_FALLBACK) {
            listOf(API_FALLBACK, API_PRIMARY)
        } else {
            listOf(API_PRIMARY, API_FALLBACK)
        }
        var lastError: Exception? = null
        for (base in order) {
            try {
                val text = app.get("$base$path", cacheTime = cacheTime).text
                apiBase = base
                return text
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Circle FTP API unreachable")
    }

    private suspend fun fetchPosts(path: String, cacheTime: Int = 30): List<Post> {
        val text = runCatching { apiText(path, cacheTime) }.getOrNull() ?: return emptyList()
        val page = runCatching { AppUtils.parseJson<PostPage>(text) }.getOrNull()
        return page?.posts ?: emptyList()
    }

    private fun poster(image: String?): String? {
        val file = image?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return null
        if (file.startsWith("http://", true) || file.startsWith("https://", true)) return file
        return "${apiBase}/uploads/$file"
    }

    private fun Post.toSearchResponse(): SearchResponse? {
        val postId = id ?: return null
        val rawTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val display = cleanTitle(rawTitle).ifBlank { rawTitle }
        val displayPoster = poster(imageSm ?: image)
        val pageUrl = "$mainUrl/content/$postId"
        return if (type == TYPE_SERIES) {
            newTvSeriesSearchResponse(display, pageUrl, TvType.TvSeries) {
                this.posterUrl = displayPoster
            }
        } else {
            newMovieSearchResponse(display, pageUrl, TvType.Movie) {
                this.posterUrl = displayPoster
            }
        }
    }

    // --- Helpers -------------------------------------------------------------

    private fun idFromUrl(url: String): String? {
        val u = url.trim()
        Regex("""/content/(\d+)""").find(u)?.let { return it.groupValues[1] }
        Regex("""/api/posts/(\d+)""").find(u)?.let { return it.groupValues[1] }
        Regex("""^(\d+)$""").find(u)?.let { return it.groupValues[1] }
        return Regex("""(\d{2,})$""").find(u)?.groupValues?.get(1)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun firstInt(value: String?): Int? =
        Regex("""\d+""").find(value ?: "")?.value?.toIntOrNull()

    /** "Hope (2026) 1080p WEB-DL Dual Audio" -> "Hope". */
    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var title = raw.replace(RE_BRACKETS, " ")
        title = RE_PARENS.replace(title) { match ->
            val inner = match.groupValues[1]
            val decorative = YEAR.containsMatchIn(inner) ||
                QUALITY_TAG.containsMatchIn(inner) ||
                DECOR_WORDS.any { inner.contains(it, true) }
            if (decorative) " " else match.value
        }
        title = title.replace(QUALITY_TAG, " ")
        title = title.replace(Regex("""\s{2,}"""), " ")
        return title.trim().trim('-', '\u2013', '.', '_').trim()
    }

    // --- Models --------------------------------------------------------------

    data class PostPage(
        val posts: List<Post>? = null,
    )

    data class Post(
        val id: Int? = null,
        val title: String? = null,
        val type: String? = null,
        val image: String? = null,
        val imageSm: String? = null,
        val metaData: String? = null,
        val quality: String? = null,
        val year: String? = null,
    )

    private companion object {
        const val TYPE_SERIES = "series"
        const val API_PRIMARY = "http://new.circleftp.net:5000"
        const val API_FALLBACK = "http://15.1.1.50:5000"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        val MAPPER = ObjectMapper()
        val YEAR = Regex("""\b(19|20)\d{2}\b""")
        val RE_BRACKETS = Regex("""\[[^\]]*]""")
        val RE_PARENS = Regex("""\(([^)]*)\)""")
        val QUALITY_TAG = Regex(
            """(?i)\b(2160p|1080p|1080i|720p|576p|540p|480p|360p|4k|uhd|x264|x265|h264|h265|""" +
                """hevc|avc|aac|ac3|eac3|dts|ddp|bluray|blu-ray|bdrip|brrip|webrip|web-dl|""" +
                """webdl|hdrip|dvdrip|dvdscr|hdtv|pdtv|remux|proper|repack|10bit|8bit|""" +
                """dual audio|multi audio|dubbed|esub|esubs|msub|msubs)\b"""
        )
        val DECOR_WORDS = listOf(
            "series", "serial", "season", "complete", "dubbed", "audio", "episode",
        )

        /** Last API host that answered; used for posters too. */
        @Volatile
        var apiBase: String = API_PRIMARY
    }
}
