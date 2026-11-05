# Huawei AppGallery (and why not the Samsung Galaxy Store)

Trackhop is built in five flavors from one code base (same package id `com.xlollx.songport`, same
signing key). One of them is for Huawei's store:

| Flavor | Store | Asset (prerelease `builds-v<version>` of each release) | Differences from the Play build |
|---|---|---|---|
| `appgallery` | Huawei AppGallery | `Trackhop-v<version>-appgallery.apk` | no Google Play update library and no ML Kit, so no Gemini Nano (Huawei phones have no Google services) |

It follows the store rules of the Play build (`BuildConfig.STORE_BUILD`): official APIs only, web
connectors only through the Bridge (which needs no Google services either), no link to external
payments (no Ko-fi), the report flag on the Roast. Same listing texts, screenshots and privacy policy
as Google Play ([play-store-listing.md](play-store-listing.md), `docs/store/play/`,
`docs/privacy-policy.md`).

The app's own release `v<version>` keeps a single APK (the `full` one): Obtainium follows it and
skips prereleases, so it never has to ask which APK to install.

Signing: the AppGallery APK carries the release workflow's key, the same as the GitHub and F-Droid
APKs, so a user can move between them without uninstalling. Google Play re-signs with its own app
signing key: moving to or from Play needs an uninstall. If AppGallery offers to re-sign with a key of
its own, decline when possible.

**Samsung Galaxy Store: not possible for now.** Seller Portal takes Android apps only from a
"Corporate Commercial Distribution Seller" (a registered company); private sellers can publish themes
and watch faces only (checked 05/11/2026). Samsung phones have Google Play, so their users are covered
by Play, GitHub and F-Droid. The `galaxy` flavor (as `play` without Google Play's update library)
stays in the code, not released, should that change.

## Huawei AppGallery

1. **Account**: developer.huawei.com › Register › AppGallery Connect. **Individual developer**:
   identity check (ID document; in some regions also a bank card). Free.
2. **Trader status (EU)**: non-trader (no ads, no purchases).
3. AppGallery Connect › **My apps** › **New app** › Android, package `com.xlollx.songport`. Fill in:
   - App information: same texts, icon, screenshots, category **Music & Audio**, privacy policy URL,
     content rating questionnaire (no ads, no purchases).
   - **Distribution countries/regions**: all except **Chinese mainland** (it needs a Chinese software
     copyright certificate and an ICP filing).
   - Package: upload `Trackhop-v<version>-appgallery.apk` from the GitHub prerelease `builds-v<version>`.
   - Paid/free: free; no in-app purchases.
4. **Submit**. Review takes a few days; they check that the app works without Google services
   (sign-in pages open in the browser; Gemini Nano is simply not offered).
5. Each update: new version in the same app, new APK, release notes from fastlane.

## Later: uploads from the release workflow

AppGallery Connect has a Publishing API (with an API client). Once the first manual upload is
accepted, the release workflow can send each new APK like `play.yml` does for Google Play, with the
client's key as a repository secret.
