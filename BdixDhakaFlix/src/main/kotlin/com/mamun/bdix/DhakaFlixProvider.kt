package com.mamun.bdix

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * DhakaFlix, BDIX file servers running h5ai.
 *
 * Listing is done by reading the ordinary h5ai HTML page (the same page the browser
 * shows) instead of the JSON items call: one request per folder, every entry with a
 * real name, and season folders are visible. Search still uses h5ai's JSON search
 * because it walks the whole share tree.
 *
 * Posters come from TMDb (the file server has no artwork), falling back to an image
 * file inside the folder when TMDb has no match.
 */
enum class DhakaKind { MOVIES, SERIES, ANIME }

data class DhakaScope(val host: String, val path: String)

data class DhakaHome(val host: String, val path: String, val label: String)

private data class DhakaRow(val name: String, val url: String, val folder: Boolean)

private data class TmdbSearchResult(val results: List<TmdbItem>?)

private data class TmdbItem(
    val id: Int? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
)

abstract class DhakaFlixProvider(private val kind: DhakaKind) : MainAPI() {

    override var mainUrl = "http://172.16.50.14"
    override var name = "(BDIX) DhakaFlix"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val instantLinkLoading = true

    override val supportedTypes = when (kind) {
        DhakaKind.MOVIES -> setOf(TvType.Movie, TvType.AnimeMovie)
        DhakaKind.SERIES -> setOf(TvType.TvSeries, TvType.Anime, TvType.Documentary, TvType.Others)
        DhakaKind.ANIME -> setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie)
    }

    /** Folders walked for search. */
    protected abstract val scopes: List<DhakaScope>

    /** Category paths shown on the home page; each one is opened and listed. */
    protected abstract val homes: List<DhakaHome>

    /**
     * Must stay lazy: Cloudstream reads this after the constructor has run. Reading
     * the subclass properties (scopes/homes) during construction would crash the
     * plugin with a null value, which is what made the extension fail to load.
     */
    override val mainPage: List<MainPageData>
        get() = mainPageOf(
            *homes.mapIndexed { index, home -> index.toString() to home.label }.toTypedArray()
        )

    // --- Home ----------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val index = request.data.toIntOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val home = homes.getOrNull(index) ?: return newHomePageResponse(request.name, emptyList(), false)
        val rows = fetchRows(home.host, home.path)
        if (rows.isEmpty()) return newHomePageResponse(request.name, emptyList(), false)

        // Some categories are split into "★ 0—9", "♥ A—L" … buckets. Open those
        // one level so the row shows real titles instead of bucket names.
        val source = openBuckets(rows)

        val start = ((page - 1).coerceAtLeast(0)) * PAGE_SIZE
        val slice = source.drop(start).take(PAGE_SIZE)

        val items = coroutineScope {
            slice.map { row -> async { rowToSearchResponse(row, home.host) } }.awaitAll()
        }.filterNotNull()

        return newHomePageResponse(request.name, items, source.size > start + PAGE_SIZE)
    }

    private suspend fun openBuckets(rows: List<DhakaRow>): List<DhakaRow> {
        val buckets = rows.filter { it.folder && isBucket(it.name) }
        if (buckets.isEmpty() || buckets.size * 2 < rows.size) return rows
        val inner = coroutineScope {
            buckets.take(MAX_BUCKETS).map { bucket -> async { fetchRows(bucket.url) } }.awaitAll()
        }.flatten()
        return inner.ifEmpty { rows }
    }

    private fun isBucket(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (n.any { it in "\u2605\u2606\u2665\u2661\u2666\u2660\u2663" }) return true
        return Regex("^[A-Za-z0-9]{1,3}\\s*[-\u2013\u2014/]\\s*[A-Za-z0-9]{1,3}$").matches(n)
    }

    private suspend fun rowToSearchResponse(row: DhakaRow, host: String): SearchResponse? {
        val title = cleanTitle(row.name)
        if (title.isBlank()) return null
        val isSeries = row.folder && looksLikeSeries(row.url)
        val type = when {
            isSeries -> TvType.TvSeries
            kind == DhakaKind.MOVIES -> TvType.Movie
            kind == DhakaKind.ANIME -> if (row.folder) TvType.Anime else TvType.AnimeMovie
            else -> TvType.TvSeries
        }
        val poster = posterFor(title, isSeries, row.url, host)
        return makeSearchResponse(title, row.url, type, poster)
    }

    // --- Search --------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val pattern = buildPattern(term)

        val hits: List<Pair<String, String>> = coroutineScope {
            scopes.map { scope -> async { searchScope(scope, pattern) } }.awaitAll()
        }.flatten()

        val seenUrls = mutableSetOf<String>()
        val seenTitles = mutableSetOf<String>()
        val unique = mutableListOf<Pair<String, String>>()
        for ((title, url) in hits) {
            if (!seenUrls.add(url.trimEnd('/'))) continue
            if (!seenTitles.add(title.lowercase(Locale.ROOT))) continue
            unique.add(title to url)
            if (unique.size >= MAX_SEARCH_RESULTS) break
        }

        return coroutineScope {
            unique.map { (title, url) ->
                async {
                    val isSeries = looksLikeSeries(url)
                    val type = when {
                        isSeries -> TvType.TvSeries
                        kind == DhakaKind.MOVIES -> TvType.Movie
                        kind == DhakaKind.ANIME -> TvType.AnimeMovie
                        else -> TvType.TvSeries
                    }
                    // No TMDb call here on purpose: 30 lookups made search time out.
                    makeSearchResponse(title, url, type, null)
                }
            }.awaitAll()
        }.filterNotNull()
    }

    private suspend fun searchScope(scope: DhakaScope, pattern: String): List<Pair<String, String>> {
        return try {
            val body = MAPPER.writeValueAsString(
                mapOf(
                    "action" to "get",
                    "search" to mapOf("href" to scope.path, "pattern" to pattern, "ignorecase" to true),
                )
            )
            val text = app.post(
                scope.host + scope.path,
                requestBody = body.toRequestBody(JSON_MEDIA),
                timeout = TIMEOUT,
            ).text

            val root = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return emptyList()
            val hits = root.get("search")?.takeIf { it.isArray } ?: return emptyList()

            val scopeKey = scope.path.trimEnd('/')
            val out = mutableListOf<Pair<String, String>>()
            for (node in hits) {
                val href = node.get("href")?.asText()?.trim().orEmpty()
                if (href.isEmpty() || !href.endsWith("/")) continue
                if (!isFolderNode(node)) continue
                if (href.trimEnd('/') == scopeKey) continue
                val title = cleanTitle(href.trimEnd('/').substringAfterLast('/'))
                if (title.isBlank()) continue
                out.add(title to (scope.host + href))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun makeSearchResponse(title: String, url: String, type: TvType, poster: String?): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) { this.posterUrl = poster }
            TvType.Anime -> newAnimeSearchResponse(title, url, type) { this.posterUrl = poster }
            TvType.AnimeMovie -> newAnimeSearchResponse(title, url, type) { this.posterUrl = poster }
            else -> newMovieSearchResponse(title, url, type) { this.posterUrl = poster }
        }
    }

    // --- Load ----------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val target = url.trim()
        if (target.isEmpty()) return null
        return try {
            if (isVideoUrl(target)) {
                return makeMovie(cleanTitle(target.substringAfterLast('/')), target, target, null)
            }

            val rows = fetchRows(target)
            if (rows.isEmpty()) return null

            val title = cleanTitle(target.trimEnd('/').substringAfterLast('/'))
            val videos = rows.filter { !it.folder && isVideoName(it.name) }
            val folders = rows.filter { it.folder }
            val seasonFolders = folders.filter { isSeasonName(it.name) }
            val series = looksLikeSeries(target) || kind == DhakaKind.SERIES

            val localPoster = rows.firstOrNull { !it.folder && isImageName(it.name) }?.url
            val poster = posterFor(title, series, target, null) ?: localPoster

            // Episodes directly inside the folder.
            if (videos.isNotEmpty() && (series || seasonFolders.isNotEmpty())) {
                val episodes = videos
                    .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }
                    .mapIndexed { index, video ->
                        newEpisode(video.url) {
                            this.name = episodeTitle(video.name)
                            this.episode = episodeNumber(video.name) ?: (index + 1)
                        }
                    }
                return seriesResponse(title, target, episodes, poster)
            }

            if (videos.size == 1) {
                return makeMovie(title, target, videos.first().url, poster)
            }
            if (videos.size > 1 && !series) {
                // A collection folder: every file is one movie, not an episode.
                val episodes = videos.map { video ->
                    newEpisode(video.url) { this.name = cleanTitle(video.name) }
                }
                return seriesResponse(title, target, episodes, poster)
            }

            // Season folders directly inside.
            if (seasonFolders.isNotEmpty()) {
                val episodes = mutableListOf<Episode>()
                seasonFolders.forEachIndexed { index, folder ->
                    val season = seasonNumber(folder.name) ?: (index + 1)
                    val inner = fetchRows(folder.url)
                        .filter { !it.folder && isVideoName(it.name) }
                        .sortedBy { episodeNumber(it.name) ?: Int.MAX_VALUE }
                    inner.forEachIndexed { position, video ->
                        episodes.add(
                            newEpisode(video.url) {
                                this.name = episodeTitle(video.name)
                                this.season = season
                                this.episode = episodeNumber(video.name) ?: (position + 1)
                            }
                        )
                    }
                }
                if (episodes.isNotEmpty()) return seriesResponse(title, target, episodes, poster)
            }

            // A container folder (year, category, collection): open each child folder
            // and use its first playable file so the title does not dead-end.
            val container = coroutineScope {
                folders.take(MAX_CONTAINER_FOLDERS).map { folder ->
                    async {
                        val video = fetchRows(folder.url)
                            .firstOrNull { !it.folder && isVideoName(it.name) }
                        video?.let { folder.name to it.url }
                    }
                }.awaitAll()
            }.filterNotNull()

            if (container.isNotEmpty()) {
                val episodes = container.map { (folderName, videoUrl) ->
                    newEpisode(videoUrl) { this.name = cleanTitle(folderName) }
                }
                return seriesResponse(title, target, episodes, poster)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun seriesResponse(
        title: String,
        url: String,
        episodes: List<Episode>,
        poster: String?,
    ): LoadResponse = newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
    }

    private suspend fun makeMovie(title: String, url: String, data: String, poster: String?): LoadResponse {
        val type = when (kind) {
            DhakaKind.MOVIES -> TvType.Movie
            DhakaKind.ANIME -> TvType.AnimeMovie
            DhakaKind.SERIES -> TvType.Movie
        }
        return newMovieLoadResponse(title, url, type, data) {
            this.posterUrl = poster
        }
    }

    // --- Links ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val link = data.trim()
        if (link.isEmpty()) return false
        if (!isVideoUrl(link)) return false
        return try {
            callback.invoke(
                newExtractorLink(source = this.name, name = this.name, url = link) {
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept-Encoding" to "identity",
                    )
                    this.referer = originOf(link) + "/"
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- h5ai HTML -----------------------------------------------------------

    private suspend fun fetchRows(url: String): List<DhakaRow> {
        return try {
            fetchRows(app.get(url, timeout = TIMEOUT).document, originOf(url))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchRows(host: String, path: String): List<DhakaRow> {
        return try {
            fetchRows(app.get(host + path, timeout = TIMEOUT).document, host)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchRows(doc: Document, host: String): List<DhakaRow> {
        val out = mutableListOf<DhakaRow>()
        for (row in doc.select("tbody > tr")) {
            val link = row.selectFirst("td.fb-n > a[href]") ?: continue
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("#")) continue
            val label = link.text().trim()
            val folder = row.selectFirst("td.fb-i > img")?.attr("alt")?.equals("folder", true) == true
                || href.endsWith("/")
            if (!folder && !isVideoName(href) && !isImageName(href)) continue
            val full = if (href.startsWith("http", true)) href else host + href
            if (label.isBlank() && !folder) continue
            out.add(DhakaRow(label, full, folder))
        }
        return out
    }

    private fun isFolderNode(node: com.fasterxml.jackson.databind.JsonNode): Boolean {
        val size = node.get("size") ?: return node.get("href")?.asText()?.endsWith("/") == true
        return size.isNull && node.get("href")?.asText()?.endsWith("/") == true
    }

    // --- Posters -------------------------------------------------------------

    private suspend fun posterFor(
        title: String,
        isSeries: Boolean,
        folderUrl: String,
        host: String?,
    ): String? {
        val key = folderUrl.trimEnd('/')
        synchronized(posterCache) {
            if (posterCache.containsKey(key)) return posterCache[key]
        }
        var found = tmdbPoster(title, isSeries)
        if (found == null && host != null) {
            found = try {
                fetchRows(folderUrl).firstOrNull { !it.folder && isImageName(it.name) }?.url
            } catch (e: Exception) {
                null
            }
        }
        synchronized(posterCache) { posterCache[key] = found }
        return found
    }

    private suspend fun tmdbPoster(title: String, isSeries: Boolean): String? {
        val query = cleanTitle(title)
        if (query.isBlank()) return null
        val key = "tmdb:" + (if (isSeries) "tv:" else "movie:") + query.lowercase(Locale.ROOT)
        synchronized(posterCache) {
            if (posterCache.containsKey(key)) return posterCache[key]
        }
        val found = try {
            val type = if (isSeries) "tv" else "movie"
            val result = app.get(
                "https://api.themoviedb.org/3/search/$type",
                params = mapOf("api_key" to TMDB_KEY, "query" to query),
                timeout = 12L,
            ).parsed<TmdbSearchResult>()
            result.results?.firstOrNull()?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch (e: Exception) {
            null
        }
        synchronized(posterCache) { posterCache[key] = found }
        return found
    }

    private val posterCache = HashMap<String, String?>()

    // --- Naming helpers ------------------------------------------------------

    private fun looksLikeSeries(url: String): Boolean {
        val decoded = runCatching {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        }.getOrDefault(url).lowercase(Locale.ROOT)
        return SERIES_KEYWORDS.any { decoded.contains(it) }
    }

    private fun cleanTitle(raw: String): String {
        var n = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
        if (n.contains("/")) n = n.trimEnd('/').substringAfterLast('/')
        n = n.replace(Regex("\\.($VIDEO_EXT|$IMAGE_EXT)$", RegexOption.IGNORE_CASE), "")
        n = n.replace(Regex("^\\d{1,3}\\.\\s*"), " ")
        n = n.replace(Regex("\\[[^]]*]"), " ")
        n = n.replace(Regex("\\([^)]*\\d{4}[^)]*\\)"), " ")
        n = n.replace(Regex("\\([^)]*[A-Za-z]{4,}[^)]*\\)"), " ")
        repeat(3) { n = n.replace(TAGS, " ") }
        n = n.replace('_', ' ')
        n = n.replace(Regex("\\s{2,}"), " ").trim()
        return n.trim('-', '.', ':', '\u2013', '\u2014').trim()
    }

    private fun episodeTitle(raw: String): String {
        var n = cleanTitle(raw)
        n = n.replace(Regex("[Ss]\\d{1,2}[\\s._-]?[Ee][Pp]?\\d{1,4}\\b"), " ")
        n = n.replace(Regex("\\b\\d{1,2}x\\d{2,3}\\b"), " ")
        n = n.replace(
            Regex("(^|[\\s._\\-\\[()])[Ee]p?(?:isode)?[\\s._-]*\\d{1,4}\\b", RegexOption.IGNORE_CASE),
            "$1",
        )
        n = n.replace(Regex("\\s{2,}"), " ").trim().trim('-', '.', '_', ':').trim()
        return n.ifBlank { cleanTitle(raw) }
    }

    private fun isSeasonName(name: String): Boolean {
        val n = cleanTitle(name).lowercase(Locale.ROOT)
        return n.startsWith("season") ||
            Regex("^s\\d{1,2}(\\b|$)").containsMatchIn(n) ||
            n.startsWith("complete") || n.startsWith("special") ||
            n.startsWith("extra") || n.startsWith("bonus")
    }

    private fun seasonNumber(name: String): Int? {
        Regex("Season[\\s._-]*(\\d{1,2})", RegexOption.IGNORE_CASE).find(name)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("^[Ss](\\d{1,2})$").find(name.trim())?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun episodeNumber(name: String): Int? {
        Regex("[Ss](\\d{1,2})[\\s._-]?[Ee][Pp]?(\\d{1,3})").find(name)?.let {
            return it.groupValues[2].toIntOrNull()
        }
        Regex("\\b(\\d{1,2})x(\\d{2,3})\\b").find(name)?.let {
            return it.groupValues[2].toIntOrNull()
        }
        Regex("(?:^|[\\s._\\-\\[])[Ee]p?(?:isode)?[\\s._-]*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
            .find(name)?.let {
                return it.groupValues[1].toIntOrNull()
            }
        return null
    }

    companion object {
        private const val TIMEOUT = 15L
        private const val PAGE_SIZE = 24
        private const val MAX_SEARCH_RESULTS = 30
        private const val MAX_CONTAINER_FOLDERS = 20
        private const val MAX_BUCKETS = 8
        private const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private val MAPPER = ObjectMapper()
        private val JSON_MEDIA = "application/json".toMediaType()

        private val SERIES_KEYWORDS = listOf(
            "tv series", "tv-series", "tv-web-series", "web series", "web-series",
            "anime & cartoon tv series", "korean tv", "documentary", "wrestling",
        )

        private val STOPWORDS = setOf(
            "the", "and", "of", "a", "an", "to", "in", "is", "it",
            "for", "on", "at", "be", "no", "vs",
        )

        private const val VIDEO_EXT = "mkv|mp4|m4v|avi|webm|mov|ts|m2ts|flv|wmv"
        private const val IMAGE_EXT = "jpg|jpeg|png|webp"

        fun originOf(url: String): String =
            Regex("^https?://[^/]+").find(url)?.value ?: ""

        fun isVideoName(nameOrUrl: String): Boolean {
            val n = nameOrUrl.substringBefore('?').lowercase(Locale.ROOT)
            return Regex("\\.($VIDEO_EXT)$").containsMatchIn(n)
        }

        fun isVideoUrl(url: String): Boolean = isVideoName(url.substringBefore('?'))

        fun isImageName(name: String): Boolean {
            val n = name.substringBefore('?').lowercase(Locale.ROOT)
            return Regex("\\.($IMAGE_EXT)$").containsMatchIn(n)
        }

        /** h5ai regex: significant words OR-joined, e.g. "spider|man|way". */
        fun buildPattern(query: String): String {
            val words = query
                .split(Regex("[^A-Za-z0-9\\u0980-\\u09FF]+"))
                .filter { it.length >= 3 }
                .filter { !STOPWORDS.contains(it.lowercase(Locale.ROOT)) }

            val unique = mutableListOf<String>()
            for (word in words) {
                if (unique.size >= 3) break
                val low = word.lowercase(Locale.ROOT)
                if (unique.any { it.lowercase(Locale.ROOT) == low }) continue
                unique.add(word)
            }

            return if (unique.isEmpty()) {
                regexEscape(query.trim())
            } else {
                unique.joinToString("|") { regexEscape(it) }
            }
        }

        fun regexEscape(value: String): String {
            val sb = StringBuilder()
            for (c in value) {
                if ("\\^$.|?*+()[]{}".contains(c)) sb.append('\\')
                sb.append(c)
            }
            return sb.toString()
        }

        private val TAGS = Regex(
            "(?i)(^|[\\s._\\-])(" +
                "2160p|1080p|1080i|720p|576p|540p|480p|360p|4k|uhd|x264|x265|h264|h265|hevc|avc|" +
                "xvid|divx|aac|ac3|eac3|dts|ddp|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|" +
                "hdrip|dvdrip|dvdscr|hdtv|pdtv|amzn|nf|hmax|dsnp|atvp|zs|esub|esubs|msub|msubs|" +
                "multi|proper|repack|remux|extended|unrated|10bit|8bit" +
                ")(?=$|[\\s._\\-])"
        )
    }
}

class DhakaFlixMoviesProvider : DhakaFlixProvider(DhakaKind.MOVIES) {
    override var name = "(BDIX) DhakaFlix Movies"

    override val scopes = listOf(
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/English%20Movies%20%281080p%29/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/Hindi%20Movies/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/"),
        DhakaScope("http://172.16.50.7", "/DHAKA-FLIX-7/English%20Movies/"),
        DhakaScope("http://172.16.50.7", "/DHAKA-FLIX-7/Foreign%20Language%20Movies/"),
        DhakaScope("http://172.16.50.7", "/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"),
    )

    override val homes = listOf(
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/English%20Movies%20%281080p%29/", "English Movies"),
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/Hindi%20Movies/", "Hindi Movies"),
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/", "IMDb Top 250"),
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/", "South Indian"),
        DhakaHome("http://172.16.50.7", "/DHAKA-FLIX-7/English%20Movies/", "English Movies 7"),
        DhakaHome("http://172.16.50.7", "/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/", "Bangla Movies"),
        DhakaHome("http://172.16.50.7", "/DHAKA-FLIX-7/Foreign%20Language%20Movies/", "Foreign Movies"),
    )
}

class DhakaFlixSeriesProvider : DhakaFlixProvider(DhakaKind.SERIES) {
    override var name = "(BDIX) DhakaFlix Series"

    override val scopes = listOf(
        DhakaScope("http://172.16.50.12", "/DHAKA-FLIX-12/TV-WEB-Series/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/"),
        DhakaScope("http://172.16.50.10", "/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/"),
        DhakaScope("http://172.16.50.10", "/DHAKA-FLIX-10/Awards%20%26%20TV%20Shows/"),
        DhakaScope("http://172.16.50.10", "/DHAKA-FLIX-10/WWE%20%26%20AEW%20Wrestling/"),
        DhakaScope("http://172.16.50.10", "/DHAKA-FLIX-10/Documentary/"),
    )

    override val homes = listOf(
        DhakaHome("http://172.16.50.12", "/DHAKA-FLIX-12/TV-WEB-Series/", "TV & Web Series"),
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/", "Korean Series"),
        DhakaHome("http://172.16.50.10", "/DHAKA-FLIX-10/Awards%20%26%20TV%20Shows/", "TV Shows"),
        DhakaHome("http://172.16.50.10", "/DHAKA-FLIX-10/Documentary/", "Documentary"),
        DhakaHome("http://172.16.50.10", "/DHAKA-FLIX-10/WWE%20%26%20AEW%20Wrestling/", "Wrestling"),
    )
}

class DhakaFlixAnimeProvider : DhakaFlixProvider(DhakaKind.ANIME) {
    override var name = "(BDIX) DhakaFlix Anime"

    override val scopes = listOf(
        DhakaScope("http://172.16.50.10", "/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/Animation%20Movies/"),
        DhakaScope("http://172.16.50.14", "/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/"),
    )

    override val homes = listOf(
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/", "Animation Movies"),
        DhakaHome("http://172.16.50.14", "/DHAKA-FLIX-14/Animation%20Movies/", "Animation Movies SD"),
        DhakaHome("http://172.16.50.10", "/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/", "Anime & Cartoon TV"),
    )
}
