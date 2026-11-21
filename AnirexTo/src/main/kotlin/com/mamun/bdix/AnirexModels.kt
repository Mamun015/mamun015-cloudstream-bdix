package com.mamun.bdix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeStatus
import com.lagradost.cloudstream3.TvType

// --- API nested data objects -------------------------------------------------

data class SearchAnime(
    val id: Int?,
    @JsonProperty("anilist_id") val anilistId: Int?,
    @JsonProperty("title_romaji") val titleRomaji: String?,
    @JsonProperty("title_english") val titleEnglish: String?,
    @JsonProperty("cover_image_url") val coverImageUrl: String?,
    val format: String?,
    val slug: String?,
    @JsonProperty("average_score") val averageScore: Int?,
    @JsonProperty("season_year") val seasonYear: Int?,
)

data class HomepageData(
    val featured: List<SearchAnime>?,
    @JsonProperty("latestEpisodes") val latestEpisodes: List<SearchAnime>?,
    val trending: List<SearchAnime>?,
    @JsonProperty("newOnSite") val newOnSite: List<SearchAnime>?,
    val upcoming: List<SearchAnime>?
)

data class AnimeDTO(
    val id: Int?,
    @JsonProperty("anilist_id") val anilistId: Int?,
    @JsonProperty("title_romaji") val titleRomaji: String?,
    @JsonProperty("title_english") val titleEnglish: String?,
    @JsonProperty("cover_image_url") val coverImageUrl: String?,
    @JsonProperty("banner_image_url") val bannerImageUrl: String?,
    val format: String?,
    val status: String?,
    val episodes: Int?,
    @JsonProperty("average_score") val averageScore: Int?,
    val genres: List<String>?,
    val slug: String?,
    val season: String?,
    @JsonProperty("season_year") val seasonYear: Int?,
    val duration: Int?,
    val description: String?,
    @JsonProperty("start_date") val startDate: String?,
    val popularity: Int?,
    @JsonProperty("total_episodes") val totalEpisodes: Int?,
    @JsonProperty("latest_episode_number") val latestEpisodeNumber: Int?
)

data class EpisodeDTO(
    val id: Int?,
    @JsonProperty("episode_number") val episodeNumber: Int,
    val title: String?,
    val duration: Int?,
    @JsonProperty("air_date") val airDate: String?,
    val filler: Boolean?,
    @JsonProperty("has_sub") val hasSub: Boolean?,
    @JsonProperty("has_dub") val hasDub: Boolean?
)

data class WatchResponse(
    @JsonProperty("anime_id") val animeId: Int?,
    val episode: Int?,
    val servers: List<ServerDTO>?,
    @JsonProperty("duration_seconds") val durationSeconds: Int?
)

data class ServerDTO(
    val id: Int?,
    @JsonProperty("player_url") val playerUrl: String?,
    val audio: String?
)

// --- Extensions for the provider ---------------------------------------------

fun String?.toAnirexTvType(): TvType? {
    return when (this?.uppercase()) {
        "TV" -> TvType.TvSeries
        "MOVIE" -> TvType.Movie
        "OVA" -> TvType.AnimeMovie
        "ONA" -> TvType.Anime
        "SPECIAL" -> TvType.AnimeMovie
        "TV_SHORT" -> TvType.TvSeries
        "MUSIC" -> TvType.Others
        else -> TvType.Anime
    }
}

fun String?.toAnirexStatus(): AnimeStatus? {
    return when (this?.uppercase()) {
        "FINISHED" -> AnimeStatus.COMPLETED
        "RELEASING" -> AnimeStatus.ONGOING
        "NOT_YET_RELEASED" -> AnimeStatus.NOT_YET_RELEASED
        "CANCELLED" -> AnimeStatus.CANCELLED
        else -> null
    }
}