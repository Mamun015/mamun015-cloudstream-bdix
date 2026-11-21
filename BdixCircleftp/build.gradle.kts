// Circle FTP (BDIX). API on :5000, files on ftpN.circleftp.net.
version = 2

cloudstream {
    description = "Circle FTP (BDIX). Movies and TV series from new.circleftp.net. Works only on a BDIX line."
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
        "Documentary",
        "Others",
    )
    language = "bn"

    iconUrl = "http://new.circleftp.net/static/media/logo.fce2c9029060a10687b8.png"
}
