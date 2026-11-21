package com.mamun.bdix

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * MovieNestBD. Listings and detail pages are server rendered; the player is not.
 *
 * A media id on the detail page (24 hex chars) is turned into a server rendered
 * /player/<id> page by the site itself. That page holds a jiofiles/xcloud iframe.
 * The xcloud page exposes workerPools and encodedDriveId; posting the drive id to
 * /api/encode on one of those workers returns a playable HLS master playlist.
 * Worker hosts change per title, so they are always read from the page.
 */
class MovieNestBDProvider : MainAPI() {
    override var mainUrl = "https://movienestbd.best"
    override var name = "MovieNestBD"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.Others,
    )

    // mainPage must stay lazy: Cloudstream reads it after construction.
    override val mainPage: List<MainPageData>
        get() = mainPageOf(
            "latest" to "Latest Updates",
            "category/hollywood" to "Hollywood",
            "category/bollywood" to "Bollywood",
            "category/korean" to "Korean",
            "category/south-indian" to "South Indian",
            "category/foreign" to "Foreign",
            "anime" to "Anime",
            "genre/action" to "Action",
            "genre/horror" to "Horror",
            "genre/comedy" to "Comedy",
            "genre/drama" to "Drama",
            "genre/animation" to "Animation",
        )

    // --- Home / search -------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = when (request.data) {
            "latest" -> if (page <= 1) "/" else "/?page=$page"
            else -> {
                val clean = request.data.trim('/')
                val sep = if (clean.contains('?')) "&" else "?"
                "/$clean${sep}page=$page"
            }
        }
        val doc = fetch(mainUrl + path) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = doc.select("a.movie-card").mapNotNull { cardToSearch(it) }
        return newHomePageResponse(request.name, items, hasNextPage(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val doc = fetch("$mainUrl/search?q=${URLEncoder.encode(term, "UTF-8").replace("+", "%20")}")
            ?: return emptyList()
        return doc.select("a.movie-card").mapNotNull { cardToSearch(it) }
    }

    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href").trim()
        if (href.isEmpty() || href.startsWith("#")) return null
        val url = if (href.startsWith("http", true)) href else mainUrl + if (href.startsWith("/")) href else "/$href"

        val heading = card.selectFirst("h3")?.clone()
        val yearText = heading?.selectFirst("span")?.text().orEmpty()
        heading?.select("span")?.remove()
        val title = heading?.text()?.trim().orEmpty()
            .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (title.isEmpty()) return null

        val poster = card.selectFirst("img[src]")?.attr("src")?.trim()?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img[data-src]")?.attr("data-src")?.trim()
        val year = YEAR.find(yearText)?.value?.toIntOrNull()
        val type = cardType(card)

        return when (type) {
            TvType.Anime -> newAnimeSearchResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun cardType(card: Element): TvType {
        val badge = card.selectFirst("div.absolute.bottom-1\\.5.right-1\\.5")?.text()?.trim().orEmpty()
        return when {
            badge.contains("anime", true) -> TvType.Anime
            badge.contains("series", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun hasNextPage(doc: Document): Boolean =
        doc.select("a").any { it.text().trim().equals("Next", true) && it.attr("href").contains("page") }

    // --- Detail --------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null
        val title = doc.selectFirst("main h1")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val script = doc.select("script").joinToString("\n") { it.data() }
        val raw = unescape(script)

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: doc.selectFirst("div.w-48 img[src]")?.attr("src")?.trim()
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = YEAR.find(doc.select("span.bg-white\\/10").firstOrNull()?.text().orEmpty())?.value?.toIntOrNull()
        val score = Regex("""[\d.]+""").find(doc.selectFirst("#display-rating-score")?.text().orEmpty())?.value?.toDoubleOrNull()
        val genres = doc.select("a[href^=/genre/]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val languages = doc.select("a[href^=/language/]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val actors = doc.select("a[href^=/actor/]").mapNotNull {
            val n = it.text().trim()
            if (n.isEmpty()) null else ActorData(Actor(n, null))
        }
        val trailer = doc.selectFirst("iframe#trailer-iframe[data-src]")?.attr("data-src")?.trim().orEmpty()
        val trailers = if (trailer.startsWith("http", true)) {
            mutableListOf(TrailerData(extractorUrl = trailer, raw = false, referer = mainUrl))
        } else mutableListOf()

        val episodes = parseEpisodes(raw, url)
        val isSeries = episodes.isNotEmpty() || Regex("""const\s+isSeries\s*=\s*true""").containsMatchIn(raw) ||
            doc.select("span").any { it.text().trim().equals("SERIES", true) }

        if (isSeries) {
            val isAnime = Regex("""const\s+isAnime\s*=\s*true""").containsMatchIn(raw)
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(
                title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score?.let { Score.from10(it) }
                this.tags = genres + languages
                this.actors = actors
                this.trailers = trailers
            }
        }

        val sources = movieSources(raw)
        if (sources.isEmpty()) return null
        val data = sources.map { "${it.second}|${it.first}" }.joinToString("\n")
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score?.let { Score.from10(it) }
            this.tags = genres + languages
            this.actors = actors
            this.trailers = trailers
        }
    }

    /** Each source is id to label, ordered with the page's own player first. */
    private fun movieSources(script: String): List<Pair<String, String>> {
        val out = linkedMapOf<String, String>()
        for (m in EMBED_ID.findAll(script)) {
            out.putIfAbsent(m.groupValues[1], "MovieNestBD")
        }
        for (m in LINK_ENTRY.findAll(script)) {
            val quality = m.groupValues[2].trim()
            if (quality.contains("subtitle", true)) continue
            val id = mediaIdFromLink(m.groupValues[1]) ?: continue
            if (out[id] == "MovieNestBD" && quality.isNotEmpty()) out[id] = quality
            else out.putIfAbsent(id, quality.ifEmpty { "MovieNestBD" })
        }
        return out.map { it.key to it.value }.take(MAX_SOURCES)
    }

    private fun parseEpisodes(script: String, url: String): List<Episode> {
        val block = EPISODES_BLOCK.find(script)?.groupValues?.get(1) ?: return emptyList()
        val season = Regex("""-s(\d{1,2})(?:$|/)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val out = mutableListOf<Episode>()
        for (m in EPISODE_ENTRY.findAll(block)) {
            val id = mediaIdFromLink(m.groupValues[2]) ?: continue
            val label = m.groupValues[1].trim()
            val number = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: (out.size + 1)
            out.add(
                newEpisode("Episode $number|$id") {
                    this.name = label.ifEmpty { "Episode $number" }
                    this.season = season
                    this.episode = number
                }
            )
        }
        return out
    }

    private fun mediaIdFromLink(link: String): String? {
        // A /file/ token is base64 of "id|slug|ts|sig"; its first 24 letters are
        // not a media id. Only take a literal id from player/embed links.
        if (!link.contains("/file/", true)) {
            ID24.find(link)?.let { return it.value }
        }
        val token = link.substringAfterLast("/", "").trim()
        if (token.isEmpty()) return null
        val padded = token + "=".repeat((4 - token.length % 4) % 4)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return ID24.find(decoded)?.value
    }

    // --- Links ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val entries = data.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2 && ID24.matches(parts[1])) parts[1] to parts[0] else null
        }.distinctBy { it.first }
        if (entries.isEmpty()) return false

        val resolved = coroutineScope {
            entries.map { (id, label) -> async { resolve(id, label) } }.awaitAll()
        }
        val subs = linkedMapOf<String, SubtitleFile>()
        for (result in resolved) result.second.forEach { subs.putIfAbsent(it.url, it) }
        subs.values.forEach { subtitleCallback(it) }

        var count = 0
        for (result in resolved) {
            val link = result.first ?: continue
            callback(link)
            count++
        }
        return count > 0
    }

    private suspend fun resolve(id: String, label: String): Pair<ExtractorLink?, List<SubtitleFile>> {
        var streamUrl: String? = null
        try {
            val playerText = app.get("$mainUrl/player/$id", headers = HEADERS, timeout = TIMEOUT).text
            streamUrl = IFRAME_SRC.find(playerText)?.groupValues?.get(1)?.let { absolute(it) }
            if (streamUrl.isNullOrEmpty()) {
                val direct = app.get("https://embed.jiofiles.pics/$id", headers = HEADERS, timeout = TIMEOUT).text
                streamUrl = STREAM_URL.find(unescape(direct))?.value
            }
            if (streamUrl.isNullOrEmpty()) return null to emptyList()

            val page = unescape(app.get(streamUrl, headers = HEADERS, timeout = TIMEOUT).text)
            val drive = DRIVE_ID.find(page)?.groupValues?.get(1)
            val pool = WORKERS.find(page)?.groupValues?.get(1)?.let { WORKER_URL.find(it)?.value?.trimEnd('/') }
            val directHls = HLS_URL.find(page)?.groupValues?.get(1)

            val hls = directHls ?: run {
                if (drive.isNullOrEmpty() || pool.isNullOrEmpty()) return null to emptyList()
                val body = MAPPER.writeValueAsString(mapOf("driveid" to drive))
                    .toRequestBody(JSON_MEDIA)
                val answer = app.post(
                    "$pool/api/encode", requestBody = body, headers = HEADERS, timeout = TIMEOUT
                ).text
                val node = runCatching { MAPPER.readTree(answer) }.getOrNull() ?: return null to emptyList()
                if (node.path("status").asText() != "success") return null to emptyList()
                node.get("hls")?.asText()?.takeIf { it.isNotBlank() } ?: return null to emptyList()
            }

            val link = newExtractorLink(
                source = name,
                name = label.ifEmpty { name },
                url = hls,
                // The worker returns an HLS master playlist with no .m3u8 suffix.
                // Cloudstream only builds an HLS media source for M3U8 links; VIDEO
                // makes ExoPlayer parse the playlist as a media container (error 3003).
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = HEADERS
            }
            return link to subtitles(page)
        } catch (e: Exception) {
            return null to emptyList()
        }
    }

    private suspend fun subtitles(page: String): List<SubtitleFile> {
        val block = SUBS.find(page)?.groupValues?.get(1) ?: return emptyList()
        val out = mutableListOf<SubtitleFile>()
        for (m in SUB_ENTRY.findAll(block)) {
            val sid = m.groupValues[1]
            val lang = m.groupValues[2].ifBlank { "Subtitle" }
            out.add(newSubtitleFile(lang, "https://sub.xcloud.icu/$sid"))
        }
        return out
    }

    // --- Helpers -------------------------------------------------------------

    private suspend fun fetch(url: String): Document? = runCatching {
        app.get(url, headers = HEADERS, timeout = TIMEOUT).document
    }.getOrNull()

    private fun absolute(href: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> mainUrl + href
        else -> "$mainUrl/$href"
    }

    /** Site data uses escaped slashes, e.g. "https:\/\/player.jiofiles.pics\/abc". */
    private fun unescape(text: String): String = text.replace("\\/", "/")

    private companion object {
        const val TIMEOUT = 20L
        const val MAX_SOURCES = 6

        val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept-Encoding" to "identity",
        )
        val JSON_MEDIA = "application/json".toMediaType()
        val MAPPER = ObjectMapper()

        val YEAR = Regex("""\b(19|20)\d{2}\b""")
        val ID24 = Regex("""[a-zA-Z0-9]{24}""")
        val EMBED_ID = Regex("""(?:embed|player)\.jiofiles\.pics/([a-zA-Z0-9]{24})""")
        val LINK_ENTRY = Regex("""(?s)\{\s*name:\s*"[^"]*"\s*,\s*link:\s*"([^"]+)"\s*,\s*quality:\s*"([^"]*)"""")
        val EPISODES_BLOCK = Regex("""(?s)rawEpisodes\s*=\s*\[(.*?)\];""")
        val EPISODE_ENTRY = Regex("""(?s)\{\s*name:\s*"([^"]*)"\s*,\s*link:\s*"([^"]*)"""")
        val IFRAME_SRC = Regex("""(?is)<iframe[^>]*?src=["']([^"']+)["']""")
        val STREAM_URL = Regex("""https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9_/-]*stream_[0-9a-f]{16}""")
        val DRIVE_ID = Regex("""encodedDriveId\s*:\s*"([^"]+)""")
        val WORKERS = Regex("""workerPools\s*:\s*\[([^\]]*)\]""")
        val WORKER_URL = Regex("""https:[^"',\s]+""")
        val HLS_URL = Regex("""hlsUrl\s*:\s*"([^"]+)""")
        val SUBS = Regex("""(?s)rawSubs\s*:\s*\[(.*?)\]""")
        val SUB_ENTRY = Regex("""(?s)\{\s*"id"\s*:\s*(\d+).*?"lang"\s*:\s*"([^"]*)"""")
    }
}
