# Trackhop – Privacy Policy / Informativa sulla privacy

_Last updated / Ultimo aggiornamento: 2026-10-29_

## English

_This policy covers the app **Trackhop** (called Songport up to version 1.1), published on GitHub, F-Droid and Google Play: the same app, with the one difference described under "Advertising data"._

**Controller**: the independent developer of Trackhop (contact on the Google Play listing).

**What the app does**: syncs playlists between music services you connect (Spotify, Apple Music, YouTube Music,
TIDAL, Deezer) and local files. All processing happens **on your device**.

**Data processed**
- **Web sessions** of the built-in web connectors (YouTube Music, Amazon Music, Spotify and Apple Music
  web sign-in; GitHub and F-Droid version only): the cookies set by the service's own sign-in page,
  stored **only on the device**, encrypted, used only towards that service. Signing out deletes them.
- **Access tokens** for the connected music services (OAuth), stored **only on the device**, encrypted
  with the Android Keystore, and used solely to read and modify your playlists at your request. They
  are never sent to the developer or third parties. Remove them with "Disconnect" or by uninstalling.
- **Personal server credentials** (Subsonic/Navidrome, Jellyfin, Plex) and Last.fm/ListenBrainz **usernames** entered by the user: stored encrypted on the device only, used only towards the server you specify.
- **Playlist content** (titles, artists, ISRC) read from the connected services to run syncs, plus a
  local match cache. Stays on the device.
- **Exact recording lookups (optional, on by default)**: for a track without an ISRC, the app asks Deezer's
  public catalogue (api.deezer.com) for the recording's ISRC: an anonymous search by title and artist, no
  account or token. Off in Settings › General.
- **Spotify web connector (GitHub and F-Droid version)**: to talk to Spotify's web player backend the app
  reads the player's public JavaScript and, at most every six hours, a public list of the player's
  query identifiers hosted on GitHub (raw.githubusercontent.com). Both are anonymous downloads: no
  account data, token or playlist is sent with them.
- **Update check**: the version downloaded from GitHub asks GitHub's public API (api.github.com), at
  most twice a day, which is the latest Trackhop release, to show an arrow at the top when a newer one
  exists. It is an anonymous request: no account, token, playlist or device identifier is sent. It is not
  made when F-Droid or Obtainium installed the app, and it can be turned off in Settings › About. The
  Google Play version asks Google Play instead, through Google Play's own update service.
- **Playlist generator (optional)**: if you connect your own AI account (OpenAI, Anthropic, Google Gemini
  or a compatible server you choose), the description you type, the optional reference tracks and the
  number of tracks are sent to that provider, under its own policy; your API key is stored encrypted on
  the device only. Nothing else (playlists, tokens) is sent. The feature is off until you add a key.
- **Radio syncs (optional)**: to find a station by name, the name you type is sent to the public
  radio directory radio-browser.info (anonymous, no account). While a radio sync's window is open, the
  app briefly opens the station's stream every 2 minutes to read the title of the song on air, as any
  radio player would; the audio is not kept. The songs heard are saved in a playlist file on the device.
- **Connections log**: the app keeps, on the device, the list of host names it has contacted and how
  many times (Settings › Developer options › Show connections), so you can verify the above yourself. Only host
  names, never content; you can clear it at any time.
- **Advertising data** (Google Play version only; the version downloaded from GitHub contains no
  advertising SDK and collects no advertising data): the app shows one banner via Google AdMob. The Google Mobile Ads SDK may collect
  the advertising ID, IP address, device and app information to serve and measure ads, according to the
  consent you give in the GDPR form (Google UMP). See https://policies.google.com/technologies/partner-sites.
  You can change your choice in *Settings › Ad privacy options*.

**What we do NOT do**: no Trackhop account, no developer servers, no collection of e-mail,
contacts, location or files; no sale of data.

**Third parties**: requests go directly to the official APIs of the connected services (Spotify,
Apple, Google/YouTube, TIDAL, Deezer) under their own policies. Use of the YouTube API is subject to the
YouTube API Terms of Service (https://www.youtube.com/t/terms) and Google's Privacy Policy
(https://policies.google.com/privacy); you can revoke access at https://myaccount.google.com/permissions.

**Your rights**: revoke access at any time from the services or by uninstalling the app.
Questions: contact on the Play Store listing.

**Children**: the app is not intended for children under 16.

## Italiano

_Questa informativa riguarda l'app **Trackhop** (chiamata Songport fino alla versione 1.1), pubblicata su GitHub, F-Droid e Google Play: la stessa app, con l'unica differenza descritta in "Dati pubblicitari"._

**Titolare**: lo sviluppatore indipendente dell'app Trackhop (contatti nella scheda Google Play).

**Cosa fa l'app**: sincronizza playlist tra i servizi musicali che l'utente collega (Spotify, Apple Music,
YouTube Music, TIDAL, Deezer) e file locali. Tutta l'elaborazione avviene **sul dispositivo**.

**Dati trattati**
- **Sessioni web** dei connettori web integrati (YouTube Music, Amazon Music, accesso web a Spotify e
  Apple Music; solo versione GitHub e F-Droid): i cookie impostati dalla pagina di accesso del servizio,
  salvati **solo sul dispositivo**, cifrati, usati solo verso quel servizio. Uscendo vengono cancellati.
- **Token di accesso** ai servizi musicali collegati (OAuth). Sono salvati **solo sul dispositivo**, cifrati
  con l'Android Keystore, e usati esclusivamente per leggere e modificare le playlist dell'utente su sua
  richiesta. Non vengono mai inviati allo sviluppatore né a terzi. Si eliminano con "Scollega" o
  disinstallando l'app.
- **Credenziali dei server personali** (Subsonic/Navidrome, Jellyfin, Plex) e **nomi utente** Last.fm/ListenBrainz inseriti dall'utente: salvati cifrati solo sul dispositivo, usati solo verso il server indicato.
- **Contenuto delle playlist** (titoli, artisti, ISRC) letto dai servizi collegati per eseguire le sync e
  una cache locale degli abbinamenti. Restano sul dispositivo.
- **Controllo aggiornamenti**: la versione scaricata da GitHub chiede all'API pubblica di GitHub
  (api.github.com), al massimo due volte al giorno, qual è l'ultima versione di Trackhop, per mostrare una
  freccia in alto quando ne esiste una più recente. È una richiesta anonima: non vengono inviati account,
  token, playlist o identificativi del dispositivo. Non avviene se l'app è stata installata da F-Droid o
  Obtainium e si può spegnere in Impostazioni › Informazioni. La versione Google Play lo chiede invece a
  Google Play, tramite il servizio di aggiornamento di Google Play.
- **Generatore di playlist (facoltativo)**: se colleghi il tuo account AI (OpenAI, Anthropic, Google Gemini
  o un server compatibile a tua scelta), la descrizione che scrivi, gli eventuali brani di riferimento e il
  numero di brani vengono inviati a quel fornitore, secondo la sua informativa; la chiave API resta cifrata
  solo sul dispositivo. Nient'altro (playlist, token) viene inviato. La funzione è spenta finché non aggiungi una chiave.
- **Sync da radio (facoltative)**: per cercare una stazione per nome, il nome che scrivi viene inviato
  all'elenco pubblico di radio radio-browser.info (in forma anonima, senza account). Mentre la finestra di
  una sync da radio è aperta, l'app apre per qualche secondo il flusso della stazione ogni 2 minuti per
  leggere il titolo della canzone in onda, come farebbe qualsiasi lettore radio; l'audio non viene
  conservato. Le canzoni ascoltate sono salvate in un file playlist sul dispositivo.
- **Registro delle connessioni**: l'app conserva, sul dispositivo, l'elenco degli host che ha contattato e quante
  volte (Impostazioni › Opzioni sviluppatore › Mostra le connessioni), così puoi verificare tu stesso quanto sopra. Solo nomi
  di host, mai contenuti; puoi svuotarlo quando vuoi.
- **Dati pubblicitari** (solo nella versione Google Play; la versione scaricata da GitHub non contiene
  alcun SDK pubblicitario e non raccoglie dati pubblicitari): l'app mostra un banner tramite Google AdMob. L'SDK Google Mobile Ads può
  raccogliere l'identificatore pubblicitario, indirizzo IP, informazioni sul dispositivo e sull'app per
  fornire e misurare gli annunci, in base al consenso espresso tramite il modulo GDPR (Google UMP).
  Informativa Google: https://policies.google.com/technologies/partner-sites.
  Il consenso può essere modificato in *Impostazioni › Opzioni privacy annunci*.

**Cosa NON facciamo**: nessun account Trackhop, nessun server dello sviluppatore, nessuna
raccolta di e-mail, contatti, posizione o file; nessuna vendita di dati.

**Terze parti**: le richieste vanno direttamente alle API ufficiali dei servizi collegati (Spotify,
Apple, Google/YouTube, TIDAL, Deezer), soggette alle rispettive informative. L'uso dell'API di YouTube è
soggetto ai Termini di servizio delle API YouTube (https://www.youtube.com/t/terms) e all'informativa
Google (https://policies.google.com/privacy); l'utente può revocare l'accesso da
https://myaccount.google.com/permissions.

**Diritti**: l'utente può revocare l'accesso in qualsiasi momento dai servizi o disinstallando l'app.
Per domande: contatto nella scheda Play Store.

**Minori**: l'app non è destinata a minori di 16 anni.
