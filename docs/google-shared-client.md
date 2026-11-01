# Shipping a shared Google client for YouTube Music

By default every user creates their own Google Cloud project. A maintainer can instead ship one
Android OAuth client in the build so that users only see "Connect" and the Google login. This is
what commercial competitors do. The trade-off is a shared daily quota (see the end of this page).
Users can still switch to their own credentials in the app.

## 1. Project and API

1. console.cloud.google.com → create a project, for example `songport-prod`.
2. APIs & Services → Library → **YouTube Data API v3** → Enable.

## 2. Google Auth Platform

1. **Branding**: app name `Trackhop`, user support e-mail, app logo (`docs/store/icon512.png`
   scaled to 120×120), application home page `https://trackhop.org/`, privacy policy
   `https://trackhop.org/privacy-policy`, terms of service (optional), authorized
   domain `xlollx.github.io`, developer contact e-mail.
2. **Audience**: user type **External**. While the app is in *Testing*, only the listed test users
   (max 100) can log in. Press **Publish app** when you are ready for verification.
3. **Data Access**: add the scope `https://www.googleapis.com/auth/youtube`. It is classed as
   *sensitive* (not *restricted*), so verification does not require a security assessment.
   Justification text that matches what the app does:

   > Trackhop keeps a user's playlists in sync between music services. With the youtube scope the
   > app reads the user's YouTube playlists and their items, creates playlists in the user's own
   > channel and adds or removes playlist items, only when the user starts or schedules a sync.
   > Data is processed on the user's device only; there is no Trackhop server.

## 3. The Android OAuth client

1. **Clients** → Create client → application type **Android**.
2. Package name `com.xlollx.songport`.
3. SHA-1: the **upload key** fingerprint (`keytool -list -v -keystore upload.jks -alias upload`).
   After the first upload to Play Console, add a second Android client (or a second fingerprint)
   with the **Play App Signing** SHA-1 shown in Play Console → Test and release → App integrity.
4. Copy the client ID (ends with `.apps.googleusercontent.com`) into the GitHub Actions variable
   `GOOGLE_CLIENT_ID`. The build turns it into the redirect scheme
   `com.googleusercontent.apps.<id>:/oauth2redirect`, which the manifest already handles.
   An Android client ID is not a secret: Google ties it to the package name and the signature.

Never register the debug keystore fingerprint on a public project: anyone could rebuild the app
with the same signature.

## 4. Verification

Google reviews apps that request sensitive scopes before they can serve more than the test users.
Prepare, in this order:

1. **Home page** on the authorized domain that describes the app and how it uses Google user data
   (`docs/index.md` already does), linking to the privacy policy.
2. **Domain ownership**: Google Search Console → add the URL-prefix property
   `https://trackhop.org/` and verify it with the HTML file method (drop the file in
   `docs/`). The account doing this must be an owner of the Cloud project.
3. **Demo video** (unlisted YouTube video, 1–3 minutes): open the app, tap Connect on YouTube
   Music, show the Google consent screen with the app name and the requested permission, complete
   the login, then show a playlist being synced. The client ID must be visible at least once: the
   consent screen URL in the browser is enough.
4. Google Auth Platform → **Verification Center** → *Prepare for verification*: paste the scope
   justification, the video link and confirm the URLs. Typical turnaround for sensitive scopes is
   a few working days; Google may ask for changes by e-mail.

Until verification completes the app stays usable through the test-user list.

## 5. Quota

YouTube Data API v3 gives **10,000 units per day per project**. Trackhop's costs:

| Call | Units | When |
|------|-------|------|
| `search.list` | 100 | one per track that is not in the match cache |
| `playlistItems.insert` | 50 | one per added track |
| `playlistItems.delete` | 50 | one per removed track |
| `playlists.insert` | 50 | new target playlist |
| `playlists.list`, `playlistItems.list` | 1 | per page of 50 items |

So a new track costs about 150 units: roughly 65 new tracks per day for all users of the shared
client, while re-syncs of unchanged playlists cost almost nothing. The app shows the daily usage as
a ring and estimates the cost of each sync before running it.

**Requesting more**: Cloud Console → APIs & Services → YouTube Data API v3 → **Quotas** → edit
*Queries per day* → *Request quota increase*. Google redirects to the *YouTube API Services –
Audit and Quota Extension Form*. A justification that fits this app:

> Trackhop is a free, open-source Android app (https://github.com/xlollx/Trackhop) that syncs a
> user's playlists between music services on the user's own device. It uses search.list to find
> the YouTube video for a track (about 100 units per new track), playlistItems.insert/delete to
> mirror the user's playlist and playlists.list/playlistItems.list to read state. Every match is
> cached on the device so a track is searched at most once. Requests are made only on explicit
> user action or on a schedule the user chose (at most hourly). Expected volume: N daily active
> users × ~M new tracks per day ≈ K units. The app complies with the YouTube API Services Terms
> of Service and Developer Policies, links to the YouTube ToS and the Google Privacy Policy in
> its privacy policy, shows users their daily quota usage, and does not store YouTube data on any
> server.

Fill in N, M and K with real numbers from the first weeks; requests backed by usage data are the
ones that get approved. Independent developers commonly obtain 100,000 to 1,000,000 units.

## 6. Ship it

Set `GOOGLE_CLIENT_ID` in GitHub → Settings → Secrets and variables → Actions → Variables, push
or run the workflow, and upload the new AAB. Users who already entered their own credentials keep
using them; everyone else sees a plain **Connect** button.
