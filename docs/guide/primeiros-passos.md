---
title: Primeiros passos com o Trackhop
description: Instale o Trackhop, conecte seus serviços de música e crie a sua primeira sincronização de playlists, passo a passo.
lang: pt-BR
i18n: start
---

# Primeiros passos

## Instalar

| Onde | Observações |
|---|---|
| [F-Droid](https://f-droid.org/packages/com.xlollx.songport/) | Compilado pelo F-Droid a partir do código-fonte e assinado com a mesma chave do APK do GitHub (build reproduzível): um atualiza o outro. As versões novas chegam alguns dias depois do GitHub. |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Adicione `https://github.com/xlollx/Trackhop`: cada versão chega sozinha ao celular. |
| [GitHub](https://github.com/xlollx/Trackhop/releases/latest) | O APK assinado de cada versão. |

Android 8.0 ou mais recente. No computador: [Trackhop Desktop](desktop) (Windows, macOS, Linux).

## Conectar um serviço

*Contas → Adicionar conector.* Muitos serviços podem ser conectados de dois jeitos:

- **Conexão rápida** (Spotify, Apple Music, YouTube Music, Amazon Music): você entra pela página do
  serviço, dentro do app, como num navegador. Nada para criar. Não é uma interface oficial, então pode
  parar de funcionar se o site mudar (o app avisa antes do primeiro login).
- **Conexão oficial** (Spotify, YouTube Music, TIDAL, Deezer, Apple Music): você cria uma chave de
  desenvolvedor gratuita no seu nome; o app guia você passo a passo, em uns dois minutos.

Servidores pessoais (Navidrome/Subsonic, Jellyfin, Plex) pedem só o endereço e o seu login. Arquivos
não pedem nada. Depois de conectar, *⋮ → Testar conexão* verifica login, playlists e busca de uma vez.

## A primeira sincronização

*Syncs → Nova sync*: escolha a origem (uma playlist, suas músicas curtidas, uma seção da biblioteca ou
um link), o destino (uma playlist existente ou "criar uma playlist nova"; deixe o nome vazio para usar o
da origem) e a frequência. Opções úteis:

- **Espelhar remoções**: o que sair da origem sai também do destino. Se uma execução fosse remover
  grande parte do destino, ela para e espera a sua confirmação na prévia.
- **Manter a ordem da origem**: depois de adicionar, o destino fica na ordem da origem.
- **Só Wi-Fi**, **Ativada** e as regras de correspondência.

Abra a **prévia** antes da primeira execução: ela mostra o que vai entrar e sair. As músicas não
encontradas vão para a **revisão**, onde você busca no destino ou escolhe uma sugestão; o app lembra a
sua escolha.

## Organizar

- **Favoritas**: *⋮ → Adicionar às favoritas* dá uma estrela à sincronização, que fica no topo (desative em
  Configurações › Aparência › Estilo dos cartões › Favoritas sempre no topo).
- **Rótulos e tags**: *⋮ → Estilo* escolhe a cor do cartão, e um nome para essa cor vira um rótulo; no editor,
  as **Tags** agrupam do seu jeito (genero = metal). A pesquisa acima da lista aparece desde a primeira
  sincronização, e os filtros a partir de cinco: encontram as sincronizações por nome, rótulo ou tag.
- **Tamanho dos cartões** e **Visão mínima** (pequenos blocos em grade) em Configurações › Aparência ›
  **Estilo dos cartões**, que reúne também cores, status e execuções anteriores nos cartões; segure um
  cartão e arraste para mudar a ordem.

No app, Configurações › Como fazer reúne guias curtos sobre tudo, com uma pesquisa no topo.

[Transferir playlists grátis](transferir-playlist) · [Guia completo (inglês)](./)
