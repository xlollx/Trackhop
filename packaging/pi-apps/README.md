# Trackhop on Pi-Apps

The files of Trackhop's [Pi-Apps](https://github.com/Botspot/pi-apps) entry, at the same paths as in
that repository: `apps/Trackhop/` (installs the release's `-linux-arm64.deb`, 64-bit Raspberry Pi OS
and Ubuntu) and `.github/workflows/updates/Trackhop.sh` (Pi-Apps' own updater bumps `version` from
our `server-v*` releases, so a new release needs no pull request). Plus the line
`Trackhop|Multimedia` in `etc/categories`.

Submitting: fork Botspot/pi-apps, copy these files there, run the fork's `test_build.yml` with the
app name `Trackhop`, run the app on a real Raspberry Pi, then the pull request against `master`
(see packaging/README.md).
