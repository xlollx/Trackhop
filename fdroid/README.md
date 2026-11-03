# F-Droid

Trackhop's GitHub build (`full` flavor) is F-Droid compatible: only free libraries (AndroidX, Kotlin,
OkHttp, Tink), no ads SDK, no trackers, no prebuilt binaries in the repository, no self-updater. It
includes the web connectors (`android/bridge`), which use the services' web interfaces rather than
official APIs: that is what the NonFreeNet anti-feature describes. The `play` flavor carries the ads
SDK and is never built by F-Droid.

What F-Droid reads from this repository:

- `fastlane/metadata/android/<locale>/`: title, short and full description, changelog per
  `versionCode`, icon and feature graphic. Phone screenshots go in `images/phoneScreenshots/`.
- `.github/FUNDING.yml`: donation links.
- `android/gradle/wrapper/`: the Gradle version (the wrapper JAR is the official 8.14.3 one,
  SHA-256 `7d3a4ac4…6172`, as listed on gradle.org).

## Submitting

1. Create an account on gitlab.com and fork https://gitlab.com/fdroid/fdroiddata.
2. Add `com.xlollx.songport.yml` from this folder as `metadata/com.xlollx.songport.yml`.
3. Open a merge request with the "App inclusion" template. The CI of fdroiddata builds the app; the
   reviewers comment there if something needs changing.

The recipe must stay exactly as `fdroid rewritemeta` writes it, or fdroiddata's CI fails: no
comments, Unix line endings (LF). Pasting it into GitLab's web editor on Windows saves CRLF; upload
the file with "Replace" instead. No `prebuild` step is needed for the Play flavor's ads SDK: the
`playImplementation` lines in `build.gradle` belong to a flavor F-Droid does not build, and
fdroiddata's CI (pipeline 2878053202 on !49974, scanner included) passed on the recipe without it.

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
