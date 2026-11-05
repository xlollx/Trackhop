# Samsung Galaxy Store and Huawei AppGallery

Trackhop is built in five flavors from one code base (same package id `com.xlollx.songport`, same
signing key). Two of them are for these stores:

| Flavor | Store | Asset in each GitHub Release | Differences from the Play build |
|---|---|---|---|
| `galaxy` | Samsung Galaxy Store | `Trackhop-v<version>-galaxy.apk` | no Google Play update library (the store updates the app) |
| `appgallery` | Huawei AppGallery | `Trackhop-v<version>-appgallery.apk` | as `galaxy`, and no ML Kit, so no Gemini Nano (Huawei phones have no Google services) |

Both follow the store rules of the Play build (`BuildConfig.STORE_BUILD`): official APIs only, web
connectors only through the Bridge, no link to external payments (no Ko-fi), the report flag on the
Roast. Same listing texts, screenshots and privacy policy as Google Play
([play-store-listing.md](play-store-listing.md), `docs/store/play/`, `docs/privacy-policy.md`).

Signing: both APKs carry the release workflow's key, the same as the GitHub and F-Droid APKs, so a
user can move between GitHub, F-Droid, Galaxy Store and AppGallery without uninstalling. Google Play
re-signs with its own app signing key: moving to or from Play needs an uninstall. If a store offers
to re-sign with a key of its own, decline when possible.

## Samsung Galaxy Store

1. **Account**: seller.samsungapps.com › Sign up with a Samsung account. Choose **Private seller**
   (individual) and a free app only: no tax or bank details needed. The account is approved in a few
   days.
2. **Trader status (EU)**: Seller Portal asks for it as Google Play does. No ads and no in-app
   purchases: **non-trader**.
3. **Add new application** › Android › default language English. Fill in:
   - App information: title *Trackhop*, short and full description (from `fastlane/metadata/android/<locale>/`),
     icon 512×512 (`docs/store/icon512.png`), screenshots (`docs/store/play/<locale>/`), category
     **Music**, age rating (no ads, no purchases, no user interaction: the lowest), privacy policy URL,
     support e-mail.
   - Binary: upload `Trackhop-v<version>-galaxy.apk` from the GitHub Release.
   - Countries: all; price: free.
4. **Submit for review**. Samsung tests on real devices; expect a few days and sometimes a request
   for test instructions: reuse the reviewer note of the Play listing (files work without any account).
5. Each update: new binary in the same app, with the release notes of
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.

Gemini Nano: the `galaxy` build includes ML Kit, so recent Galaxy phones with AICore get the AI
without a key, as on Play.

## Huawei AppGallery

1. **Account**: developer.huawei.com › Register › AppGallery Connect. **Individual developer**:
   identity check (ID document; in some regions also a bank card). Free.
2. **Trader status (EU)**: non-trader (no ads, no purchases).
3. AppGallery Connect › **My apps** › **New app** › Android, package `com.xlollx.songport`. Fill in:
   - App information: same texts, icon, screenshots, category **Music & Audio**, privacy policy URL,
     content rating questionnaire (no ads, no purchases).
   - **Distribution countries/regions**: all except **Chinese mainland** (it needs a Chinese software
     copyright certificate and an ICP filing).
   - Package: upload `Trackhop-v<version>-appgallery.apk`.
   - Paid/free: free; no in-app purchases.
4. **Submit**. Review takes a few days; they check that the app works without Google services
   (sign-in pages open in the browser; Gemini Nano is simply not offered).
5. Each update: new version in the same app, new APK, release notes from fastlane.

## Later: uploads from the release workflow

Both stores have publishing APIs (Huawei: AppGallery Connect Publishing API with an API client;
Samsung: Galaxy Store Developer API, Content Publish, with a service account). Once the first manual
uploads are accepted, the release workflow can send each new APK like `play.yml` does for Google
Play, with their keys as repository secrets.
