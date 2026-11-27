---
title: YouTube Music with your own Google key, step by step (and every error fixed)
description: Create the free Google key Trackhop's official YouTube route needs, in about ten minutes, with every click spelled out, and fix "Access blocked: has not completed the Google verification process", "Google hasn't verified this app", redirect_uri_mismatch, invalid_client and being signed out every week.
---

# YouTube Music with your own Google key

Trackhop's **official** YouTube route talks to Google's YouTube Data API. Google gives every person
a free key of their own: Trackhop has none built in, so your daily quota is yours alone.
Making the key takes about ten minutes, once. Follow the steps **in order**: each one depends on the
one before.

> **Read this first.** Google's pages say "unverified", "unsafe" and "blocked" a lot. That's
> normal: the "app" you create is **yours**, used only by **you**, and Google simply hasn't reviewed
> it, because it doesn't need to. Nothing here costs money, and no credit card is asked.

## What you need

- The **Google account (Gmail)** whose YouTube playlists you want to sync. Use the same one in every
  step. A personal Gmail works best: school and work accounts may be blocked by their administrator.
- A computer is easier than a phone for these pages, but a phone works too.

## Step 1: create a project

1. Open [console.cloud.google.com/projectcreate](https://console.cloud.google.com/projectcreate) and
   sign in with **that** Gmail.
2. The first time, Google asks for your country and to accept its terms: do it.
3. **Project name**: anything, for example `Trackhop`. Leave *Location* as it is. Press **Create**.
4. Wait for the notification (bell, top right) that says the project was created, then press
   **Select project**.
5. Check the project's name in the bar at the top of the page: every next step must happen **inside
   this project**.

## Step 2: turn on the YouTube Data API

1. Open [the YouTube Data API v3 page](https://console.cloud.google.com/apis/library/youtube.googleapis.com).
2. Check that the bar at the top shows **your project**.
3. Press **Enable** and wait until the page changes to *API/Service details*.

## Step 3: set up the sign-in screen

1. Open [Google Auth Platform › Overview](https://console.cloud.google.com/auth/overview) and press
   **Get started**. (If it shows *Branding* and *Audience* already, someone did this before: go to Step 4.)
2. **App name**: `Trackhop`. **User support email**: your Gmail. Press **Next**.
3. **Audience**: choose **External**. Press **Next**.
4. **Contact information**: your Gmail. Press **Next**.
5. Tick the box to agree to the policy, press **Continue**, then **Create**.

## Step 4: add yourself as a test user ⚠️ the step people miss

Without it Google answers **"Access blocked: Trackhop has not completed the Google verification
process"** (Error 403: access_denied).

1. Open [Google Auth Platform › Audience](https://console.cloud.google.com/auth/audience).
2. Scroll to **Test users** and press **+ Add users**.
3. Type the **same Gmail** you'll sign in with in Trackhop and press **Save**.
4. Check that it now appears in the *Test users* list. Up to 100 addresses: add your family's too if
   they'll use your key.

## Step 5: create the client and copy it

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

## Step 6: sign in

1. In Trackhop press **Sign in**. Google shows its account chooser: pick the **same Gmail** you added
   in Step 4.
2. Google shows **"Google hasn't verified this app"**. It's your own app: press **Continue**.
   (On some screens: **Advanced**, then **Go to Trackhop (unsafe)**.)
3. Google asks to **manage your YouTube account**: allow it (Trackhop needs it to read and write your
   playlists), then **Continue**.
4. If your Google account has more than one YouTube channel, pick the one with your playlists.
5. Back in Trackhop, YouTube shows as connected. Done.

## Step 7 (recommended): stay signed in for good

While your app is in **Testing**, Google signs you out **every 7 days**, and syncs to YouTube stop
until you sign in again. To avoid it:

1. Open [Google Auth Platform › Audience](https://console.cloud.google.com/auth/audience).
2. Under *Publishing status* press **Publish app**, then **Confirm**.
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
| **Quota exceeded** | YouTube's daily limit (about 100 searches a day) | Nothing is broken: the sync carries on by itself after midnight Pacific time |

Still stuck? Open an [issue](https://github.com/xlollx/Trackhop/issues/new/choose) with the message
Google showed, and if you can the technical details from the app (**Settings › Advanced › Log ›
Share technical details**; the button appears once **Developer options** is on): they say which of
these it is.

[All guide pages](./) · [Troubleshooting](troubleshooting)
