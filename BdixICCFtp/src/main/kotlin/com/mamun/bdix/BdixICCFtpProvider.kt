package com.mamun.bdix

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TrailerData
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getDurationFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * ICC Ftp, a BDIX media server.
 *
 *   POST /command.php  body cSearch=<q>  -> [{"id","type","name","image"},...]
 *   GET  /player.php?play=<id>           -> player page
 *
 * A player page either carries a download dropdown
 * `<ul class="dropdown-menu"><li><a href="<file>.mkv" download>Name</a></li></ul>`
 * (one entry per file = a series/pack) or a single `<a class="btn" href="<file>.mkv" download>`
 * (a movie). Posters live at `/files/`.
 *
 * Only reachable from a BDIX-connected line. Every network failure degrades to an
 * empty list or null instead of an exception, so the app never reports a crash.
 */
class BdixICCFtpProvider : MainAPI() {
    override var mainUrl = "http://10.16.100.244/"
    override var name = "(BDIX) ICC Ftp"
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
        "0" to "Latest",
        "59" to "Bangla Movies",
        "2" to "Hindi Movies",
        "19" to "English Movies",
        "43" to "Dual Audio",
        "32" to "South Movies",
        "33" to "Animated",
        "36" to "English Series",
        "37" to "Hindi Series",
        "41" to "Documentary",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = runCatching {
            val doc = app.get("${mainUrl}index.php?category=${request.data}").document
            doc.select("div.post-wrapper > a").mapNotNull { post ->
                val name = post.select("img").attr("alt").trim()
                // The card links to "?session=&play=<id>", which only works relative to
                // the listing page; the player page takes the id directly.
                val url = playUrl(post.attr("href"))
                val image = post.select("img").attr("src").trim().takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("http", true)) it else "${mainUrl}$it" }
                if (name.isNotBlank() && url != null) {
                    newMovieSearchResponse(name, url, TvType.Movie) {
                        this.posterUrl = image
                    }
                } else null
            }
        }.getOrNull() ?: emptyList()
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return runCatching {
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = "cSearch=${URLEncoder.encode(term, "UTF-8")}".toRequestBody(mediaType)
            val text = app.post("${mainUrl}command.php", requestBody = body).text
            parseSearchItems(text).map { it.toSearchResponse() }
        }.getOrNull() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc: Document = runCatching { app.get(url).document }.getOrNull() ?: return null

        val title = doc.select("table.ewTable tr:first-child strong")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() } ?: "ICC Ftp"
        val poster = poster(doc)
        val year = cellAfter(doc, "Year:").takeIf { it.isNotBlank() }?.let { firstInt(it) }
        val genre = cellAfter(doc, "Genre:")
        val plot = cellAfter(doc, "Plot:")
        val durationRaw = cellAfter(doc, "Runtime:")
        val trailer = doc.selectFirst("button[data-theVideo]")?.attr("data-theVideo") ?: ""
        val trailers = if (trailer.isNotBlank()) {
            mutableListOf(TrailerData(extractorUrl = trailer, raw = false, referer = mainUrl))
        } else mutableListOf()

        // The player holds one <source src title="S01E01"> per file: a single file is a
        // movie, several are the episodes of a season pack. The navbar dropdowns also
        // use <a> links, so only the video element is read.
        val files = doc.select("video source[src]")
            .mapNotNull { it.attr("src").trim().takeIf { url -> isMediaUrl(url) } }
            .distinct()

        val episodes = files.mapIndexed { index, href ->
            val found = seasonEpisodeOf(href)
            newEpisode(href) {
                this.season = found.first ?: 1
                this.episode = found.second ?: (index + 1)
                this.name = "Episode " + (found.second ?: (index + 1))
            }
        }

        if (episodes.size > 1) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genre.split(",").map { it.trim() }.filter { it.isNotBlank() }
                this.duration = getDurationFromString(durationRaw)
                this.trailers = trailers
            }
        }

        // One file only: the download <a class="btn">, else that single <source>.
        val movieLink = doc.selectFirst("a.btn[href]")?.attr("href")?.trim()
            ?: files.firstOrNull()
            ?: firstMediaUrl(doc)
        if (movieLink.isNullOrBlank() || !isMediaUrl(movieLink)) return null

        return newMovieLoadResponse(title, url, TvType.Movie, movieLink) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genre.split(",").map { it.trim() }.filter { it.isNotBlank() }
            this.duration = getDurationFromString(durationRaw)
            this.trailers = trailers
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
            newExtractorLink(source = this.name, name = this.name, url = link) {
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept-Encoding" to "identity",
                )
            }
        )
        return true
    }

    // --- Parsing --------------------------------------------------------------

    private fun parseSearchItems(text: String): List<SearchItem> {
        return runCatching {
            val node = MAPPER.readTree(text)
            if (!node.isArray) return@runCatching emptyList()
            node.elements().asSequence().mapNotNull { item ->
                val id = item.get("id")?.asText()
                val name = item.get("name")?.asText()?.trim()
                if (id.isNullOrBlank() || name.isNullOrBlank()) return@mapNotNull null
                SearchItem(id, name, item.get("image")?.asText())
            }.toList()
        }.getOrNull() ?: emptyList()
    }

    private fun SearchItem.toSearchResponse(): SearchResponse {
        val url = "${mainUrl}player.php?play=$id"
        val image = image?.trim()?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http", true)) it else "${mainUrl}files/$it" }
        return newMovieSearchResponse(name, url, TvType.Movie) {
            this.posterUrl = image
        }
    }

    private fun poster(doc: Document): String? {
        val src = doc.selectFirst(".col-md-4 > img[src], .col-md-4 img[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("img[src]")?.attr("src")?.trim()
        return src?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("http", true)) it else "${mainUrl}$it" }
    }

    private fun cellAfter(doc: Document, label: String): String {
        val td = doc.select("table.ewTable tr td:containsOwn($label)").firstOrNull() ?: return ""
        val cells = td.parent()?.select("> td") ?: return ""
        val idx = cells.indexOf(td)
        return if (idx >= 0 && idx + 1 < cells.size) cells.get(idx + 1).text().trim() else ""
    }

    private fun firstInt(text: String): Int? =
        Regex("""\d+""").find(text)?.value?.toIntOrNull()

    /** "?session=&play=46395" / "player.php?play=46395" -> player page url. */
    private fun playUrl(href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty()) return null
        Regex("""play=(\d+)""").find(raw)?.let {
            return "${mainUrl}player.php?play=${it.groupValues[1]}"
        }
        return if (raw.startsWith("http", true)) raw else "${mainUrl}${raw.trimStart('/')}"
    }

    /** "Breaking Bad S05E01.mkv" -> 5 to 1. */
    private fun seasonEpisodeOf(url: String): Pair<Int?, Int?> {
        val name = url.substringBefore('?')
        Regex("""[Ss](\d{1,2})[\s._-]?[Ee][Pp]?(\d{1,3})""").find(name)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("""[\s._-][Ee][Pp][\s._-]?(\d{1,3})\b""").find(name)?.let {
            return null to it.groupValues[1].toIntOrNull()
        }
        return null to null
    }

    private fun firstMediaUrl(doc: Document): String? {
        val src = doc.selectFirst("source[src]")?.attr("src")?.trim()
        if (src != null && isMediaUrl(src)) return src
        return doc.select("a[href]").mapNotNull {
            it.attr("href").trim().takeIf { isMediaUrl(it) }
        }.firstOrNull()
    }

    private val MEDIA_EXT = Regex("""(?i)\.(mkv|mp4|m4v|avi|webm|mov|ts|m2ts|flv|wmv)($|\?|&)""")
    private fun isMediaUrl(url: String?): Boolean =
        url?.let { MEDIA_EXT.containsMatchIn(it) } == true

    // --- Models --------------------------------------------------------------

    private data class SearchItem(val id: String, val name: String, val image: String?)

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        val MAPPER = ObjectMapper()
    }
}
