# Google Play listing – texts, answers and checklist

The app is **Trackhop** on Google Play as everywhere else. Same code,
same name, same package id `com.xlollx.songport`, same signing key as every other build: the `play`
flavor only drops the built-in web connectors and describes plugin-based services generically.
Upload **only the `play` AAB** (the
`Trackhop-release-aab` artifact of a CI run on `main`, or `Trackhop-vX-play.aab` in a GitHub Release (`Songport-vX-play.aab` up to 1.5.2)).

Rule for everything below: the listing describes official-API services and files only. No companion
app, no plugin, no link to one; screenshots taken from the `play` build on a phone without plugins.

## Store listing

### Title (max 30)
- EN: `Trackhop – playlist sync`
- IT: `Trackhop – sync playlist`

### Short description (max 80)
- EN: `Sync playlists across Spotify, Apple Music, YouTube Music, TIDAL and Deezer.`
- IT: `Playlist sincronizzate tra Spotify, Apple Music, YouTube Music, TIDAL e Deezer.`

### Full description (EN)
Keep your playlists identical across music services, without rebuilding them by hand.

- Pick a source playlist and a target one, or let the app create it with the same name
- Run the sync whenever you want, or schedule it: hourly, every 6 hours, daily or weekly
- Additions only, or mirror removals too; keep the source order where the service allows it
- Liked songs, saved albums, followed artists and podcasts as sources
- Paste the link of a public playlist, or share it from another app, and sync it into your library
- Preview before every sync: see what will be added or removed, then confirm
- Tracks that were not found are yours to fix: search the target service, pick the right one, the app remembers it
- Playlist generator: describe a playlist, your own AI (OpenAI, Anthropic, Google Gemini or any compatible server) proposes the tracks and the app checks they exist before anything is created; extend an existing playlist the same way
- Manage your playlists: rename, delete, make public or private, remove tracks by title, artist or album, remove duplicates
- Files: import and export CSV, M3U, Apple Music/iTunes XML and JSON; dated backups of every playlist, also to a folder of your choice
- Connection test for every account, and a review of every sync

Works with Spotify, Apple Music, YouTube Music, TIDAL, Deezer, personal Navidrome/Subsonic, Jellyfin
and Plex servers, Last.fm and ListenBrainz as sources, and files. Some connectors are marked beta in
the app: they work, but they have had less use than the others.

Free, no subscriptions, no in-app purchases, no ads.
No account to create and no server of ours: everything stays on your phone, access tokens are
encrypted with the Android Keystore and never pass through anyone else. You sign in on each
service's own page; for Spotify, YouTube Music, TIDAL and Deezer you create a free key in your own
name in about two minutes, and the app walks you through it.

Scheduled syncs run on your phone, without servers: if the phone is off or battery saving blocks
the app, they resume at the first opportunity.

Trackhop is not affiliated with Spotify, Apple, Google/YouTube, TIDAL or Deezer. Service names are
trademarks of their respective owners and are used only to indicate compatibility.

### Full description (IT)
Tieni le tue playlist uguali su tutti i servizi musicali, senza rifarle a mano.

- Scegli una playlist di origine e una di destinazione, o lasciala creare all'app con lo stesso nome
- Esegui la sincronizzazione quando vuoi, oppure programmala: ogni ora, 6 ore, giorno o settimana
- Solo aggiunte, oppure rispecchia anche le rimozioni; mantieni l'ordine dell'origine dove il servizio lo permette
- Brani preferiti, album salvati, artisti seguiti e podcast come origine
- Incolla il link di una playlist pubblica, o condividila da un'altra app, e sincronizzala nella tua libreria
- Anteprima prima di ogni sync: vedi cosa verrà aggiunto o tolto e confermi
- I brani non trovati li sistemi tu: cerchi sul servizio di destinazione, scegli quello giusto e l'app se lo ricorda
- Generatore di playlist: descrivi una playlist, la tua AI (OpenAI, Anthropic, Google Gemini o qualsiasi server compatibile) propone i brani e l'app verifica che esistano prima di creare qualcosa; allo stesso modo allunghi una playlist esistente
- Gestisci le tue playlist: rinomina, elimina, rendi pubblica o privata, rimuovi brani per titolo, artista o album, togli i duplicati
- File: import ed export CSV, M3U, XML di Apple Music/iTunes e JSON; backup datati di ogni playlist, anche in una cartella a tua scelta
- Prova del collegamento per ogni account, e revisione di ogni sync

Compatibile con Spotify, Apple Music, YouTube Music, TIDAL, Deezer, server personali
Navidrome/Subsonic, Jellyfin e Plex, Last.fm e ListenBrainz come sorgenti, e file. Alcuni connettori
sono indicati come beta nell'app: funzionano, ma sono stati usati meno degli altri.

Gratuita, senza abbonamenti, acquisti in-app né pubblicità. Nessun account da creare e nessun server nostro: tutto resta sul tuo telefono, i token
di accesso sono cifrati con l'Android Keystore e non passano da nessun altro. Accedi sulla pagina di
ogni servizio; per Spotify, YouTube Music, TIDAL e Deezer crei una chiave gratuita a tuo nome in
circa due minuti, e l'app ti guida passo passo.

Le sincronizzazioni programmate girano sul tuo telefono, senza server: se il telefono è spento o il
risparmio energetico blocca l'app, ripartono alla prima occasione utile.

Trackhop non è affiliata a Spotify, Apple, Google/YouTube, TIDAL o Deezer. I nomi dei servizi sono
marchi dei rispettivi proprietari e sono usati solo per indicare la compatibilità.

### Category and tags
Application · **Music & Audio** · tags: Music, Playlists, Productivity. Free app, no ads.

### Graphics
- Icon 512×512: `docs/store/icon512.png` (also `fastlane/metadata/android/en-US/images/icon.png`).
- Feature graphic 1024×500: `docs/store/feature_en.png`, `feature_it.png`.
- Phone screenshots: 2 to 8, JPEG/PNG, 320–3840 px per side, **long side at most twice the short
  one**. The Play build's own pictures are in `docs/store/play/<locale>/`, taken on an emulator by
  the Screenshots workflow with flavor `play` (only the services that build reaches, no ad). The
  F-Droid captures in `fastlane/` are taller than 2:1 and show services the Play build leaves out:
  not for Play. `scripts/play_screenshots.sh <files>` crops other pictures to 2:1 if needed.
- No service logos anywhere (icon, screenshots, feature graphic): names in text only.

## App content (Play Console › Policy › App content)

| Declaration | Answer |
|---|---|
| Privacy policy | URL of the published `docs/privacy-policy.md` (it names Trackhop and its former name, Songport). Same URL as the `PRIVACY_POLICY_URL` repository variable, which the app shows in Settings. |
| Ads | **No**, the app contains no ads. |
| App access | **All or some functionality is restricted**. Instructions for the reviewer: "Every feature can be exercised with playlist files (Files › Import, or share a text with one track per line) without any account. Music services require the reviewer's own account and, for Spotify, YouTube Music, TIDAL and Deezer, a free developer key created through the in-app wizard (about two minutes). No credentials can be supplied by the developer: the app has no accounts of its own." |
| Content rating (IARC) | Category **Utility, Productivity, Communication or Other**. Violence, sexuality, language, controlled substances: none. Users cannot interact with or share content with other users: **No**. Shares location: **No**. Digital purchases: **No**. Contains ads: **No**. Expected result: PEGI 3 / Everyone. |
| Target audience | **18 and over** only (optionally 16–17 too). Not designed for children; the app then needs no Families policy. "Could the store listing unintentionally appeal to children?" **No**. |
| News app | No. |
| COVID-19 contact tracing | No. |
| Data safety | See the next section. |
| Government app | No. |
| Financial features | None. |
| Health | None. |
| Advertising ID | **No**: no advertising SDK, and the manifest has no `com.google.android.gms.permission.AD_ID`. |
| Foreground service permissions | Type **`dataSync`** (`FOREGROUND_SERVICE_DATA_SYNC`). Task: "user-initiated and scheduled playlist synchronisation between music services; a sync of a large playlist can exceed the background work limits and would otherwise be killed half-way, leaving the target playlist inconsistent". Play asks for a short video showing the feature: record a sync from Syncs › Run (the notification appears) with any screen recorder and upload it unlisted on YouTube or Drive. |
| Photo and video permissions | Not applicable: files are chosen through the system picker (Storage Access Framework), no `READ_MEDIA_*` permission. |

Permissions in the manifest, for reference: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS
(asked on first use, not at start), WAKE_LOCK, USE_BIOMETRIC, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_DATA_SYNC.

## Data safety – answers

The app itself collects nothing: no account, no server, no analytics, no crash reporting, no ads.
The only declaration left comes from Google's ML Kit (Gemini Nano on the phone, 1.14+), which sends
Google technical usage data; check Google's ML Kit data disclosure when filling the form
(https://developers.google.com/ml-kit/android-data-disclosure), the list moves.

- Does your app collect or share any of the required user data types? **Yes** (ML Kit's diagnostics).
- Is all of the user data collected by your app encrypted in transit? **Yes**.
- Do you provide a way for users to request that their data is deleted? The developer holds no data
  at all: **No**, with that note.

| Data type | Collected | Shared | Purpose | Optional? | Processed ephemerally |
|---|---|---|---|---|---|
| App info and performance › Diagnostics (ML Kit) | yes | no | analytics | no | no |

Not collected: personal info, financial info, health, messages, photos, audio, files, contacts,
calendar, browsing history, search history, installed apps. Playlist contents, tokens and the AI key
stay on the device; the AI prompt goes only to the AI provider the user configured, which is a
service the user chose and signed up with, not a collection by the app.

## Release checklist

The order matters: a personal developer account cannot publish to production until a closed test
has run.

1. **Developer account** (play.google.com/console): one-time 25 USD fee, identity check with an ID
   document, phone and e-mail verification. The developer name and a contact e-mail are shown on the
   listing; the console says during sign-up which other details become public for your account type.
2. **Create the app**: name *Trackhop*, default language English (United States), App, Free. Declare
   it has no ads. Add Italian as a second listing language.
3. **Store listing**: texts above, icon, feature graphic, screenshots (see Graphics), category, contact
   e-mail, privacy policy URL.
4. **App content**: every row of the table above, in one sitting (the console blocks releases while
   any declaration is missing).
5. **Build to upload**: the `Trackhop-release-aab` artifact of a green CI run on `main` (flavor `play`,
   release, signed with the upload key from the `UPLOAD_KEYSTORE_*` secrets). Each upload must carry
   a higher `versionCode` than the last one accepted, so bump `android/app/build.gradle` before the
   next upload once a build has gone to any track (the F-Droid version keeps the same numbers).
   **Play App Signing** is proposed at the first upload: accept it; Google keeps the app signing key
   and the key in the secrets stays the upload key. Only if a shared `GOOGLE_CLIENT_ID` is shipped do
   the SHA-1s of both keys (Setup › App signing) need registering in the Google OAuth client.
6. **Closed testing**: create a closed track, upload the AAB, add testers as a Google Group, send
   them the opt-in link; the console states the current requirement (12 testers opted in for 14
   continuous days at the time of writing; it was 20 before). Recruiting texts, group setup and the
   final questionnaire are in `docs/play-testers.md`. Testers install from Play, so they must
   uninstall the GitHub/F-Droid build first: same package id.
7. **Apply for production access** when the test is complete (Dashboard › Apply for production): a few
   questions on who tested, what they found and how it was fixed. Google answers within a few days.
8. **Production release**: upload the AAB (a newer one if the version moved on), release notes
   (`fastlane/metadata/android/*/changelogs/<versionCode>.txt` is a good start), countries (all),
   roll out. First review takes from a few hours to a week.
9. **Later**: keep `docs/privacy-policy.md` online at the same URL; every release goes through CI the
    same way, and the AAB keeps the same upload key.

Repository settings already in place: `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`,
`UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` (the AAB artifact proves it), `DEBUG_KEYSTORE_BASE64`,
`PRIVACY_POLICY_URL`, `KOFI_URL`; optional `APPLE_DEVELOPER_TOKEN` (JWT, expires in 6 months) and
the service client ids, which users can replace with their own in the app.

Before the first production upload, tested on the `play` build: Spotify login with a user's own key, Apple Music login, manual
sync, scheduled sync (wait one hour), import from a public link, file import/export, playlist
generator with a free Gemini key.
