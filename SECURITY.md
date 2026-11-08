# Security policy

Trackhop handles OAuth tokens and, for personal servers, passwords. Reports about anything that could
expose them are welcome.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (*Security → Report a vulnerability*)
rather than a public issue. Please include the app version, the service involved and steps to
reproduce. You will get a reply as soon as possible; fixes ship as a new release on Google Play and a
tagged commit here.

## What is in scope

- Token or credential storage (`data/TokenStore.kt`, `EncryptedSharedPreferences`)
- OAuth flows (`auth/`): PKCE, redirect handling, the loopback server used for Google, the Apple Music
  WebView
- Anything that would let a third party read playlists or act on a connected account

## What is not a vulnerability

- Rate limits or quota errors returned by the music services

## Keys and secrets

No API key, client secret, developer token or signing key is stored in this repository. They are
injected at build time through GitHub Actions variables and secrets (see the README). If you find one
committed by mistake, report it as above.
