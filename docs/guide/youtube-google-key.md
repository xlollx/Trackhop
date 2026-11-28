---
title: YouTube Music with your own Google key, step by step (and every error fixed)
description: Create the free Google key Trackhop's official YouTube route needs, in about three minutes with Google's Personal mode, with every click spelled out, and fix "Access blocked: has not completed the Google verification process", "Google hasn't verified this app", redirect_uri_mismatch, invalid_client and being signed out every week.
---

# YouTube Music with your own Google key

Trackhop's **official** YouTube route talks to Google's YouTube Data API. Google gives every person
a free key of their own: Trackhop has none built in, so your daily quota is yours alone.
Making the key takes about ten minutes, once. Follow the steps **in order**: each one depends on the
one before.

> **Read this first.** Google's pages say "unverified", "unsafe" and "blocked" a lot. That's
> normal: the "app" you create is **yours**, used only by **you**, and Google simply hasn't reviewed
> it, because it doesn't need to. Nothing here costs money, and no credit card is asked.

## The quick way: Google's Personal mode (about 3 minutes)

Since late 2026 Google has a **Personal mode** that makes the project and the key in one page. Nothing to
verify, no test users, no publishing, and Google does **not** sign you out every 7 days. The only limit:
**only the Gmail that makes the key can sign in with it** (for a second Google account, make a second key
the same way).

1. Open [Google's Personal mode page](https://console.cloud.google.com/auth/overview/personal?clientType=desktop)
   signed in with **the Gmail whose YouTube playlists you'll sync**. If Google opens another of your
   accounts, switch account at the top right first.
2. Keep the project it offers (or pick one), check that the client type is **Desktop app**, accept the terms
   and press **Continue**.
3. Turn on the API for that project: open [the YouTube Data API v3](https://console.cloud.google.com/flows/enableapi?apiid=youtube.googleapis.com),
   check the project name at the top and press **Enable**.
4. Back on the client, press **Download JSON**. In Trackhop open the key screen of YouTube Music (Google API)
   and press **Import the client file (JSON)**: the Client ID and secret fill in by themselves. (Or copy the
   two values into their fields.)
5. Press **Save and connect** and pick the same Gmail. Done.

**"Channel not found"?** That Google account has no YouTube channel yet (YouTube Music works without one,
the API doesn't). Open the YouTube app with that Gmail, tap your picture, then **Create a channel**: it
publishes nothing. Then try again.

The long way below is for a key that **several Google accounts** share, and for the **Docker server** opened
from another computer (it needs a *Web application* client with a redirect).

## The long way

### What you need

- The **Google account (Gmail)** whose YouTube playlists you want to sync. Use the same one in every
  step. A personal Gmail works best: school and work accounts may be blocked by their administrator.
- A computer is easier than a phone for these pages, but a phone works too.

### Step 1: create a project

1. Open [console.cloud.google.com/projectcreate](https://console.cloud.google.com/projectcreate) and
   sign in with **that** Gmail.
2. The first time, Google asks for your country and to accept its terms: do it.
3. **Project name**: anything, for example `Trackhop`. Leave *Location* as it is. Press **Create**.
4. Wait for the notification (bell, top right) that says the project was created, then press
   **Select project**.
5. Check the project's name in the bar at the top of the page: every next step must happen **inside
   this project**.

### Step 2: turn on the YouTube Data API

1. Open [the YouTube Data API v3 page](https://console.cloud.google.com/apis/library/youtube.googleapis.com).
2. Check that the bar at the top shows **your project**.
3. Press **Enable** and wait until the page changes to *API/Service details*.

### Step 3: set up the sign-in screen

1. Open [Google Auth Platform › Overview](https://console.cloud.google.com/auth/overview) and press
   **Get started**. (If it shows *Branding* and *Audience* already, someone did this before: go to Step 4.)
2. **App name**: `Trackhop`. **User support email**: your Gmail. Press **Next**.
3. **Audience**: choose **External**. Press **Next**.
4. **Contact information**: your Gmail. Press **Next**.
5. Tick the box to agree to the policy, press **Continue**, then **Create**.

### Step 4: add yourself as a test user ⚠️ the step people miss

Without it Google answers **"Access blocked: Trackhop has not completed the Google verification
process"** (Error 403: access_denied).

1. Open [Google Auth Platform › Audience](https://console.cloud.google.com/auth/audience).
2. Scroll to **Test users** and press **+ Add users**.
3. Type the **same Gmail** you'll sign in with in Trackhop and press **Save**.
4. Check that it now appears in the *Test users* list. Up to 100 addresses: add your family's too if
   they'll use your key.

### Step 5: create the client and copy it

1. Open [Google Auth Platform › Clients › Create client](https://console.cloud.google.com/auth/clients/create).
2. **Application type**, depending on where you use Trackhop:
   - **The phone app**: choose **Desktop app**. Nothing else to fill in.
   - **Trackhop Desktop** (Windows, macOS, Linux): choose **Desktop app** too. If Google later answers
     *redirect_uri_mismatch*, make a **Web application** client instead with the *Authorized redirect
     URI* `http://127.0.0.1:8642/callback`.
   - **The Docker server** opened from another computer: choose **Web application**, and under
     **Authorized redirect URIs** press **+ Add URI** and paste the address the Trackhop page shows
     (your `SONGPORT_URL` followed by `/callback`, for example `http://127.0.0.1:8080/callback`).
     Google accepts plain `http` only for `127.0.0.1` and `localhost`: anything else needs `https`.
   - Not **Android**, not **iOS**: those won't work with your own key.
3. **Name**: anything. Press **Create**.
4. A window shows the **Client ID** and the **Client secret**. **Copy both now**: Google shows the
   secret only this once. (Lost it? Open the client, press **Add secret**, and use the new one.)
5. In Trackhop paste the **Client ID** and the **Client secret** in their fields, with nothing
   before or after (no spaces), and press **Save**.

### Step 6: sign in

1. In Trackhop press **Sign in**. Google shows its account chooser: pick the **same Gmail** you added
   in Step 4.
2. Google shows **"Google hasn't verified this app"**. It's your own app: press **Continue**.
   (On some screens: **Advanced**, then **Go to Trackhop (unsafe)**.)
3. Google asks to **manage your YouTube account**: allow it (Trackhop needs it to read and write your
   playlists), then **Continue**.
4. If your Google account has more than one YouTube channel, pick the one with your playlists.
5. Back in Trackhop, YouTube shows as connected. Done.

### Step 7 (recommended): stay signed in for good

While your app is in **Testing**, Google signs you out **every 7 days**, and syncs to YouTube stop
until you sign in again. To avoid it:

1. Open [Google Auth Platform › Branding](https://console.cloud.google.com/auth/branding). Google lets you
   publish only when three more fields are filled in:
   - **Application home page**: `https://trackhop.org`
   - **Application privacy policy link**: `https://trackhop.org/privacy-policy`
   - **Authorized domains**: press **+ Add domain** and type `trackhop.org`

   Press **Save** at the bottom. (Without them **Publish app** stays grey, with "complete the configuration
   on the Branding page".)
2. Open [Google Auth Platform › Audience](https://console.cloud.google.com/auth/audience). Under *Publishing
   status* press **Publish app**, then **Confirm**.
3. Sign in again in Trackhop once. From now on Google keeps you signed in. Google doesn't review an
   app like this one unless more than 100 people use it; the "hasn't verified" screen in Step 6 stays,
   and that's fine.

## Every error, and what to do

| What you see | Why | What to do |
|---|---|---|
| **Access blocked: Trackhop has not completed the Google verification process** (Error 403: access_denied) | Your Gmail isn't a test user, or you signed in with another account | Step 4 with the exact Gmail, then sign in again choosing that account |
| **Google hasn't verified this app** | Normal for your own app | Press **Continue** (or *Advanced › Go to Trackhop (unsafe)*) |
| **Error 400: redirect_uri_mismatch** | Wrong client type, or (Docker) the redirect isn't registered | Phone app: a **Desktop app** client. Desktop: a **Web application** client with `http://127.0.0.1:8642/callback`. Docker: a **Web application** client with the exact address the Trackhop page shows |
| **invalid_client** / **unauthorized_client** | The ID or secret was copied wrong, or belong to different clients | Copy both again from the same client (Step 5); no spaces |
| **YouTube Data API v3 has not been used in project … or it is disabled** | Step 2 was skipped or done in another project | Step 2, with your project selected at the top |
| **This app is blocked** / **admin_policy_enforced** | A school or work account whose administrator blocks it | Use a personal Gmail |
| **Signed out after about a week** | The app is still in *Testing* | Step 7 |
| **Publish app is grey**: "complete the configuration on the Branding page" | Home page, privacy policy and authorized domain are empty | Step 7, point 1 |
| **Channel not found** (API 404) | The Google account has no YouTube channel | YouTube app › your picture › **Create a channel**, then try again |
| **Quota exceeded** | YouTube's daily limit (about 100 searches a day) | Nothing is broken: the sync carries on by itself after midnight Pacific time |

Still stuck? Open an [issue](https://github.com/xlollx/Trackhop/issues/new/choose) with the message
Google showed, and if you can the technical details from the app (**Settings › Advanced › Log ›
Share technical details**; the button appears once **Developer options** is on): they say which of
these it is.

[All guide pages](./) · [Troubleshooting](troubleshooting)
