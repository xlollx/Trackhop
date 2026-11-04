---
title: Salvare Discover Weekly, Release Radar e daylist di Spotify prima che cambino
description: Tieni i brani di Discover Weekly, Release Radar o della tua daylist prima che Spotify li sostituisca. Un'app gratuita li copia in una tua playlist, ogni settimana, in automatico.
lang: it
i18n: discover-weekly
---

# Salvare Discover Weekly prima che cambi

Discover Weekly sparisce ogni lunedì. Release Radar viene sostituita ogni venerdì e la daylist
cambia più volte al giorno. Se non hai salvato in tempo i brani che ti piacevano, se ne vanno con
lei. **Trackhop** copia queste playlist in una playlist tutta tua, a mano o a orari fissi, così non
perdi niente. È gratis e open source e funziona con un solo account Spotify: non serve un secondo
servizio.

## Due modi per tenerle

| Vuoi | Imposta la sync su | Ottieni |
|---|---|---|
| **Tutti i brani proposti**, che si accumulano settimana dopo settimana | *Solo aggiunte* (l'impostazione predefinita) | Una playlist che cresce e basta: ogni esecuzione aggiunge i brani nuovi, non si cancella mai nulla |
| **Una copia che la segue**, sullo stesso servizio o su un altro | *Rispecchia le rimozioni* + *Playlist che cambia da sola* | La destinazione diventa ogni volta la nuova versione, con il suo titolo e, su Spotify, la sua copertina |

Quasi sempre si vuole il primo: un archivio di tutto quello che Discover Weekly ti ha mai proposto.

## Come salvo Discover Weekly in automatico?

1. **Installa Trackhop** su Android ([download](/it/)), oppure usa [Trackhop Desktop](desktop) su
   Windows, macOS o Linux.
2. **Collega Spotify con l'accesso rapido**: *Account → Aggiungi connettore → Spotify (web)*, e
   accedi sulla pagina di Spotify con il tuo solito account.

   > **Perché "Spotify (web)" e non l'altro collegamento Spotify?** Trackhop ha due modi per
   > raggiungere Spotify. Quello ufficiale, con una tua chiave sviluppatore gratuita, non può più
   > leggere le playlist che Spotify crea per te (Discover Weekly, Release Radar, daylist): Spotify
   > non lo permette più da febbraio 2026. Con quel collegamento quelle playlist risultano vuote o
   > non compaiono: è una regola di Spotify, non un errore di Trackhop. L'accesso rapido passa dal
   > web player di Spotify e le vede. Non è un'interfaccia ufficiale, quindi leggi prima
   > [le sue note](troubleshooting#spotify) (in inglese). Nella versione Google Play arriva con il
   > plugin gratuito [Trackhop Bridge](https://github.com/xlollx/Trackhop-Bridge/releases/latest).
3. **Nuova sync**: *Sync → Nuova sync*. Come **Origine** scegli *Discover Weekly* tra le tue
   playlist Spotify.
4. Come **Destinazione** scegli di nuovo Spotify e *Crea una nuova playlist*, per esempio
   "Archivio Discover Weekly". Origine e destinazione possono stare sullo stesso servizio.
5. **Lascia spento *Rispecchia le rimozioni*.** È la modalità *solo aggiunte*: l'archivio tiene
   ogni brano, anche dopo che Discover Weekly lo ha tolto.
6. **Esecuzione automatica**: *Ogni settimana*, oppure *In giorni scelti* con il martedì e un orario
   a tua scelta, così l'esecuzione arriva dopo l'aggiornamento del lunedì.
7. **Salva**, controlla l'anteprima e tocca **Esegui ora** per la prima copia. Da lì in poi va da
   sola.

<!-- update when archive mode ships -->

Ogni settimana la scheda della sync mostra quanti brani sono stati aggiunti. I brani già presenti
nell'archivio non vengono aggiunti due volte.

## Release Radar e daylist

La stessa sync funziona per qualsiasi playlist che si rinnova da sola:

- **Release Radar** si aggiorna il venerdì: scegli *In giorni scelti* con il sabato.
- **La daylist** cambia durante la giornata: *Ogni 6 ore* ne prende quasi tutti i momenti, *Ogni
  ora* praticamente tutti. In modalità solo aggiunte ottieni un'unica lunga playlist di tutte le
  daylist.
- **Daily Mix, On Repeat**: stessi passi, con l'orario che preferisci.

## Una copia che la segue, titolo e copertina compresi

Se invece vuoi avere anche la Discover Weekly di questa settimana come playlist tua (per
condividerla o ascoltarla su un altro servizio), accendi **Rispecchia le rimozioni**. Compare un
secondo interruttore: **Playlist che cambia da sola**. Con questo acceso la destinazione diventa
ogni volta la nuova versione della sorgente, anche se cambiano quasi tutti i brani, senza conferme
da dare. Il nuovo titolo di una daylist la segue, e su Spotify anche la nuova copertina, a meno che
tu non abbia dato alla destinazione un nome tuo.

Senza quell'interruttore, Trackhop considera una playlist che perde quasi tutti i brani come un
possibile errore e trattiene le rimozioni finché non le confermi: giusto per le tue playlist, non
per Discover Weekly.

## Posso tenere una playlist per ogni settimana?

Sì, a mano: *Strumenti → Nuova playlist da… → Copia* fa una copia singola della Discover Weekly di
questa settimana come nuova playlist, che poi non cambia più. E se accendi i
[backup automatici](backup-playlist-spotify), ogni backup tiene anche una copia datata di Discover
Weekly come file, così puoi rivedere le ultime settimane.

## Funziona con il solo Spotify?

Sì. Tutto quello che c'è sopra resta dentro un unico account Spotify. Se usi anche YouTube Music,
TIDAL, Deezer, Apple Music o il tuo Navidrome, Jellyfin o Plex, sceglilo come destinazione e la tua
Discover Weekly arriva lì.

## È sicuro per il mio account?

Accedi sulla pagina di Spotify e l'accesso resta cifrato sul tuo telefono. Trackhop non ha un
server né un account suo. Una sync settimanale legge una playlist e aggiunge qualche decina di
brani: la stessa cosa che faresti tu a mano, una volta a settimana.

## Altro che puoi fare con un solo servizio

- [Backup automatico di tutte le playlist](backup-playlist-spotify)
- [Rimuovere i brani doppi da una playlist](rimuovere-duplicati-playlist)
- [Sistemare i brani in grigio](brani-non-riproducibili)
- [Il tuo Wrapped, quando vuoi](wrapped-quando-vuoi)

[Scarica Trackhop](/it/) · [Primi passi](iniziare) · [Tutte le pagine della guida](./)
