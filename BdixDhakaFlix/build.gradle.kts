// DhakaFlix BDIX provider (Movies, Series, Anime).
version = 6

android {
    namespace = "com.mamun.bdix"
}

cloudstream {
    description = "DhakaFlix (BDIX). Movies, TV series and anime from BDIX DhakaFlix servers. Works only on a BDIX line."
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
    language = "bn"

    iconUrl = "https://raw.githubusercontent.com/Mamun015/Mamun015-Zangetsu/main/logo.png"
}