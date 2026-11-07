# Trackhop Desktop and the Docker image in stores and package managers

Trackhop Desktop (Windows, Linux) and the Docker image are released together as `server-v<version>`
(`server/VERSION`, `.github/workflows/desktop.yml`). These releases are **prereleases, never
"latest"** on GitHub (that belongs to the Android app, `v1.x`): every version check below looks at
`server-v*` tags, never at `/releases/latest`.

The guides and the README list the commands of the channels not live yet as "coming soon"; take the
note off each one once its channel is live.

| Channel | In this folder | Status | What the owner does once | Then, on each release |
|---|---|---|---|---|
| Docker Hub | `dockerhub/README.md`, `server.yml` | **Live** ([xlollx/trackhop](https://hub.docker.com/r/xlollx/trackhop)) | Docker Hub account and token, two secrets | `server.yml` pushes `xlollx/trackhop` and updates its page |
| winget | `winget/manifests/…/0.5.1/` | First PR open, in review | Fork, first PR by hand, a token | `stores.yml` opens the PR to `microsoft/winget-pkgs` |
| Chocolatey | `chocolatey/` | **Live** ([trackhop](https://community.chocolatey.org/packages/trackhop), approved 02/11 with 0.5.1; later versions pushed by `stores.yml`) | Account, API key secret | `stores.yml` packs, tests and pushes; moderation approves |
| Scoop | `scoop/trackhop.json` | **Live** ([xlollx/scoop-bucket](https://github.com/xlollx/scoop-bucket)) | Create `xlollx/scoop-bucket` | The bucket's Excavator updates the manifest |
| Homebrew (macOS) | `homebrew/` | **Live** ([xlollx/homebrew-tap](https://github.com/xlollx/homebrew-tap)) | Create `xlollx/homebrew-tap` | The tap's own workflow updates the cask |
| Microsoft Store | `msix/`, `desktop.yml` | **Live** ([Trackhop Desktop](https://apps.microsoft.com/detail/9PFX74Q2XN9P), product ID `9PFX74Q2XN9P`) | Account, reserve the name, three variables, first submission; for automatic updates an Entra ID app and four secrets | `msstore.yml` uploads the `.msix` and submits it (once its secrets exist; until then, by hand in Partner Center) |
| Google Play (the app) | `play/whatsnew/`, `play.yml` | Closed testing | A service account, its key as a secret, the track's name | `play.yml` uploads the release's `.aab` with the notes in `play/whatsnew/<versionCode>/` |
| Snap Store | `snap/`, `snap.yml` | **Live** ([snapcraft.io/trackhop](https://snapcraft.io/trackhop), stable since 0.8.0) | An Ubuntu One account, the name `trackhop`, one secret | `snap.yml` packs each Desktop release and publishes it to the stable channel (once its secret exists) |
| Flathub | `flatpak/`, `flatpak.yml` | Needs owner action, review risk | Desktop screenshots, a release with these files, the submission PR | A PR in the Flathub repo per release |

Secrets and variables go in GitHub: *Settings › Secrets and variables › Actions*, in the
*Secrets* or *Variables* tab, *New repository secret/variable*.

---

## Docker Hub

**In the repository.** `server.yml` also pushes the image to `docker.io/xlollx/trackhop` (`latest`
and the version, amd64 and arm64) when the secrets below exist, and then sets the Docker Hub page
from `dockerhub/README.md` (with `peter-evans/dockerhub-description`, pinned). Without the secrets
both steps are skipped and nothing else changes; `ghcr.io/xlollx/trackhop` stays the main image.

**Once:**

1. Sign up at [hub.docker.com](https://hub.docker.com/signup) as `xlollx` (or use an organisation;
   then set the variable `DOCKERHUB_REPOSITORY` to `<namespace>/trackhop`).
2. Create the public repository `trackhop` (*Repositories › Create*). The first push would create it
   too, but private on some plans.
3. *Account settings › Personal access tokens › Generate new token*, access **Read, Write, Delete**
   (the description update needs it; Read & Write only pushes images).
4. Secrets: `DOCKERHUB_USERNAME` (the user name) and `DOCKERHUB_TOKEN` (the token).
5. Run *Actions › Server › Run workflow* on `main`, or wait for the next server change.

**Each release:** automatic. Then remove the "coming soon" note from `docs/guide/docker.md` and the README.

---

## winget

**In the repository.** `winget/manifests/x/xlollx/Trackhop/0.5.1/`: the three files of a multi-file
manifest (schema 1.12, the format `microsoft/winget-pkgs` requires), validated against the official
JSON schemas. `PackageIdentifier: xlollx.Trackhop`; installer: the release's MSI (WiX, per-user,
`Scope: user`, no elevation), its SHA-256 and its `ProductCode` read from the MSI
(`{814DEDF3-BE95-3D1C-9364-D639845B0CFD}`; jpackage makes a new one each version, the
`UpgradeCode` `{5B8F3C1E-7A2D-4E9B-9C61-2F0D8A4B7E13}` stays).

`stores.yml` (called by `desktop.yml` after a new release) runs
[WinGet Releaser](https://github.com/vedantmgoyal9/winget-releaser) (pinned to a commit), which
builds the new version's manifests from the last one in winget-pkgs (Komac reads the new MSI's
hash and ProductCode) and opens the pull request from the fork.

**Once:**

1. Fork [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) under `xlollx` (*Fork*,
   keep the name).
2. **The first version must be a pull request by hand** (WinGet Releaser only updates a package that
   is already there). In the fork, on a new branch, add the three files of
   `packaging/winget/manifests/x/xlollx/Trackhop/0.5.1/` at the same path
   (`manifests/x/xlollx/Trackhop/0.5.1/`). If a newer `server-v` release is out by then, use
   `komac new xlollx.Trackhop` or `wingetcreate new <msi url>` for it instead.
3. Optional, on Windows: `winget settings --enable LocalManifestFiles`, then
   `winget validate --manifest <folder>` and `winget install --manifest <folder>`.
4. Open the pull request to `microsoft/winget-pkgs` `master`, titled
   `New package: xlollx.Trackhop version 0.5.1`, fill in its checklist, and sign the Microsoft CLA when
   the bot asks. The validation pipeline installs it in a sandbox; a moderator merges it.
5. Once it is merged: create a **classic** token (*GitHub › Settings › Developer settings › Personal
   access tokens › Tokens (classic)*) with `public_repo` and `workflow` (fine-grained tokens do not
   work with WinGet Releaser), and add it as the secret `WINGET_TOKEN`.

**Each release:** automatic (a pull request per version; moderators merge it, usually within a day
or two). To send a release again: *Actions › Stores › Run workflow*, version `x.y.z`.

---

## Chocolatey

**In the repository.** `chocolatey/trackhop/`: `trackhop.nuspec` (every element the moderation
checks ask for; the icon from the site, not a raw GitHub URL, which the rules refuse),
`tools/chocolateyinstall.ps1` (downloads the release's MSI and checks its SHA-256, installs it
silently; no binaries in the package, so no `VERIFICATION.txt`) and `tools/chocolateyuninstall.ps1`.
`chocolatey/update.ps1` sets the version, the MSI address and the checksum for a release.

`stores.yml`, on a Windows runner: downloads the release's MSI and hashes it, runs `update.ps1`,
`choco pack`, installs and uninstalls the package on the runner as a test, then `choco push` to the
community repository.

Notes: the MSI is per user, so `choco install trackhop` (run elevated by the user) installs into that
user's `%LOCALAPPDATA%\Trackhop`; from another administrator account or SYSTEM it would install
there. The package version is the plain `0.5.1`, with "beta" in the description: a `-beta`
version would hide it unless users add `--pre`.

**Once:**

1. Sign up at [community.chocolatey.org](https://community.chocolatey.org/account/Register) as
   `xlollx`, confirm the e-mail.
2. *Account › API Keys*: copy the key into the secret `CHOCOLATEY_API_KEY`.
3. First version: *Actions › Stores › Run workflow*, version `0.5.1`, winget unticked (or wait for
   the next release). The first version of a new package waits in moderation (automatic validation
   and verification, then a person); answer the moderators' comments on the package page. Later
   versions go faster.

**Each release:** automatic push; each version is approved by moderation before it is public.

---

## Scoop

**In the repository.** `scoop/trackhop.json`: the portable ZIP (`extract_dir: Trackhop`), its
hash, a Start menu shortcut and the `trackhop` command; `checkver` reads the GitHub releases API
and takes the newest `server-v` tag (`"tag_name": "server-vX.Y.Z"`, never `releases/latest`);
`autoupdate` points at the version's ZIP. No `persist`: the data is in `%APPDATA%\Trackhop`, outside
Scoop's folders, so updates and uninstalls leave it alone (the manifest's notes say so).

**Scoop Extras is not possible yet:** its rules ask for a "reasonably well-known and widely used"
project, *at least 100 stars and/or 50 forks*, the "latest stable version", and an issue (package
request) before any pull request. Trackhop is still far from 100 stars. So: our own bucket.

**Once:**

1. On [ScoopInstaller/BucketTemplate](https://github.com/ScoopInstaller/BucketTemplate), *Use this
   template › Create a new repository*: owner `xlollx`, name `scoop-bucket`, public.
2. In the new repository: *Settings › Actions › General*: allow all actions; workflow permissions
   *Read and write*. Replace the placeholder in `bin/auto-pr.ps1` with `xlollx/scoop-bucket`.
3. Copy `packaging/scoop/trackhop.json` to `bucket/trackhop.json`, commit, push. Add the topic
   `scoop-bucket` to be listed on scoop.sh.
4. Try it on Windows: `scoop bucket add xlollx https://github.com/xlollx/scoop-bucket`,
   `scoop install trackhop`; `bin/checkver.ps1 trackhop` in the bucket prints the version found.

**Each release:** the bucket's Excavator (every 4 hours) finds the new `server-v` tag, rewrites the
version, URL and hash and commits. `packaging/scoop/trackhop.json` here is only the starting copy.

---

## Microsoft Store (MSIX)

**In the repository.** On Windows, `desktop.yml` packs jpackage's app image as
`Trackhop-Desktop-v<version>-windows.msix` with MakeAppx from the runner's Windows SDK
(`msix/build.ps1`, manifest template `msix/AppxManifest.xml`, tiles in `msix/Assets/` made from
`docs/store/icon512.png` by `msix/assets.sh`) and adds it to the release. Capabilities:
`runFullTrust` (a desktop app) and `internetClient`.

- **Unsigned on purpose.** Microsoft: packages submitted to the Store "don't have to be signed with a
  certificate rooted in a trusted certificate authority"; the Store re-signs them with its own
  certificate after certification
  ([App package requirements](https://learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msix/app-package-requirements)).
  So no certificate to buy. An unsigned MSIX cannot be installed by double-clicking (the release
  notes say so); outside the Store, people use the MSI or the ZIP.
- **Version.** The Store wants the first number above 0 and keeps the fourth: Trackhop `x.y.z` is
  package `(x+1).y.z.0` (0.5.1 is 1.5.1.0, 1.0.0 will be 2.0.0.0), always increasing.
- **Identity.** Name, Publisher and Publisher display name must be exactly Partner Center's. The
  reserved name is **Trackhop Desktop** ("Trackhop" was taken), so the package's display name is
  that too; its identity is `xlollx.TrackhopDesktop`, publisher
  `CN=6C91EA3D-59AB-47BA-8ACB-5DF74CE63DDF`, display name `xlollx`. These are the defaults in
  `build.ps1` and `desktop.yml`; the repository variables `MSIX_IDENTITY_NAME`, `MSIX_PUBLISHER`,
  `MSIX_PUBLISHER_DISPLAY_NAME` override them if the account ever changes.
- **What changes under MSIX.** The app keeps working: it writes only under `%APPDATA%\Trackhop`,
  which Windows redirects for packaged apps into the package's own folder
  (`%LOCALAPPDATA%\Packages\<package>\LocalCache\Roaming\Trackhop`), removed when the app is
  uninstalled; the data of an MSI install is not shared. **The tray's "Start with the computer"
  does not work there**: it writes the Run key, which a packaged app only sees in its private copy
  of the registry. The manifest declares a startup task instead (off by default; the user turns it
  on in *Settings › Apps › Startup* or Task Manager), which starts `TrackhopStartup.exe`, the same
  launcher with `--background`. Inside the package (`jpackage.app-path` under `\WindowsApps\`) the
  tray and the window do not offer their own "Start with the computer".
  Port 8642 on 127.0.0.1 and WebView2 work as with the MSI (full-trust apps are not sandboxed).
- **Official connections only.** Inside the package Desktop leaves out the unofficial connectors
  (YouTube Music, Spotify and Deezer with cookies, SoundCloud links), as the app's Play build does:
  `installServerConnectors(officialOnly = true)`, decided by the same `\WindowsApps\` check.

**Once:**

1. Open a developer account at [storedeveloper.microsoft.com](https://storedeveloper.microsoft.com)
   (*Get started for free*; no fee for individual or company accounts; identity check with an ID).
2. In [Partner Center](https://partner.microsoft.com/dashboard/apps-and-games/overview): *New
   product › MSIX or PWA app*, reserve the name (done: **Trackhop Desktop**).
3. *Product management › Product identity*: check **Package/Identity/Name**,
   **Package/Identity/Publisher** (`CN=…`) and **Package/Properties/PublisherDisplayName** against
   the defaults in `build.ps1` and `desktop.yml` (or the repository variables that override them).
4. Build a package with them: *Actions › Desktop › Run workflow* on `main` (the `.msix` is in the
   *Trackhop-Desktop-Windows* artifact), or wait for the next release (it is a release file).
5. Create the submission:
   - *Pricing and availability*: free, markets.
   - *Properties*: category *Music* (or *Productivity*), privacy policy URL
     `https://trackhop.org/privacy-policy`, website, support contact
     (`https://github.com/xlollx/Trackhop/issues`).
   - *Age ratings*: the IARC questionnaire (no violence, no user interaction or sharing, no
     purchases, no ads in Desktop).
   - *Packages*: upload the `.msix`. Device family: Desktop.
   - *Store listings* (English, optionally Italian…): description, at least one screenshot of the
     Desktop window (1366×768 or larger), the 300×300 logo (from `docs/store/icon512.png`).
   - *Submission options › Restricted capabilities*: explain `runFullTrust`: "Trackhop Desktop is a
     Java desktop app (jpackage) with a tray icon and a local web server on 127.0.0.1; it needs full
     trust to run its own runtime."
6. Submit; certification takes a few days.

**Each release:** by hand: download the release's `.msix` and upload it in a new submission
(*Update*) in Partner Center. `msstore.yml` opens an issue assigned to the owner with the file and
these steps, so it is not forgotten. The API needs a Microsoft Entra application with the Manager
role, and an individual developer account does not offer one (07/11/2026: a tenant can be created
for free, but *User management* shows only *Users*, with no developer roles; see also
[windows-dev-docs#6153](https://github.com/MicrosoftDocs/windows-dev-docs/issues/6153)). With a
company account, once the four secrets exist it is automatic: `msstore.yml` (called by `desktop.yml`)
downloads the release's `.msix`, uploads it with the Microsoft Store Developer CLI
(`msstore publish <msix> -id 9PFX74Q2XN9P`) and submits it to certification. A draft waiting in Partner
Center is replaced (the CLI makes a new submission from the last published one), so edit the listing
between releases, or run the workflow by hand with *draft* ticked and finish it in Partner Center.
Without the secrets: download the release's `.msix` and upload it in a new submission (*Update*).

**Once, for the automatic updates** (Microsoft's
[GitHub Actions page](https://learn.microsoft.com/en-us/windows/apps/publish/msstore-dev-cli/github-actions)):

1. [Microsoft Entra admin center](https://entra.microsoft.com) › *Identity › Applications › App
   registrations › New registration*: name *Trackhop Store upload*, single tenant, no redirect URI.
   Copy its **Application (client) ID**, and from *Identity › Overview* the **Tenant ID**.
2. The app's *Certificates & secrets › Client secrets › New client secret* (24 months): copy the
   **Value** at once (it is not shown again).
3. [Partner Center](https://partner.microsoft.com/dashboard) › *Account settings › User management ›
   Microsoft Entra applications › Add Microsoft Entra application*: pick the app, role **Manager**.
   (If Partner Center asks to associate a tenant first, associate the same one.)
4. *Account settings › Legal info* (or *Developer settings*): the **Seller ID**.
5. GitHub secrets: `MSSTORE_TENANT_ID`, `MSSTORE_CLIENT_ID`, `MSSTORE_CLIENT_SECRET`, `MSSTORE_SELLER_ID`.
   The product ID defaults to `9PFX74Q2XN9P` (variable `MSSTORE_PRODUCT_ID` to change it).
6. Try it: *Actions › Microsoft Store › Run workflow*, the latest version, *draft* ticked; Partner
   Center then shows a new submission with that package.

---

## Google Play (the app)

**In the repository.** `play.yml` (called by `build.yml` after each app release) downloads the
release's `Trackhop-v<version>-play.aab` and uploads it with
[upload-google-play](https://github.com/r0adkll/upload-google-play) to the track named by the
variable `PLAY_TRACK` (default `alpha`), rolled out as `PLAY_STATUS` says (default `completed`; `draft`
leaves it for Play Console). The release notes are `play/whatsnew/<versionCode>/whatsnew-en-US` and
`whatsnew-it-IT`, written for Play: they never name what the Play build leaves out (the web
connectors and their plugin, F-Droid, Ko-fi…), which is why the F-Droid changelogs are not used.
Without notes for a version, it goes up without any.

**Once** (Google's [getting started](https://developers.google.com/android-publisher/getting_started)):

1. [Google Cloud Console](https://console.cloud.google.com/projectcreate): a project (any name, e.g.
   *trackhop-play*), then [enable the Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com).
2. *IAM & Admin › Service accounts › Create service account* (name *play-upload*, no roles), then the
   account's *Keys › Add key › Create new key › JSON*: a file downloads.
3. [Play Console › Users and permissions](https://play.google.com/console/users-and-permissions) ›
   *Invite new users*: the service account's e-mail (…@….iam.gserviceaccount.com), *App permissions ›
   Trackhop*: **Release to testing tracks** (and **Release to production** when it goes public), *Invite user*.
4. GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`: the whole JSON file's text. Variable `PLAY_TRACK` if the
   closed track is not `alpha` (Play Console › *Testing › Closed testing*: the track's name).
5. Try it: *Actions › Google Play › Run workflow* with the latest tag (a version Play already has is
   refused: fine for a test of the key, or wait for the next release).

**Each release:** automatic, with the notes written for it in `play/whatsnew/<versionCode>/`.

---

## Snap Store (Ubuntu)

**In the repository.** `packaging/snap/snapcraft.yaml` packs the Linux `.tar.gz` of a Desktop release
(jpackage's image, with its own Java) as a strict snap, with GTK 3 and WebKitGTK 4.1 for its window
from the `gnome` extension. Its data is in `$SNAP_USER_COMMON` (kept across updates); "Start with the
computer" works through the snap's `autostart` entry; the update notice says the Snap Store updates it.
`snap.yml` runs after each Desktop run on main that made a release (or by hand), keeps the `.snap` as an
artifact and, once the secret exists, publishes it to the stable channel.

**Once:**

1. An Ubuntu One account at [snapcraft.io](https://snapcraft.io/account) (sign in, accept the
   developer terms).
2. Register the name: [snapcraft.io/register-snap](https://snapcraft.io/register-snap), `trackhop`.
3. On a Linux machine with snapd (Ubuntu, or Ubuntu in WSL): `sudo snap install snapcraft --classic`,
   then
   `snapcraft export-login --snaps=trackhop --channels=stable --acls package_access,package_push,package_update,package_release trackhop-store.txt`
   (it asks for the Ubuntu One sign-in). Where snap does not work (Ubuntu in WSL), the same without
   snapcraft: `python3 packaging/snap/export-login.py` (Python 3 only, nothing to install).
4. GitHub secret `SNAPCRAFT_STORE_CREDENTIALS`: the whole text of `trackhop-store.txt`; then delete
   the file.
5. *Actions › Snap › Run workflow*, *Publish* ticked: the current version goes to the store. The first
   upload of a strict snap may wait for a manual review of its interfaces (a day or two).

**Each release:** automatic.

## Flathub

**In the repository.** `flatpak/`:

- `io.github.xlollx.Trackhop.yml`: the manifest. GNOME runtime 51 (it still has WebKitGTK 4.1,
  which SWT's browser needs on GTK 3), the OpenJDK 21 SDK extension for Java, Gradle 8.14.3 from its
  release archive, and a fully **offline build from source** (`offline.init.gradle` makes Gradle use
  only the Maven files listed in `gradle-sources.json`). Permissions: X11 and IPC (SWT and Java's
  AWT; AWT has no Wayland backend, Xwayland covers Wayland sessions), DRI (WebKit), network (the
  services, and the pages on 127.0.0.1:8642). No access to home: the data lives in
  `~/.var/app/io.github.xlollx.Trackhop/data/trackhop`.
- `gradle-sources.json`: the 123 Maven files the build needs, with sha512; `update-sources.sh`
  regenerates it (run it whenever a dependency or plugin version changes in `server/`,
  `android/core` or `android/ytm`).
- `io.github.xlollx.Trackhop.metainfo.xml` (passes `appstreamcli validate`),
  `io.github.xlollx.Trackhop.desktop` (passes `desktop-file-validate`), `trackhop.sh` (the
  launcher), `flathub.json` (x86_64 only: the build carries SWT for Linux x86_64 only).
- `.github/workflows/flatpak.yml` builds it in Flathub's GNOME 51 container on every change to
  these files or to the Gradle builds, and keeps a `.flatpak` bundle to try.

**Policy found** ([requirements](https://docs.flathub.org/docs/for-app-authors/requirements)):

- "All source available submissions must be built entirely from source code"; prebuilt binaries
  and `extra-data` are for non-redistributable apps (exceptions only for well-known vendors). So the
  release tarball is not an option: the manifest builds from source, offline ("There is no network
  access during the build process").
- The ID: `io.github.xlollx.Trackhop` matches the GitHub repository; the domain part is lowercase as
  required. Verification: sign in on flathub.org with the `xlollx` GitHub account.
- **Stable software only**: "The Flathub stable repository is dedicated to hosting only stable
  software" and "New application submissions will not be accepted directly into the Flathub beta
  repository". Desktop is out of beta since 1.0.0; its GitHub releases are still marked pre-releases
  only so that the repository's "latest" release stays the Android app (Obtainium): say so in the PR.
- "Simple web wrapper applications that embed local or remote content in a web engine without
  providing significant polish, functionality, or meaningful desktop integration will not be
  accepted." Trackhop has its own engine, scheduler, tray and API, but its window is a web view of
  its own pages: say so in the PR.
- Screenshots: the metainfo points at the Android app's screenshots (pinned to a commit) for now.
  **Replace them with Desktop windows captured on Linux** (with the window's title bar, not
  maximised, 1000×700 or smaller) before submitting.
- The icon fills its square to the edges; Flathub's quality guidelines prefer some margin (not a
  blocker, but it keeps it out of the featured lists).
- GNOME 52's runtime (March 2027) drops WebKitGTK 4.1: by then SWT needs GTK 4 (WebKitGTK 6), or the
  manifest has to build WebKitGTK 4.1 itself.

**Once:**

1. Release a version that contains `packaging/flatpak/` (the Flathub build reads the metainfo,
   the desktop file and the Gradle init script from the tagged sources). Add its `<release>` to the
   metainfo first.
2. Take Desktop screenshots on Linux, put them in the repository (for example `docs/store/desktop/`)
   and point the metainfo's `<image>` at them through a tag or commit URL.
3. Build and test locally:
   `flatpak install flathub org.flatpak.Builder` and
   `flatpak run org.flatpak.Builder --force-clean --user --install --install-deps-from=flathub build-dir packaging/flatpak/io.github.xlollx.Trackhop.yml`,
   then `flatpak run io.github.xlollx.Trackhop`; lint with
   `flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest packaging/flatpak/io.github.xlollx.Trackhop.yml`
   and `… appstream packaging/flatpak/io.github.xlollx.Trackhop.metainfo.xml`.
4. Fork [flathub/flathub](https://github.com/flathub/flathub) with "Copy the master branch only"
   **unticked**, then `git clone --branch=new-pr git@github.com:xlollx/flathub.git`,
   `git checkout -b add-trackhop new-pr`.
5. Add the manifest, `gradle-sources.json` and `flathub.json`, with the manifest's `type: dir`
   source replaced by the release tag:

   ```yaml
         - type: git
           url: https://github.com/xlollx/Trackhop.git
           tag: server-vX.Y.Z
           commit: <the tag's full commit hash>
           x-checker-data:
             type: git
             tag-pattern: ^server-v([\d.]+)$
   ```

6. Push and open the pull request against **`new-pr`** (not `master`), titled
   `Add io.github.xlollx.Trackhop`, keep the template and explain the permissions (X11 for AWT,
   network). Answer the review there, then comment `bot, build`.
7. Once accepted, Flathub creates `flathub/io.github.xlollx.Trackhop`; enable 2FA on GitHub and
   accept the invitation within a week. Then verify the app on flathub.org.

**Each release:** a pull request in `flathub/io.github.xlollx.Trackhop` with the new tag and commit
(Flathub's external data checker opens it by itself thanks to `x-checker-data`), plus a fresh
`gradle-sources.json` whenever dependencies changed, and the new `<release>` in the metainfo here.
