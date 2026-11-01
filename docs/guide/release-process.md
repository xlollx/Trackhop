# Release process

1. **Version**: bump `versionCode` and `versionName` in `android/app/build.gradle`. Every version that
   users should get is a release: Obtainium and F-Droid follow the tags.
2. **Changelog**: `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` in all seven
   locales (en-US, it-IT, fr-FR, de-DE, es-ES, zh-CN, ja-JP), under 500 characters.
3. **Push to `main`**. CI (`.github/workflows/build.yml`) runs the unit tests, builds the debug APK, the signed release APK (`full` flavour) and the AAB (`play` flavour),
   and, when the `versionName` has no tag yet, creates the tag `vX.Y.Z` and the GitHub Release with
   `Trackhop-vX.Y.Z.apk` and `Trackhop-vX.Y.Z-play.aab` (up to 1.5.2 the APK was `Songport-vX.Y.Z.apk`).
4. **Obtainium** users get it at once. **F-Droid** finds the new tag (`UpdateCheckMode: Tags`),
   builds it from source a few days later and, because the build is reproducible, publishes the
   GitHub APK with its own signature.

## Desktop and Docker

They have their own version, separate from the app's: `server/VERSION`, with its notes in
`server/CHANGELOG.md`. Bump it (and add its section) when a server or Desktop change should reach
people; the app's version does not move for it, nor theirs for an app-only change.

- On every push to `main` that touches the server, `server.yml` publishes the Docker image as
  `latest` and as `server/VERSION`.
- When `server/VERSION` has no release yet, `desktop.yml` builds Desktop for Windows and Linux and
  creates the release `server-vX.Y.Z`: a prerelease that is never "latest", so Obtainium and the
  links to `releases/latest` keep finding the app.
- The page's footer shows both: the server's version and the app version whose engine it runs.
- After a new release, `stores.yml` opens the winget pull request and pushes the Chocolatey package,
  and `msstore.yml` sends the `.msix` to the Microsoft Store (each once its secrets exist); Docker Hub
  follows `server.yml`; Scoop's bucket updates itself; Flathub needs a step by hand. All of it is in
  [packaging/README.md](https://github.com/xlollx/Trackhop/blob/main/packaging/README.md).
- The direct download links (README, the site's "Also on a computer" section, the Desktop guide)
  name the version: point them at the new `server-v…` files once its release is out.

## Keeping the F-Droid build reproducible

- The full APK is built with none of the repository's variables (`build.yml`: `env -u …`), so it is
  what F-Droid builds from source. A variable that changed it (1.3.6: an old `PRIVACY_POLICY_URL`)
  made F-Droid's build fail. The Play bundle keeps them.
- The release key never changes (certificate SHA-256
  `21eadf268c72f85565961a6045bf4be5abaf256d680d1e0d808d93ed22d35032`). Keep a backup of the keystore and
  its passwords outside GitHub.
- The F-Droid recipe is `metadata/com.xlollx.songport.yml` in fdroiddata; a copy lives in `fdroid/`.

## Google Play

After each app release `play.yml` uploads the bundle to the closed track, once its secret exists, with
the notes in `play/whatsnew/<versionCode>/` (en-US and it-IT, written for Play: write them with the
changelog of each version).

The `play` flavour is published as **Trackhop**, like every other build: same package id, no built-in
web connectors, AdMob banner. Listing texts, Data safety answers and the closed-test kit are in `docs/play-store-listing.md`
and `docs/play-testers.md`.

## Store graphics

`scripts/store_graphics.sh` (ImageMagick) redraws the icon PNGs and the EN/IT feature graphics
from the same geometry as the icon's vector drawables, and prints the contrast of every line of
text; `scripts/store_graphics.sh preview DIR` also writes icon previews for review.

## Checks before pushing

- Strings: the same names, in the same order, in all seven `values*/strings.xml`; apostrophes escaped
  (`\'`).

[All guide pages](./)
