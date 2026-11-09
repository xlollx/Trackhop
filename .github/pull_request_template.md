## What this changes

<!-- One or two sentences: what and why. -->

Fixes #<!-- issue number, if there is one -->

## Type of change

- [ ] Bug fix
- [ ] Enhancement to an existing feature
- [ ] New feature
- [ ] New service or connector
- [ ] Translation
- [ ] Documentation or website
- [ ] Build, CI or dependencies
- [ ] Refactoring (no change in behavior)

## Where it applies

- [ ] Android app (GitHub / F-Droid builds)
- [ ] Android app (Google Play / AppGallery builds)
- [ ] Desktop
- [ ] Docker server
- [ ] Website (trackhop.org)

## How it was tested

<!-- Tests added or run, devices and Android versions, services and accounts tried. -->

## Screenshots

<!-- For changes people will see: before and after. Hide account names and anything personal. -->

## Anything that could break

<!-- Changes to saved data, settings, sync behavior or an API? How do existing users carry on? Write "None" if nothing. -->

## Checklist

- [ ] Tests pass: `./gradlew :core:test :ytm:test testFullDebugUnitTest :bridge:testDebugUnitTest` (from `android/`)
- [ ] New texts shown to users are in all 12 languages (a rough translation is fine: say so)
- [ ] No keys, tokens, cookies or traffic captures in the commits
- [ ] Play and AppGallery builds still call official APIs only and link to no payment outside the store
- [ ] I read [CONTRIBUTING.md](https://github.com/xlollx/Trackhop/blob/main/CONTRIBUTING.md) and agree my change is released under AGPL-3.0-only

<!-- Used an AI assistant? That's fine: say so here, and check that you understand and tested what it wrote. -->
