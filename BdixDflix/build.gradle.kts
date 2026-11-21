// Dflix (BDIX). Movies live under /m and series under /s on movies.discoveryftp.net.
version = 1

cloudstream {
    description = "Dflix (BDIX). Movies and TV series from movies.discoveryftp.net. Works only on a BDIX line."
    authors = listOf("Mamun015")

    /**
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama",
        "Cartoon",
        "Documentary",
    )
    language = "bn"

    iconUrl = "https://movies.discoveryftp.net/assets/images/icon.png"
}
