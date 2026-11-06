---
title: Primi passi con Trackhop
description: Installa Trackhop, collega i tuoi servizi musicali e crea la prima sincronizzazione delle playlist, passo per passo.
lang: it
i18n: start
---

# Primi passi

## Installa

| Dove | Note |
|---|---|
| [F-Droid](https://f-droid.org/packages/com.xlollx.songport/) | Compilata da F-Droid dal codice sorgente, firmata con la stessa chiave dell'APK di GitHub (build riproducibile): le due si aggiornano a vicenda. Le nuove versioni arrivano qualche giorno dopo GitHub. |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Aggiungi `https://github.com/xlollx/Trackhop`: ogni versione arriva sul telefono da sola. |
| [GitHub](https://github.com/xlollx/Trackhop/releases/latest) | L'APK firmato di ogni versione. |

Android 8.0 o successivo. Sul computer: [Trackhop Desktop](desktop) (Windows, macOS, Linux).

## Collega un servizio

*Account → Aggiungi connettore.* Molti servizi si collegano in due modi:

- **Via rapida** (Spotify, Apple Music, YouTube Music, Amazon Music): accedi dalla pagina del servizio,
  dentro l'app, come in un browser. Niente da creare. Non è un'interfaccia ufficiale, quindi può smettere
  di funzionare se il sito cambia (l'app lo dice prima del primo accesso).
- **Via ufficiale** (Spotify, YouTube Music, TIDAL, Deezer, Apple Music): crei una chiave sviluppatore
  gratuita a tuo nome; l'app ti guida passo per passo, in circa due minuti.

I server personali (Navidrome/Subsonic, Jellyfin, Plex) chiedono solo l'indirizzo e il tuo accesso.
Su **Jellyfin** puoi entrare anche con **Quick Connect**: scrivi l'indirizzo del server e approva il codice da un
dispositivo dove hai già fatto l'accesso (niente password, quindi va anche con il single sign-on).

**Più account dello stesso servizio**: aggiungi di nuovo lo stesso servizio (*Account → Aggiungi connettore*), per
esempio due account Spotify o due server Jellyfin, e dai a ciascuno un nome. Tra due account dello stesso servizio
musicale i brani si copiano così come sono, senza cercarli.
I file non chiedono nulla. Dopo il collegamento, *⋮ → Prova connessione* controlla accesso, playlist e
ricerca in un colpo solo.

## La prima sincronizzazione

*Sync → Nuova sync*: scegli la sorgente (una playlist, i brani che ti piacciono, una sezione della
libreria o un link), la destinazione (una playlist esistente o "crea una nuova playlist"; lascia il nome
vuoto per usare quello della sorgente) e la frequenza. Opzioni utili:

- **Rispecchia le rimozioni**: i brani tolti dalla sorgente vengono tolti anche dalla destinazione. Se
  un'esecuzione toglierebbe gran parte della destinazione, si ferma e aspetta la tua conferma nell'anteprima.
- **Mantieni l'ordine della sorgente**: dopo l'aggiunta, la destinazione viene riordinata come la sorgente.
- **Solo Wi-Fi**, **Attiva** e le regole di abbinamento.

Apri l'**anteprima** prima della prima esecuzione: mostra cosa verrà aggiunto e tolto. I brani non
trovati finiscono nella **revisione**, dove cerchi tu sulla destinazione o scegli un suggerimento; l'app
ricorda la tua scelta.

[Trasferire playlist gratis](trasferire-playlist) · [Guida completa (inglese)](./)
