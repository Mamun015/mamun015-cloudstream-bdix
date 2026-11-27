// MovieNestBD (public website). Server rendered listings, xcloud worker HLS.
android {
    namespace = "com.mamun.bdix"
}

cloudstream {
    version = 5
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
