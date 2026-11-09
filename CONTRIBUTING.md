# Contributing to Trackhop

Thanks for helping. Issues and pull requests are welcome, from anyone.

## Before you start

- **A bug or a small fix:** open the pull request directly.
- **A new feature or a new service:** open an issue first, so we agree on the approach before you write the code.
- **A security problem:** never in a public issue. See [SECURITY.md](SECURITY.md).

## Building and testing

Requirements: JDK 17, Android SDK with platform 36. From `android/`:

```bash
./gradlew :core:test :ytm:test testFullDebugUnitTest :bridge:testDebugUnitTest   # what CI runs
./gradlew assembleFullDebug                                                      # GitHub build
```

The Docker/Desktop server, from the repository root: `gradle -p server test`.

Without API keys the OAuth services show as "Not configured"; files and personal servers always work.

## Pull requests

- A new pull request opens with a short [template](.github/pull_request_template.md): what changes, how it was
  tested, and a checklist. Fill it in; delete what does not apply.
- Keep each pull request to one change, with a test in `src/test` when the change has logic.
- Strings shown to users go in all 12 languages (`android/app/src/main/res/values*/strings.xml`).
  A rough translation is fine: say so in the pull request and it gets checked.
- A new service: implement `MusicProvider` (or extend `OAuthProvider` / `CredentialsProvider`) and
  register it in `Providers.configure`.
- The `play` and `appgallery` builds call official APIs only and never link to payments outside the store.
- Never commit keys, tokens, cookies or traffic captures, not even test ones.
- CI runs on your pull request after a maintainer approves it (every first contribution needs that).
  Pull requests are merged with squash, with you as the author.

## License

Trackhop is under AGPL-3.0-only with the additional terms in [NOTICE](NOTICE). By opening a pull request
you agree that your contribution is released under the same license.
