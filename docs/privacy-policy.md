# Trackhop – Privacy Policy / Informativa sulla privacy

_Last updated / Ultimo aggiornamento: 2026-11-05_

## English

_This policy covers the app **Trackhop** (called Songport up to version 1.1), published on GitHub, F-Droid and Google Play: the same app, with no ads and no advertising SDK in any version._

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
- **MusicBrainz lookups (only with a personal server as target)**: when a sync writes to Navidrome/Subsonic,
  Jellyfin, Emby or Plex, the app asks the public music database MusicBrainz (musicbrainz.org) which
  recordings a track's ISRC stands for, to find the file tagged with that recording. An anonymous request
  carrying the ISRC only: no account, token or playlist. Answers are kept on the device.
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
- **Playlist generator without a key (optional)**: when you use it, the artist names you type (or the
  most frequent artists of the playlist you pick) are sent to the public music databases MusicBrainz
  (musicbrainz.org) and ListenBrainz (api.listenbrainz.org, labs.api.listenbrainz.org), and, for artists
  ListenBrainz has no data on, to Deezer's public catalogue (api.deezer.com): anonymous lookups, no
  account. If you connected ListenBrainz with your token, that token goes along to ListenBrainz.
- **Concerts (optional)**: when you tap Find concerts (Discover › Concerts), the names of the artists
  of the playlist you picked and the country you chose are sent to Ticketmaster's Discovery API
  (app.ticketmaster.com, under Ticketmaster's own privacy policy) and to the public music database
  MusicBrainz (musicbrainz.org). Only when you ask, never in the background; no account, no location,
  nothing else about you. If you add your own Ticketmaster key it goes along to Ticketmaster and is
  kept encrypted on the device. In the GitHub and F-Droid builds (and on Docker and Desktop), when
  the country is Italy, the artist names also go to Vivaticket (apigatewayb2cstore.vivaticket.com,
  read unofficially, under Vivaticket's own privacy policy). In the Android app, when the country is
  Italy and the app can open TicketOne's pages, the artist names are searched on TicketOne
  (www.ticketone.it) in a web view the app opens out of sight on your phone, like your own browser
  would (read unofficially, it may stop working): TicketOne sees your phone's connection and keeps its
  own cookies in that web view (only the necessary ones when it asks), under TicketOne's own privacy
  policy. No account, no sign-in, nothing typed but the artist's name. Ticket and search links open in your browser.
  The concerts' pictures and the records' covers are loaded from Ticketmaster's, Vivaticket's,
  TicketOne's and Discogs' image servers, like any web picture (turn them off in Settings › Images in Discover).
- **Setlists (optional)**: when you tap Find setlists (Discover › Setlists), the names of the artists
  of the playlist you picked are sent to the setlist.fm API (api.setlist.fm, under setlist.fm's own
  privacy policy); a setlist.fm link you share or paste sends its setlist's id (or, without a key,
  opens its public page). Only when you ask, never in the background; no account, no location,
  nothing else about you. If you add your own setlist.fm key it goes along to setlist.fm and is kept
  encrypted on the device.
- **Records and merch (optional)**: when you tap Find records (Discover › Records and merch), the names
  of the artists of the playlist you picked are sent to the Discogs API (api.discogs.com, under Discogs'
  own privacy policy) and to MusicBrainz (musicbrainz.org). Only when you ask, never in the background;
  nothing else about you. If you add your own Discogs token it goes along to Discogs and is kept
  encrypted on the device. For the records and merch sold in their own shops, the app also asks the
  artist's own shops that MusicBrainz lists (an official shop on Shopify, the official site, the
  artist's Bandcamp) for their public product list: their robots.txt, `/products.json` and
  `/meta.json` on a Shopify shop, `/merch` on Bandcamp. Those shops see your phone's connection and
  that a product list was asked, like any visit; no account, no cookie, nothing about you. Product
  pictures load from the shops' image servers (cdn.shopify.com, bcbits.com), like any web picture
  (turn them off in Settings › Images in Discover). Shop, Discogs and official-site links open in your
  browser, without any affiliate tag.
- **Artist alerts (optional)**: for the artists you follow in Discover (Followed artists), the app asks
  the same services as New releases, Concerts and Records and merch once a day (or a week), in the
  background: Deezer, MusicBrainz, Discogs, the artists' own shops, Ticketmaster only with your own key,
  and the country you last searched in Concerts. Only the names of the artists you follow are sent;
  what was found stays on the device. Turn it off in Settings or unfollow the artists.
- **Radio syncs (optional)**: to find a station by name, the name you type is sent to the public
  radio directory radio-browser.info (anonymous, no account). While a radio sync's window is open, the
  app briefly opens the station's stream every 2 minutes to read the title of the song on air, as any
  radio player would; the audio is not kept. The songs heard are saved in a playlist file on the device.
- **Pictures from trackhop.org**: when no AI is set up, the Roast page shows a photo from
  trackhop.org (a short list and the picture, asked at most once a day and kept on the device). It is an
  anonymous request for a picture: nothing about you, your accounts or your playlists is sent.
- **Connections log**: the app keeps, on the device, the list of host names it has contacted and how
  many times (Settings › Developer options › Show connections), so you can verify the above yourself. Only host
  names, never content; you can clear it at any time.
- **On-device AI (Gemini Nano, optional)**: on phones that have it, the Roast and the playlist generator can use
  Google's Gemini Nano through Android's AICore and Google's ML Kit library. The text it works on (playlist name,
  artists, titles) is processed on the phone and is not sent anywhere. ML Kit may send Google technical usage
  data (for example which feature ran and whether it worked, not your playlists), under Google's terms:
  https://developers.google.com/ml-kit/terms.

**What we do NOT do**: no Trackhop account, no developer servers, no collection of e-mail,
contacts, location or files; no ads and no advertising ID; no sale of data.

**Third parties**: requests go directly to the official APIs of the connected services (Spotify,
Apple, Google/YouTube, TIDAL, Deezer) under their own policies. Use of the YouTube API is subject to the
YouTube API Terms of Service (https://www.youtube.com/t/terms) and Google's Privacy Policy
(https://policies.google.com/privacy); you can revoke access at https://myaccount.google.com/permissions.

**Your rights**: revoke access at any time from the services or by uninstalling the app.
Questions: contact on the Play Store listing.

**Children**: the app is not intended for children under 16.

## Italiano

_Questa informativa riguarda l'app **Trackhop** (chiamata Songport fino alla versione 1.1), pubblicata su GitHub, F-Droid e Google Play: la stessa app, senza pubblicità né SDK pubblicitari in nessuna versione._

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
- **Ricerche su MusicBrainz (solo con un server personale come destinazione)**: quando una sync scrive su
  Navidrome/Subsonic, Jellyfin, Emby o Plex, l'app chiede al database musicale pubblico MusicBrainz
  (musicbrainz.org) a quali registrazioni corrisponde l'ISRC di un brano, per trovare il file con quel tag.
  È una richiesta anonima con il solo ISRC: niente account, token o playlist. Le risposte restano sul dispositivo.
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
- **Generatore di playlist senza chiave (facoltativo)**: quando lo usi, i nomi degli artisti che scrivi (o
  gli artisti più presenti nella playlist che scegli) vengono inviati ai database musicali pubblici
  MusicBrainz (musicbrainz.org) e ListenBrainz (api.listenbrainz.org, labs.api.listenbrainz.org) e, per gli
  artisti di cui ListenBrainz non ha dati, al catalogo pubblico di Deezer (api.deezer.com): ricerche
  anonime, senza account. Se hai collegato ListenBrainz con il tuo token, quel token viene inviato a ListenBrainz.
- **Concerti (facoltativo)**: quando tocchi Cerca concerti (Scopri › Concerti), i nomi degli artisti
  della playlist scelta e il paese indicato vengono inviati alla Discovery API di Ticketmaster
  (app.ticketmaster.com, secondo l'informativa di Ticketmaster) e al database musicale pubblico
  MusicBrainz (musicbrainz.org). Solo quando lo chiedi, mai in background; nessun account, nessuna
  posizione, nient'altro su di te. Se aggiungi la tua chiave Ticketmaster, viene inviata a Ticketmaster
  e resta cifrata sul dispositivo. Nell'app Android, quando il paese è l'Italia e l'app può aprire le
  pagine di TicketOne, i nomi degli artisti vengono cercati su TicketOne (www.ticketone.it) in una
  pagina web che l'app apre in background sul telefono, come farebbe il tuo browser (lettura non
  ufficiale, potrebbe smettere di funzionare): TicketOne vede la connessione del telefono e tiene i
  propri cookie in quella pagina (solo quelli necessari quando lo chiede), secondo l'informativa di
  TicketOne. Nessun account, nessun accesso, nient'altro che il nome dell'artista. I link ai biglietti
  e alle ricerche si aprono nel browser. Le foto dei concerti e le copertine dei dischi arrivano dai
  server di immagini di Ticketmaster, TicketOne e Discogs, come
  ogni immagine web (si spengono in Opzioni › Immagini in Scopri).
- **Scalette (facoltativo)**: quando tocchi Cerca scalette (Scopri › Scalette), i nomi degli artisti
  della playlist scelta vengono inviati all'API di setlist.fm (api.setlist.fm, secondo l'informativa di
  setlist.fm); un link di setlist.fm che condividi o incolli invia l'id della sua scaletta (o, senza
  chiave, apre la sua pagina pubblica). Solo quando lo chiedi, mai in background; nessun account,
  nessuna posizione, nient'altro su di te. Se aggiungi la tua chiave setlist.fm, viene inviata a
  setlist.fm e resta cifrata sul dispositivo.
- **Dischi e merch (facoltativo)**: quando tocchi Cerca dischi (Scopri › Dischi e merch), i nomi degli
  artisti della playlist scelta vengono inviati all'API di Discogs (api.discogs.com, secondo
  l'informativa di Discogs) e a MusicBrainz (musicbrainz.org). Solo quando lo chiedi, mai in background;
  nient'altro su di te. Se aggiungi il tuo token Discogs, viene inviato a Discogs e resta cifrato sul
  dispositivo. Per i dischi e il merch venduti nei loro negozi, l'app chiede anche ai negozi
  dell'artista elencati da MusicBrainz (un negozio ufficiale su Shopify, il sito ufficiale, il Bandcamp
  dell'artista) il loro elenco pubblico di prodotti: robots.txt, `/products.json` e `/meta.json` su un
  negozio Shopify, `/merch` su Bandcamp. Quei negozi vedono la connessione del telefono e che è stato
  chiesto l'elenco, come per ogni visita; nessun account, nessun cookie, niente su di te. Le foto dei
  prodotti arrivano dai server di immagini dei negozi (cdn.shopify.com, bcbits.com), come ogni immagine
  web (si spengono in Opzioni › Immagini in Scopri). I link a negozi, Discogs e siti ufficiali si aprono
  nel browser, senza codici di affiliazione.
- **Avvisi per artista (facoltativi)**: per gli artisti che segui in Scopri (Artisti seguiti), l'app
  chiede agli stessi servizi di Nuove uscite, Concerti e Dischi e merch una volta al giorno (o alla
  settimana), in background: Deezer, MusicBrainz, Discogs, i negozi degli artisti, Ticketmaster solo con
  la tua chiave, e il paese dell'ultima ricerca in Concerti. Vengono inviati solo i nomi degli artisti
  che segui; quello che si trova resta sul dispositivo. Si spengono in Opzioni o smettendo di seguirli.
- **Sync da radio (facoltative)**: per cercare una stazione per nome, il nome che scrivi viene inviato
  all'elenco pubblico di radio radio-browser.info (in forma anonima, senza account). Mentre la finestra di
  una sync da radio è aperta, l'app apre per qualche secondo il flusso della stazione ogni 2 minuti per
  leggere il titolo della canzone in onda, come farebbe qualsiasi lettore radio; l'audio non viene
  conservato. Le canzoni ascoltate sono salvate in un file playlist sul dispositivo.
- **Immagini da trackhop.org**: senza un'AI impostata, la pagina del Roast mostra una foto presa da
  trackhop.org (un breve elenco e l'immagine, chiesti al massimo una volta al giorno e conservati sul
  dispositivo). È una richiesta anonima di un'immagine: non viene inviato nulla su di te, sui tuoi account o
  sulle tue playlist.
- **Registro delle connessioni**: l'app conserva, sul dispositivo, l'elenco degli host che ha contattato e quante
  volte (Impostazioni › Opzioni sviluppatore › Mostra le connessioni), così puoi verificare tu stesso quanto sopra. Solo nomi
  di host, mai contenuti; puoi svuotarlo quando vuoi.
- **AI sul telefono (Gemini Nano, facoltativa)**: sui telefoni che lo hanno, il Roast e il generatore di playlist
  possono usare Gemini Nano di Google tramite AICore di Android e la libreria ML Kit di Google. Il testo su cui
  lavora (nome della playlist, artisti, titoli) è elaborato sul telefono e non viene inviato altrove. ML Kit può
  inviare a Google dati tecnici d'uso (per esempio quale funzione è stata usata e se ha funzionato, non le tue
  playlist), secondo i termini di Google: https://developers.google.com/ml-kit/terms.

**Cosa NON facciamo**: nessun account Trackhop, nessun server dello sviluppatore, nessuna
raccolta di e-mail, contatti, posizione o file; nessuna pubblicità e nessun identificatore pubblicitario; nessuna vendita di dati.

**Terze parti**: le richieste vanno direttamente alle API ufficiali dei servizi collegati (Spotify,
Apple, Google/YouTube, TIDAL, Deezer), soggette alle rispettive informative. L'uso dell'API di YouTube è
soggetto ai Termini di servizio delle API YouTube (https://www.youtube.com/t/terms) e all'informativa
Google (https://policies.google.com/privacy); l'utente può revocare l'accesso da
https://myaccount.google.com/permissions.

**Diritti**: l'utente può revocare l'accesso in qualsiasi momento dai servizi o disinstallando l'app.
Per domande: contatto nella scheda Play Store.

**Minori**: l'app non è destinata a minori di 16 anni.
