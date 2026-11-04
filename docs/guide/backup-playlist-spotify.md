---
title: Backup automatico delle playlist di Spotify (e di Apple Music, YouTube Music e altri)
description: Fai il backup di ogni playlist di Spotify, Apple Music, YouTube Music, TIDAL o Deezer e dei brani preferiti, in automatico, come file datati in una cartella che scegli tu. Gratis, open source.
lang: it
i18n: backup
---

# Backup automatico di tutte le tue playlist

Una playlist costruita in anni può sparire in un attimo: cancellata per sbaglio, svuotata da una
sync sbagliata, persa con un account a cui non riesci più ad accedere, o assottigliata dai brani che
escono dal catalogo. I servizi musicali non tengono una cronologia a cui tornare. **Trackhop** salva
ogni playlist e i brani preferiti come file datati, a mano o a orari fissi, in una cartella che
scegli tu. È gratis, open source e basta il servizio che già usi.

## Cosa viene salvato

- **Ogni playlist** di ciascun servizio collegato, quelle che hai creato e quelle che segui, più i
  **brani preferiti** e gli album e artisti salvati.
- Ogni brano con titolo, artisti, album, durata e, dove il servizio lo fornisce, il codice ISRC:
  quanto basta per ritrovare la stessa canzone ovunque.
- **Versioni datate**: ogni backup aggiunge una nuova versione delle playlist cambiate; si tengono
  le ultime 5 di ciascuna, e una playlist che non è cambiata non viene riscritta.
- Se vuoi, **tutta la configurazione di Trackhop** (sync, impostazioni, abbinamenti scelti) in un
  unico file `.trackhop`. Accessi e password non ci sono mai.

Sul servizio non cambia niente: un backup legge e basta.

## Come faccio il backup delle playlist di Spotify?

1. **Installa Trackhop** su Android ([download](/it/)), oppure [Trackhop Desktop](desktop) per
   Windows, macOS e Linux.
2. **Collega il tuo servizio**: *Account → Aggiungi connettore*, poi Spotify, Apple Music, YouTube
   Music, Amazon Music, TIDAL, Deezer, Qobuz, o il tuo Navidrome, Jellyfin, Emby o Plex. Accedi
   sulla pagina del servizio.
3. Apri *Strumenti → Backup e ripristino* e tocca **Fai ora il backup di tutte le playlist**. Ogni
   servizio collegato viene letto e salvato come file in *Playlist da file*.
4. In **In automatico**, imposta l'**Esecuzione automatica** su *Ogni giorno* o *Ogni settimana*.
5. Tocca **Backup in una cartella → Scegli una cartella**: una scheda SD, una cartella di Nextcloud
   o Drive, qualsiasi app che ne offra una. Dopo ogni backup i file nuovi e la configurazione del
   giorno vengono copiati lì, così sopravvivono anche se perdi il telefono.

La riga mostra la data dell'**ultimo backup**. Se la cartella non è più raggiungibile (scheda
tolta, accesso perso), Trackhop te lo dice e ti chiede di sceglierne di nuovo una.

## Come recupero una playlist cancellata?

1. Apri *Strumenti → Playlist da file → Importa file* e scegli il file della playlist dalla
   cartella dei backup (oppure usa la copia già presente in *Playlist da file*).
2. Crea una **Nuova sync** con quel file come **Origine** e il tuo servizio come **Destinazione**,
   con *Crea una nuova playlist*.
3. Avviala. Trackhop cerca ogni brano, prima per ISRC, e mette quelli incerti in una revisione da
   confermare.

Lo stesso file può andare su **qualsiasi** servizio: il backup delle playlist di Spotify è anche il
modo per portarle un giorno su YouTube Music, TIDAL o Apple Music, se cambi.

## Quali brani sono usciti dalla mia playlist?

Nella scheda **Scopri** c'è la pagina **Canzoni scomparse**: confronta i backup datati ed elenca i
brani che c'erano in una copia più vecchia di una playlist e mancano nella più recente, con un modo
rapido per cercarli di nuovo. Funziona solo se fai un backup ogni tanto: un motivo in più per
impostare l'esecuzione automatica.

## Apple Music, YouTube Music e gli altri

Il backup funziona allo stesso modo su ogni servizio a cui Trackhop si collega. Qualche nota:

- **YouTube Music** tramite l'API ufficiale di Google consuma parte della quota giornaliera per un
  backup completo: una volta a settimana basta e avanza.
- **Apple Music** richiede un abbonamento attivo perché le app possano leggere la libreria.
- **Il tuo server** (Navidrome, Jellyfin, Emby, Plex): le playlist sono già sul tuo disco, ma una
  copia datata le protegge comunque da una modifica sbagliata.

## In che formato sono i file?

I backup sono semplici file **CSV**, uno per playlist, che si aprono con qualsiasi foglio di
calcolo. Per avere una singola playlist in **M3U**, **XSPF**, **JSPF** o testo semplice usa
*Strumenti → Esporta una playlist*; vedi [Export a playlist to CSV or M3U](export-playlist-csv)
(in inglese).

## Senza telefono

[Trackhop Desktop](desktop) e il [server Docker](docker) hanno gli stessi backup completi, anche
programmati: comodi su un computer o un NAS sempre acceso.

## Altro che puoi fare con un solo servizio

- [Salvare Discover Weekly prima che cambi](salvare-discover-weekly)
- [Rimuovere i brani doppi da una playlist](rimuovere-duplicati-playlist)
- [Sistemare i brani in grigio](brani-non-riproducibili)
- [Il tuo Wrapped, quando vuoi](wrapped-quando-vuoi)

[Scarica Trackhop](/it/) · [Primi passi](iniziare) · [Tutte le pagine della guida](./)
