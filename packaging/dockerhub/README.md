# Trackhop server (beta)

Trackhop keeps your playlists in sync between music services: Spotify, YouTube, TIDAL, Navidrome
and other Subsonic servers, Jellyfin, Plex, with Last.fm, ListenBrainz, SoundCloud links and
playlist files as sources. This image is its sync engine behind a small web page, for a home
server, a NAS or a VPS: scheduled syncs run there whether or not your phone is on.

**Beta:** it works for everyday use, but pages and settings can still change between releases.

## Run it

```sh
docker run -d --name trackhop \
  -p 8080:8080 \
  -v trackhop-data:/data \
  -e SONGPORT_URL=http://127.0.0.1:8080 \
  -e SONGPORT_PASSWORD=choose-one \
  xlollx/trackhop:latest
```

Then open `http://127.0.0.1:8080` (any user name, the password you chose). Everything is kept in
the `/data` volume. The image runs on `amd64` and `arm64`.

For the whole household, add `-e SONGPORT_MULTIUSER=true`: you sign in as `admin` with the password,
invite the others from **Users** with a link, and each person has their own services and syncs
([how](https://trackhop.org/guide/docker#several-people-on-one-server)).

## Tags

- `latest`: the newest release.
- `0.5.1` and so on: a given release of Trackhop Desktop & Server
  ([releases](https://github.com/xlollx/Trackhop/releases?q=server-v&expanded=true)).

The same image is on the GitHub Container Registry as `ghcr.io/xlollx/trackhop`.

## More

- [The Docker guide](https://trackhop.org/guide/docker): Docker Compose, settings,
  services, reverse proxy, API.
- [Source code](https://github.com/xlollx/Trackhop) (GPL-3.0-only) and
  [issues](https://github.com/xlollx/Trackhop/issues).
