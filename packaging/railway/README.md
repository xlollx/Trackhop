# Trackhop on Railway

A Railway template is made in Railway's dashboard, not from a file: these are its settings.
Needs the image from server 2.1.3 on (its entrypoint gives Railway's volume, which belongs to root, to
the server's user; older images cannot write there).

1. Railway › your workspace › **Settings › Templates › New Template**.
2. Add a service **from a Docker image**: `ghcr.io/xlollx/trackhop:latest`.
3. **Volume**: mount path `/data`.
4. **Variables**:
   - `PORT` = `8080`
   - `SONGPORT_URL` = `https://${{RAILWAY_PUBLIC_DOMAIN}}`
   - `SONGPORT_PASSWORD` = `${{secret(24)}}` (the user sees it in the service's Variables)
5. **Networking**: public HTTP, port 8080.
6. Name *Trackhop*, description "Keeps your playlists in sync between music services (beta)",
   icon `https://raw.githubusercontent.com/xlollx/Trackhop/main/docs/store/icon512.png`, then
   **Publish**.
7. Copy the template's link (`https://railway.com/new/template/<id>`): it goes in the README's
   button.

Published 21/11: <https://railway.com/deploy/NC9rYj> (the README button carries the owner's referral code).
