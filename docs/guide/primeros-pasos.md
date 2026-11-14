---
title: Primeros pasos con Trackhop
description: Instala Trackhop, conecta tus servicios de música y crea tu primera sincronización de playlists, paso a paso.
lang: es
i18n: start
---

# Primeros pasos

## Instalar

| Dónde | Notas |
|---|---|
| [F-Droid](https://f-droid.org/packages/com.xlollx.songport/) | Compilada por F-Droid desde el código fuente y firmada con la misma clave que el APK de GitHub (build reproducible): se actualizan entre sí. Las versiones nuevas llegan unos días después que a GitHub. |
| [Obtainium](https://github.com/ImranR98/Obtainium) | Añade `https://github.com/xlollx/Trackhop`: cada versión llega sola al móvil. |
| [GitHub](https://github.com/xlollx/Trackhop/releases/latest) | El APK firmado de cada versión. |

Android 8.0 o posterior. En el ordenador: [Trackhop Desktop](desktop) (Windows, macOS, Linux).

## Conectar un servicio

*Cuentas → Añadir conector.* Muchos servicios se conectan de dos formas:

- **Vía rápida** (Spotify, Apple Music, YouTube Music, Amazon Music): inicias sesión en la página del
  servicio, dentro de la app, como en un navegador. No hay que crear nada. No es una interfaz oficial,
  así que puede dejar de funcionar si la web cambia (la app avisa antes del primer inicio de sesión).
- **Vía oficial** (Spotify, YouTube Music, TIDAL, Deezer, Apple Music): creas una clave de desarrollador
  gratuita a tu nombre; la app te guía paso a paso, en unos dos minutos.

Los servidores personales (Navidrome/Subsonic, Jellyfin, Plex) solo piden la dirección y tu acceso.
Los archivos no piden nada. Después de conectar, *⋮ → Probar conexión* comprueba acceso, playlists y
búsqueda de una vez.

## La primera sincronización

*Syncs → Nueva sync*: elige el origen (una playlist, tus canciones favoritas, una sección de la
biblioteca o un enlace), el destino (una playlist existente o "crear una playlist nueva"; deja el nombre
vacío para usar el del origen) y la frecuencia. Opciones útiles:

- **Reflejar eliminaciones**: lo que quitas del origen se quita también del destino. Si una ejecución
  quitaría gran parte del destino, se detiene y espera tu confirmación en la vista previa.
- **Mantener el orden del origen**: después de añadir, el destino se ordena como el origen.
- **Solo Wi-Fi**, **Activada** y las reglas de coincidencia.

Abre la **vista previa** antes de la primera ejecución: muestra qué se añadirá y qué se quitará. Las
canciones no encontradas van a la **revisión**, donde buscas tú en el destino o eliges una sugerencia;
la app recuerda tu elección.

## Poner orden

- **Favoritas**: *⋮ → Añadir a favoritas* le pone una estrella a la sincronización, que se queda arriba (se
  desactiva en Ajustes › Apariencia › Estilo de las tarjetas › Favoritas siempre arriba).
- **Colores con nombre y etiquetas**: *⋮ → Estilo* elige el color de la tarjeta, y un nombre para ese color
  agrupa todas las que lo llevan; en el editor, las **Etiquetas** agrupan a tu manera (genero = metal). La
  búsqueda sobre la lista está desde la primera sincronización, y los filtros desde cinco: las encuentran por
  nombre, color o etiqueta.
- **Tamaño de las tarjetas** y **Vista mínima** (mosaicos pequeños en cuadrícula) en Ajustes › Apariencia ›
  **Estilo de las tarjetas**, que reúne también colores, estado y ejecuciones anteriores en las
  tarjetas; mantén pulsada una tarjeta y arrástrala para cambiar el orden.

En la app, Ajustes › Cómo se hace reúne guías breves de todo, con un buscador arriba.

[Transferir playlists gratis](transferir-playlists) · [Guía completa (inglés)](./)
