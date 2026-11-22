// Anirex (public website). Anime streaming with sub/dub via embed iframes.
android {
    namespace = "com.mamun.bdix"
}

cloudstream {
    version = 5
    description = "Anime from anirex.to with sub/dub support. Works on any network."
    authors = listOf("Mamun015")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA",
        "ONA",
        "Special",
        "Others",
    )
    language = "en"

    iconUrl = "https://anirex.to/images/fav.png"
}