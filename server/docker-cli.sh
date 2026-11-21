#!/bin/sh
# `docker exec <container> trackhop …`: the command line as the server's user (the data folder's
# owner, server/docker-entrypoint.sh), so a file it saves there stays the server's; never as root.
cli=/opt/songport/bin/trackhop
data=${SONGPORT_DATA:-/data}
if [ "$(id -u)" = 0 ] && [ "$(stat -c %u "$data")" != 0 ]; then
  export HOME="$data"
  exec setpriv --reuid="$(stat -c %u "$data")" --regid="$(stat -c %g "$data")" --clear-groups -- "$cli" "$@"
fi
exec "$cli" "$@"
