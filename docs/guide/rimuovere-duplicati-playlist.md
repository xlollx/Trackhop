---
title: Rimuovere i brani doppi dalle playlist di Spotify (e YouTube Music, TIDAL, Deezer)
description: Trova e rimuovi i brani duplicati in una playlist di Spotify, YouTube Music, TIDAL, Deezer o Amazon Music, anche quando le copie hanno ID diversi o vengono da un altro album. Gratis e open source.
lang: it
i18n: duplicates
---

# Rimuovere i brani doppi da una playlist

Aggiungi canzoni a una playlist abbastanza a lungo e lo stesso brano compare due volte: una
dall'album, una da un singolo o da una raccolta, una da un link di un amico. Per il servizio sono
due brani diversi, quindi non ti avvisa, e il suo controllo "già aggiunto" riconosce solo la stessa
identica copia. **Trackhop** trova i doppioni, te li mostra e toglie le copie in più in un colpo
solo. È gratis, open source e funziona dentro l'unico servizio che usi.

## Cosa conta come doppione?

Trackhop considera due voci la stessa canzone quando:

- sono **lo stesso brano**, aggiunto due volte;
- hanno lo stesso **ISRC**, il codice che identifica una registrazione, anche se una viene
  dall'album e l'altra da un "Best of";
- oppure hanno **lo stesso titolo e gli stessi artisti**, con una durata compatibile.

Una versione **live**, **acustica** o **remix** resta un brano a sé: "Throne" e "Throne – Live at
the Royal Albert Hall" non sono doppioni. In ogni gruppo resta la **prima copia** nella playlist e
le altre vengono tolte, quindi l'ordine della playlist non cambia.

## Come rimuovo i doppioni da una playlist di Spotify?

1. **Installa Trackhop** su Android ([download](/it/)), oppure [Trackhop Desktop](desktop) per
   Windows, macOS o Linux.
2. **Collega Spotify**: *Account → Aggiungi connettore → Spotify*, accedendo sulla pagina di
   Spotify.
3. Apri **Strumenti**. In alto, in *Servizio per gli strumenti*, scegli Spotify.
4. In **Pulizia** tocca **Rimuovi duplicati**.
5. Scegli la playlist e tocca **Cerca duplicati**. Trackhop la legge ed elenca ogni gruppo: la
   copia che resta e quelle che andrebbero via.
6. Controlla l'elenco, poi tocca **Rimuovi *n* brani**. Fatto.

Non viene tolto niente prima di quel tasto, e compaiono solo le playlist tue (una playlist creata da
qualcun altro non può essere modificata da nessuna app).

## YouTube Music, TIDAL, Deezer, Amazon Music e il tuo server

Lo stesso strumento funziona su ogni servizio che permette alle app di togliere brani da una
playlist: Spotify, YouTube Music, TIDAL, Deezer, Amazon Music, Qobuz, Navidrome e gli altri server
Subsonic, Jellyfin, Emby e Plex. Scegli il servizio in cima a *Strumenti* e segui gli stessi passi.

## E su Apple Music?

Apple non permette alle app di togliere brani da una playlist, quindi lì *Rimuovi duplicati* è
disattivato. Puoi comunque ottenere una copia pulita: *Strumenti → Nuova playlist da… → Copia*,
scegli la playlist e spunta **Salta i doppi**. Trackhop crea una nuova playlist con una sola copia
di ogni brano; se vuoi, poi elimini la vecchia dall'app Musica.

## Unire playlist senza creare nuovi doppioni

Per mettere insieme più playlist: *Strumenti → Nuova playlist da… → Unisci* con **Salta i doppi**
spuntato. Ottieni una playlist con una sola copia di ogni brano, e le originali restano intatte.
Altri dettagli in [Merge playlists and remove duplicates](merge-playlists-remove-duplicates) (in
inglese).

## La stessa canzone in più playlist

I doppioni dentro una playlist sono una cosa; la stessa canzone sparsa in dieci playlist è un'altra.
**Ripetuti tra playlist** (sempre in *Strumenti*) legge tutte le tue playlist una volta e mostra i
brani che stanno in più di una, e i brani preferiti che non sono in nessuna. Non cambia niente: te
lo dice e basta.

## Posso tornare indietro?

Rimuovere i doppioni toglie solo le copie in più, e la prima di ogni brano resta: la musica è
ancora nella playlist. Se vuoi comunque una rete di sicurezza, tocca **Fai ora il backup di tutte
le playlist** in *Strumenti → Backup e ripristino* prima di iniziare: il backup tiene la playlist
com'era, pronta da ripristinare. Vedi [Backup automatico delle playlist](backup-playlist-spotify).

## Altro che puoi fare con un solo servizio

- [Sistemare i brani in grigio](brani-non-riproducibili), l'altra metà di *Pulizia*
- [Salvare Discover Weekly prima che cambi](salvare-discover-weekly)
- [Backup automatico di tutte le playlist](backup-playlist-spotify)
- [Il tuo Wrapped, quando vuoi](wrapped-quando-vuoi)

[Scarica Trackhop](/it/) · [Primi passi](iniziare) · [Tutte le pagine della guida](./)
