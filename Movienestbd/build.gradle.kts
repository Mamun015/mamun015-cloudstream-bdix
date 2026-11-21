// MovieNestBD (public website). Server rendered listings, jiofiles/xcloud HLS.
version = 2

android {
    namespace = "com.mamun.bdix"
}

cloudstream {
    description = "MovieNestBD. Movies, web series and anime in many languages, with direct HLS links."
    authors = listOf("Mamun015")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "AsianDrama",
        "Documentary",
        "Others",
    )
    language = "en"

    iconUrl = "https://movienestbd.best/favicon.png"
}
