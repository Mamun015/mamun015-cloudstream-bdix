package com.mamun.bdix

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Dflix series, the /s half of movies.discoveryftp.net.
 *
 * The site is plain server rendered HTML:
 *   POST /search  body term=<q>&types=s -> result blocks
 *   GET  /s/category/<name>/<page>        -> category pages
 *   GET  /s/view/<id>                     -> detail page with one link per season
 *   GET  /s/view/<id>/<season>            -> the episodes of that season
 *
 * A search result is a div.moviesearchiteam with an anchor to /s/view/<id>, a
 * .searchtitle and a poster on ext-img.discoveryftp.net.
 *
 * On a season page every episode is an h5 like "S5 | EP 1" with an anchor whose
 * href is the media file on the CDN (some rows use an intent: link instead, the
 * plain http url is taken out of it).
 *
 * Only reachable from a BDIX-connected line. Every network failure degrades to an
 * empty list or null instead of an exception, so the app never shows a crash.
 */
class DflixSeriesProvider : MainAPI() {
    override var mainUrl = "https://movies.discoveryftp.net"
    override var name = "(BDIX) Dflix Series"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Cartoon,
        TvType.Documentary,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "category/Foreign" to "English",
        "category/Bangla" to "Bangla",
        "category/Hindi" to "Hindi",
        "category/South" to "South",
        "category/Animation" to "Animation",
        "category/Dubbed" to "Dubbed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch("$mainUrl/s/${request.data}/$page") ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false
        )
        val items = parseCategory(doc)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        // The series search is a form post, the movies one is a plain path.
        val doc = runCatching {
            app.post(
                "$mainUrl/search",
                referer = "$mainUrl/s",
                data = mapOf("term" to encodeForm(term), "types" to "s"),
                timeout = TIMEOUT,
            ).document
        }.getOrNull() ?: return emptyList()

        return parseSearchResults(doc)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url) ?: return null

        val title = doc.selectFirst(".movie-detail-content-test h3, .movie-detail-content h3")
            ?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null

        val seasons = seasonLinks(doc)
        val episodes = if (seasons.isEmpty()) {
            // No season list on the page: the episodes it lists are all there is.
            parseEpisodes(doc, 1)
        } else {
            episodesOf(seasons)
        }
        if (episodes.isEmpty()) return null

        val poster = fixImageUrl(doc.selectFirst(".movie-detail-banner img[src]")?.attr("src"))
        val plot = doc.selectFirst(".storyline")?.text()?.trim().orEmpty()
        val tags = doc.select(".ganre-wrapper a")
            .map { it.text().trim().trim(',').trim() }
            .filter { it.isNotEmpty() }
        val actors = doc.select("#team-slider .col-lg-2").mapNotNull { actor(it) }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
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
        val media = directMedia(data) ?: return false
        val url = encodeMedia(media)
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = url,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = "$mainUrl/s"
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }

    // --- Pages ----------------------------------------------------------------

    private suspend fun fetch(url: String): Document? = runCatching {
        app.get(url, referer = "$mainUrl/s", timeout = TIMEOUT).document
    }.getOrNull()

    /** Result blocks of the /search form post. */
    private fun parseSearchResults(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (block in doc.select("div.moviesearchiteam")) {
            val href = block.selectFirst("a[href]")?.attr("href")?.trim().orEmpty()
            if (href.isEmpty() || !href.contains("/s/view/")) continue
            val url = absolute(href)
            if (!seen.add(url)) continue

            val title = block.selectFirst(".searchtitle")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue

            val poster = fixImageUrl(block.selectFirst("img[src]")?.attr("src"))
            val year = yearOf(block.select(".searchdetails").joinToString(" ") { it.text() })

            out.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            )
        }

        if (out.isEmpty()) {
            for (link in doc.select("a[href*=\"/s/view/\"]")) {
                val href = link.attr("href").trim()
                if (href.isEmpty()) continue
                val url = absolute(href)
                if (!seen.add(url)) continue

                val title = link.selectFirst(".searchtitle")?.text()?.trim().orEmpty()
                    .ifEmpty { link.text().trim() }
                if (title.isEmpty()) continue

                val poster = fixImageUrl(link.selectFirst("img[src]")?.attr("src"))
                out.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return out
    }

    /** Cards of a category page. */
    private fun parseCategory(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (block in doc.select("div.col-xl-4")) {
            val href = block.selectFirst("a[href]")?.attr("href")?.trim().orEmpty()
            if (href.isEmpty() || !href.contains("/s/view/")) continue
            val url = absolute(href)
            if (!seen.add(url)) continue

            val title = titleOf(block)
            if (title.isEmpty()) continue

            val poster = fixImageUrl(block.selectFirst("img[src]")?.attr("src"))
            out.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        if (out.isEmpty()) {
            for (link in doc.select("a[href*=\"/s/view/\"]")) {
                val href = link.attr("href").trim()
                if (href.isEmpty()) continue
                val url = absolute(href)
                if (!seen.add(url)) continue

                val title = titleOf(link)
                if (title.isEmpty()) continue

                val poster = fixImageUrl(link.selectFirst("img[src]")?.attr("src"))
                out.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return out
    }

    /**
     * The season links of a series page, oldest season first. The site lists
     * them newest first, but the app plays a season list in order.
     */
    private fun seasonLinks(doc: Document): List<Season> {
        val found = linkedMapOf<Int, String>()
        for (link in doc.select("a[href]")) {
            val href = link.attr("href").trim()
            val match = SEASON_URL.find(href) ?: continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            if (!found.containsKey(number)) found[number] = absolute(href)
        }
        return found.entries.sortedBy { it.key }.take(MAX_SEASONS).map { Season(it.key, it.value) }
    }

    /** Episodes of every season, fetched in parallel. */
    private suspend fun episodesOf(seasons: List<Season>): List<Episode> {
        val groups = runCatching {
            coroutineScope {
                seasons.map { season ->
                    async {
                        val doc = fetch(season.url)
                        if (doc == null) emptyList() else parseEpisodes(doc, season.number)
                    }
                }.awaitAll()
            }
        }.getOrElse { emptyList() }
        val out = mutableListOf<Episode>()
        for (group in groups) out.addAll(group)
        return out
    }

    /**
     * Episode rows look like <h5>S5 | EP 1 <a href="http://cdn5...mkv">,
     * the anchor href is the media file itself.
     */
    private fun parseEpisodes(doc: Document, seasonNumber: Int): List<Episode> {
        val out = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var counter = 0

        for (row in doc.select("h5")) {
            val label = row.ownText().trim()
            val media = directMedia(row.selectFirst("a[href]")?.attr("href")) ?: continue
            if (!seen.add(media)) continue
            counter++

            val match = SEASON_EP.find(label)
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: seasonNumber
            val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: counter

            out.add(
                newEpisode(encodeMedia(media)) {
                    this.name = label.ifEmpty { "Episode $counter" }
                    this.season = season
                    this.episode = episode
                }
            )
        }

        if (out.isEmpty()) {
            // Fallback: any direct media link on the page.
            for (link in doc.select("a[href]")) {
                val media = directMedia(link.attr("href")) ?: continue
                if (!isMediaFile(media)) continue
                if (!seen.add(media)) continue
                counter++

                out.add(
                    newEpisode(encodeMedia(media)) {
                        this.name = "Episode $counter"
                        this.season = seasonNumber
                        this.episode = counter
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

    /** Title of a category card, the layout differs a little between pages. */
    private fun titleOf(block: Element): String {
        val candidates = listOf(
            ".ftitle",
            ".searchtitle",
            "h2",
            "h3",
            "h4",
            ".title",
        )
        for (selector in candidates) {
            val text = block.selectFirst(selector)?.text()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        val alt = block.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
        if (alt.isNotEmpty()) return alt
        return block.text().trim()
    }

    /** "intent:https://host/file.mkv#Intent;type=video/mp4;end" -> the file url. */
    private fun directMedia(href: String?): String? {
        val raw = href?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val candidate = if (raw.startsWith("intent:", ignoreCase = true)) {
            raw.substringAfter("intent:", "").substringBefore('#').trim()
        } else {
            raw
        }
        return candidate.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun isMediaFile(url: String): Boolean =
        MEDIA_EXTENSION.containsMatchIn(url.substringBefore('?'))

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

    /** Form body value, the site decodes it as application/x-www-form-urlencoded. */
    private fun encodeForm(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class Season(val number: Int, val url: String)

    private companion object {
        const val TIMEOUT = 20L
        const val MAX_SEASONS = 15
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        val YEAR = Regex("""\b(19|20)\d{2}\b""")
        /** A doubled slash that is not the one in "https://". */
        val DOUBLE_SLASH = Regex("""(?<!:)//+""")
        /** /s/view/<id>/<season> */
        val SEASON_URL = Regex("""/s/view/(\d+)/(\d+)""")
        /** "S5 | EP 1" */
        val SEASON_EP = Regex("""S(\d+)\s*\|\s*EP\s*(\d+)""", RegexOption.IGNORE_CASE)
        val MEDIA_EXTENSION =
            Regex("""\.(mkv|mp4|m4v|avi|webm|mov|ts|m2ts|flv|wmv)$""", RegexOption.IGNORE_CASE)
    }
}
