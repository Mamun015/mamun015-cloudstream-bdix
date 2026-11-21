package com.mamun.bdix

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Dflix movies, the /m half of movies.discoveryftp.net.
 *
 * The site is plain server rendered HTML:
 *   GET /m/find/<q>                  -> result cards
 *   GET /m/category/<name>/<page>    -> category pages, same card markup
 *   GET /m/view/<id>                 -> detail page, links to /m/playlist/<id>
 *   GET /m/playlist/<id>             -> M3U whose last line is the media file
 *
 * A card is a div.card with an anchor to /m/view/<id>, an h3 title and a poster
 * image on ext-img.discoveryftp.net. Cards without a link are titles the site
 * does not host yet and are skipped.
 *
 * Only reachable from a BDIX-connected line. Every network failure degrades to an
 * empty list or null instead of an exception, so the app never shows a crash.
 */
class DflixMoviesProvider : MainAPI() {
    override var mainUrl = "https://movies.discoveryftp.net"
    override var name = "(BDIX) Dflix Movies"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie,
    )

    override val mainPage = mainPageOf(
        "category/Bangla" to "Bangla",
        "category/English" to "English",
        "category/Hindi" to "Hindi",
        "category/Tamil" to "Tamil",
        "category/Animation" to "Animation",
        "category/Others" to "Others",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch("$mainUrl/m/${request.data}/$page") ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false
        )
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val doc = fetch("$mainUrl/m/find/${encode(term)}") ?: return emptyList()
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null

        val title = doc.selectFirst(".movie-detail-content h3, .movie-detail-content-test h3")
            ?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        // The detail page names the playlist, the playlist names the file.
        val playlistHref = doc.selectFirst("a[href*=\"/m/playlist/\"]")?.attr("href")?.trim().orEmpty()
        val playlist = if (playlistHref.isEmpty()) "" else absolute(playlistHref)
        val direct = doc.selectFirst("a[href$=\".mkv\"], a[href$=\".mp4\"]")?.attr("href")?.trim()
            ?: doc.selectFirst("a[href^=\"intent:\"]")?.attr("href")?.let { intentTarget(it) }
        val data = playlist.ifEmpty { direct.orEmpty() }
        if (data.isEmpty()) return null

        val poster = fixImageUrl(doc.selectFirst(".movie-detail-banner img[src]")?.attr("src"))
        // The badge wrapper holds an empty badge first, the quality one comes after.
        val quality = doc.select(".badge-wrapper .badge.badge-fill")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val plot = doc.selectFirst(".storyline")?.text()?.trim().orEmpty()
        val tags = doc.select(".ganre-wrapper a")
            .map { it.text().trim().trim(',').trim() }
            .filter { it.isNotEmpty() }
        val actors = doc.select("#team-slider .col-lg-2").mapNotNull { actor(it) }

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = if (quality.isEmpty()) plot else "<b>$quality</b><br><br>$plot"
            this.tags = tags
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val raw = data.trim()
        if (raw.isEmpty()) return false

        // A movie carries its playlist link; the file itself is the last line of
        // that M3U. Anything else is already a direct file.
        val media = if (raw.contains("/m/playlist/")) {
            val text = runCatching {
                app.get(absolute(raw), referer = "$mainUrl/m", timeout = TIMEOUT).text
            }.getOrNull()
            text?.let { lastMediaLine(it) }
        } else {
            intentTarget(raw) ?: raw
        }

        val url = media?.let { encodeMedia(it) } ?: return false
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = "$mainUrl/m"
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // --- Pages ----------------------------------------------------------------

    private suspend fun fetch(url: String): Document? = runCatching {
        app.get(url, referer = "$mainUrl/m", timeout = TIMEOUT).document
    }.getOrNull()

    /**
     * Result and category cards. The markup is a div.card per title, with an
     * anchor to /m/view/<id>, an h3 title, a poster and the release date.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (card in doc.select("div.card")) {
            val href = card.selectFirst("a[href]")?.attr("href")?.trim().orEmpty()
            if (href.isEmpty() || !href.contains("/m/view/")) continue
            val url = absolute(href)
            if (!seen.add(url)) continue

            val title = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue

            val poster = fixImageUrl(card.selectFirst("img[src]")?.attr("src"))
            val year = yearOf(card.selectFirst("span.movie_details_span")?.text())

            out.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            )
        }

        if (out.isEmpty()) {
            // Fallback for a layout that does not use div.card.
            for (link in doc.select("a[href*=\"/m/view/\"]")) {
                val href = link.attr("href").trim()
                if (href.isEmpty()) continue
                val url = absolute(href)
                if (!seen.add(url)) continue

                val title = link.attr("title").trim().ifEmpty {
                    link.closest("div.card")?.selectFirst("h3")?.text()?.trim().orEmpty()
                }.ifEmpty { link.text().trim() }
                if (title.isEmpty()) continue

                val poster = fixImageUrl(link.selectFirst("img[src]")?.attr("src"))
                out.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return out
    }

    private fun actor(block: Element): ActorData? {
        val image = block.selectFirst("img[src]")
        val name = image?.attr("alt")?.trim().orEmpty().ifEmpty {
            block.selectFirst("p")?.text()?.trim().orEmpty()
        }
        if (name.isEmpty()) return null
        val role = block.select("p").getOrNull(1)?.text()?.trim().orEmpty()
        return ActorData(
            actor = Actor(name, fixImageUrl(image?.attr("src"))),
            roleString = role.ifEmpty { null },
        )
    }

    // --- Helpers --------------------------------------------------------------

    /** Last non comment line of an M3U playlist, which is the media file. */
    private fun lastMediaLine(text: String): String? {
        val lines = text.split('\n')
        for (index in lines.indices.reversed()) {
            val line = lines[index].trim()
            if (line.isNotEmpty() && !line.startsWith("#")) return line
        }
        return null
    }

    /** "intent:https://host/file.mkv#Intent;type=video/mp4;end" -> the file url. */
    private fun intentTarget(href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("intent:", ignoreCase = true)) return null
        val target = raw.substringAfter("intent:", "").substringBefore('#').trim()
        return target.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun absolute(href: String): String {
        val raw = href.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return mainUrl + if (raw.startsWith("/")) raw else "/$raw"
    }

    /**
     * Posters live on ext-img.discoveryftp.net and the site sometimes writes a
     * doubled slash ("300//", "1080//", "media//"), which the image server does
     * not resolve.
     */
    private fun fixImageUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var fixed = raw
        if (fixed.startsWith("//")) {
            fixed = "https:$fixed"
        } else if (fixed.startsWith("http://")) {
            fixed = "https://" + fixed.removePrefix("http://")
        } else if (!fixed.startsWith("http")) {
            fixed = "$mainUrl/${fixed.trimStart('/')}"
        }
        return fixed.replace(DOUBLE_SLASH, "/")
    }

    /** Media paths on the CDN carry literal spaces, which break a plain url. */
    private fun encodeMedia(url: String): String = url.replace(" ", "%20")

    private fun yearOf(text: String?): Int? =
        text?.let { YEAR.find(it)?.value?.toIntOrNull() }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val TIMEOUT = 20L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        val YEAR = Regex("""\b(19|20)\d{2}\b""")
        /** A doubled slash that is not the one in "https://". */
        val DOUBLE_SLASH = Regex("""(?<!:)//+""")
    }
}
