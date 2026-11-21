#!/bin/sh
# Started as root (the image's default): the data folder becomes the server user's, then the server
# runs as that user, never as root. A folder Docker has just created for a bind mount (compose's
# ./trackhop-data, Unraid's appdata) or a platform's disk (Railway, Render) belongs to root, and the
# server could not write there. PUID and PGID pick another user, e.g. 99 and 100 on Unraid; root is
# never used. Started with --user (or a platform's own user): runs as it is.
set -e
server=/opt/songport/bin/songport-server
data=${SONGPORT_DATA:-/data}
# Render gives the service's public address here; SONGPORT_URL, when set, wins.
if [ -z "${SONGPORT_URL:-}" ] && [ -n "${RENDER_EXTERNAL_URL:-}" ]; then export SONGPORT_URL="$RENDER_EXTERNAL_URL"; fi
if [ "$(id -u)" != 0 ]; then exec "$server" "$@"; fi
uid=${PUID:-$(id -u songport)}
gid=${PGID:-$(id -g songport)}
if [ "$uid" = 0 ] || [ "$gid" = 0 ]; then echo "PUID and PGID cannot be 0: the server never runs as root" >&2; exit 1; fi
mkdir -p "$data"
# Only when the folder is someone else's: a volume already the user's is left as it is.
if [ "$(stat -c %u:%g "$data")" != "$uid:$gid" ]; then chown -R "$uid:$gid" "$data"; fi
export HOME="$data"
exec setpriv --reuid="$uid" --regid="$gid" --clear-groups -- "$server" "$@"
