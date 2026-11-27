---
bridge: false
title: Trackhop su iPhone e iPad
description: Perché non c'è un'app Trackhop per iPhone (i costi di Apple, le sue regole, niente lavoro in background) e cosa funziona al suo posto, il tuo server Trackhop, la cui pagina sta nella schermata Home dell'iPhone come un'app.
lang: it
i18n: iphone
---

# Trackhop su iPhone e iPad

Un'app Trackhop per iPhone o iPad non c'è, e non per mancanza di voglia. L'iPhone è la piattaforma
più chiusa tra quelle su cui Trackhop potrebbe girare: quello che su Android, Windows, macOS e Linux
è libero e gratuito, Apple lo fa pagare oppure non lo permette.

Quello che oggi funziona su iPhone è il tuo **server Trackhop**: fa girare le sincronizzazioni giorno
e notte, e la sua pagina sta nella schermata Home e si apre a schermo intero, come un'app.

## Perché non c'è un'app per iPhone

- **Apple fa pagare per pubblicare qualsiasi cosa.** Un'app per iPhone, anche gratuita e open source,
  arriva alle persone solo dall'App Store, e per pubblicarci serve l'abbonamento sviluppatore di Apple,
  da rinnovare ogni anno. Trackhop è gratuito e senza pubblicità: dovrebbe pagare Apple ogni anno per
  regalare un'app. Su Android la stessa app è su GitHub e F-Droid, gratis.
- **Niente installazione da altre parti.** Su Android installi Trackhop dalla sua pagina, da F-Droid o
  da Obtainium. Su iPhone le strade alternative all'App Store costano lo stesso abbonamento, oppure
  richiedono un computer e una reinstallazione ogni settimana: niente che una persona normale
  sopporterebbe.
- **Niente lavoro in background.** iOS mette in pausa un'app pochi secondi dopo che l'hai lasciata.
  Una sincronizzazione che parte da sola ogni notte, il cuore di Trackhop, è qualcosa che iOS non
  lascia fare in modo affidabile a un'app: partirebbe solo quando decide iOS, spesso mai. Le web app
  su iPhone hanno ancora meno: nessun lavoro in background.
- **Regole più strette su cosa può fare un'app.** Gli accessi rapidi di Trackhop, che funzionano con il
  tuo solito account Spotify, YouTube Music o Amazon Music e senza chiavi sviluppatore, usano i player
  web dei servizi. Le regole dell'App Store vogliono il permesso del servizio per farlo, quindi
  un'app per iPhone si fermerebbe alle vie ufficiali, ognuna con la sua chiave sviluppatore, come la
  versione Google Play senza il suo plugin.
- **Anche l'API di Apple Music costa lo stesso abbonamento.** Su Android Trackhop arriva ad Apple Music
  dal suo player web, con il tuo solito ID Apple: su iPhone anche questa strada è chiusa.

Insomma, le cose che rendono Trackhop comodo (accedi con il tuo account, poi te ne dimentichi mentre
sincronizza da solo) sono proprio quelle che l'iPhone toglie. Un server le fa al suo posto, per tutti
i tuoi dispositivi, iPhone compreso.

Se un'app per iPhone farebbe la differenza per te, dillo in una
[issue](https://github.com/xlollx/Trackhop/issues/new/choose) (anche in italiano): sono le richieste a
decidere cosa viene dopo.

## Cosa serve

- **Un server Trackhop**: [Trackhop su Docker](docker), su un NAS, un Raspberry Pi o un computer che
  resta acceso. Le sincronizzazioni girano lì, quindi vanno avanti anche con l'iPhone spento o bloccato.
  Niente computer a casa? [Trackhop su Azure](docker#on-azure-beta) gira nel tuo abbonamento Azure,
  sempre acceso, con un indirizzo `https://`.
- **Il server raggiungibile dall'iPhone**: sulla stessa rete Wi-Fi di casa; da fuori, con una VPN come
  Tailscale o un [reverse proxy](docker#behind-a-reverse-proxy) con un indirizzo `https://`.
- **Una password** sul server (`SONGPORT_PASSWORD`), visto che anche altri dispositivi lo raggiungono.

[Trackhop Desktop](desktop) da solo non basta: la sua pagina risponde solo sul computer dove gira.

## Nella schermata Home

1. Apri l'indirizzo del server in **Safari** (per esempio `http://192.168.1.20:8080`) ed entra.
2. Tocca **Condividi › Aggiungi alla schermata Home**, poi **Aggiungi**.
3. Apri Trackhop dalla schermata Home. La prima volta chiede di nuovo la password: tiene un accesso
   tutto suo, separato da quello di Safari.

## Cosa puoi fare dall'iPhone

- Vedere ogni sincronizzazione, l'ultima esecuzione e cosa è cambiato.
- Avviare una sincronizzazione subito, accenderla o spegnerla, cambiarne la pianificazione.
- Creare una nuova sincronizzazione e rivedere i brani non trovati.
- Tutto il resto che offre la pagina del server: sincronizzazioni dalla radio, notifiche, testi,
  playlist con l'IA, l'API.

La pagina mostra il server in tempo reale e non tiene niente sull'iPhone. Se il server è spento o non
raggiungibile, lo dice.

## Collega i servizi da un computer

Collega Spotify, YouTube Music e gli altri servizi dal browser di un computer, non dall'app nella
schermata Home: la loro pagina di accesso può uscire dall'app e non tornarci. Alcuni servizi
accettano l'accesso solo su `127.0.0.1` o su un indirizzo `https://`: la
[guida Docker](docker#services) dà la regola per ognuno.

[English](iphone) · [Tutte le pagine della guida](./)
