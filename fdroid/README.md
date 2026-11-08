# F-Droid

Trackhop's GitHub build (`full` flavor) is F-Droid compatible: only free libraries (AndroidX, Kotlin,
OkHttp, Tink), no ads SDK, no trackers, no prebuilt binaries in the repository, no self-updater. It
includes the web connectors (`android/bridge`), which use the services' web interfaces rather than
official APIs: that is what the NonFreeNet anti-feature describes. The `play` flavor uses Google Play
libraries and is never built by F-Droid.

What F-Droid reads from this repository:

- `fastlane/metadata/android/<locale>/`: title, short and full description, changelog per
  `versionCode`, icon and feature graphic. Phone screenshots go in `images/phoneScreenshots/`.
- `.github/FUNDING.yml`: donation links.
- `android/gradle/wrapper/`: the Gradle version (the wrapper JAR is the official 8.14.3 one,
  SHA-256 `7d3a4ac4…6172`, as listed on gradle.org).

## The recipe

Trackhop is on F-Droid since 31 October 2026 (https://f-droid.org/packages/com.xlollx.songport/). Its
recipe lives in fdroiddata, not here:
[metadata/com.xlollx.songport.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.xlollx.songport.yml).
New versions need nothing by hand: F-Droid's bot finds each `v<version>` tag (`UpdateCheckMode: Tags`),
adds its build and publishes it once F-Droid's build matches the GitHub APK byte for byte (same signing
key, `AllowedAPKSigningKeys`). A change to the recipe itself (licence, website, description) is a merge
request on a fork of fdroiddata, edited in GitLab's web editor.

The recipe must stay exactly as `fdroid rewritemeta` writes it, or fdroiddata's CI fails: no comments,
Unix line endings (LF). In a merge request, the `checkupdates` job fails whenever a newer Trackhop tag
exists than the recipe lists: it only says the bot has a version to add, and is not a problem of the
change.

The icon and the feature graphics are CC BY-SA 4.0 (see the README's licence section), so F-Droid
can carry them; the code is AGPL-3.0-only.

Check a change locally with `pip install fdroidserver`, then `fdroid rewritemeta` and `fdroid lint`
in a folder holding `metadata/com.xlollx.songport.yml`.

After inclusion F-Droid picks up new versions by itself from the `vX.Y.Z` tags.

## Signing: reproducible build

The recipe has `Binaries` and `AllowedAPKSigningKeys`: F-Droid builds the app from source, compares it
with the APK published on GitHub Releases and, when they match, distributes the GitHub APK with its
original signature. Users can then move between the GitHub and the F-Droid version without
uninstalling. Checked for 1.0.44 locally, and for 1.0.45 by fdroiddata's CI on !49974 (job
16703446859: "compared built binary to supplied reference binary successfully"): F-Droid's build
with the GitHub signature copied onto it (`apksigcopier copy`) is byte-identical to the GitHub APK.
The GitHub build uses a system Gradle 8.14.3, F-Droid the wrapper of the same version; the output
is the same.

It stays reproducible as long as:

- the GitHub build gets no build variable that changes the app (`SPOTIFY_CLIENT_ID`,
  `GOOGLE_CLIENT_ID`, `LASTFM_API_KEY`, `APPLE_DEVELOPER_TOKEN`...): F-Droid builds without them, so
  any value ends up only in the GitHub APK and the two differ;
- the release key stays the same. Its certificate SHA-256 is
  `21eadf268c72f85565961a6045bf4be5abaf256d680d1e0d808d93ed22d35032`. **Keep a backup of the keystore
  and its passwords** outside GitHub: without them no update can be published on F-Droid or GitHub.

`commit` holds the full commit hash, not the tag, as fdroiddata requires. After inclusion,
F-Droid's update check picks up new `vX.Y.Z` tags and builds them the same way.
