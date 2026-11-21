# Mamun015 BDIX for Cloudstream

Extension repository for [Cloudstream](https://github.com/recloudstream/cloudstream) that plays media from Bangladeshi BDIX file servers. Everything is a direct HTTP file link from the BDIX server itself, so playback depends on your line: these sources only work on a BDIX-connected network in Bangladesh.

## Install

Open Cloudstream > Settings > Extensions > Add repository, and paste:

```
https://raw.githubusercontent.com/Mamun015/mamun015-cloudstream-bdix/master/repo.json
```

The same manifest is also served from the build branch if the first URL ever fails:

```
https://raw.githubusercontent.com/Mamun015/mamun015-cloudstream-bdix/builds/repo.json
```

## Sources

| Plugin | Site | Content |
| --- | --- | --- |
| BdixDhakaFlix | DhakaFlix (172.16.50.7 / .10 / .12 / .14) | Movies, TV series, anime, documentary, wrestling |
| BdixCircleftp | Circle FTP (new.circleftp.net) | Movies and series by category |
| BdixDflix | Dflix (movies.discoveryftp.net) | Movies and series, separate providers |
| BdixICCFtp | ICC FTP (10.16.100.244) | Movies and season packs |
| MovieNestBD | movienestbd.best | Movies, web series and anime from a public site |
| AnirexTo | anirex.to | Anime with sub/dub from a public streaming site |

Each plugin reads the server's own listings, so a title appears only if that server hosts the file. Posters come from TMDb where the server has no artwork.

## How it works

- Search and browsing use the same endpoints the server's website uses.
- Playback hands the direct file URL to Cloudstream's player, with BDIX-friendly headers and `Accept-Encoding: identity` so seeking works.
- No accounts, no ads, no tracking, no proxy. Your device talks to the BDIX server directly.

## Limits

- BDIX-only: outside a BDIX line every source stays empty instead of crashing.
- Some servers are reachable only on specific BDIX providers, so one plugin may work while another returns nothing.
- Files are usually x265/HEVC or 10-bit releases; the built-in player may refuse them on older devices. An external player that supports HEVC fixes that.

## Build

Push to `master`; `.github/workflows/build.yml` builds every module and publishes `plugins.json`, `repo.json` and the `.cs3` files to the `builds` branch. Local build needs Android SDK, JDK 17 and Gradle 8.x (`./gradlew make`).

## License

GPL-3.0, see [LICENSE](LICENSE).
