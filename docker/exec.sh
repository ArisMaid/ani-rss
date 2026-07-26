#!/bin/sh

chown -R ${PUID}:${PGID} /usr/app

umask ${UMASK}

if command -v su-exec >/dev/null 2>&1; then
  exec su-exec ${PUID}:${PGID} /run.sh
fi

if command -v setpriv >/dev/null 2>&1; then
  exec setpriv --reuid=${PUID} --regid=${PGID} --clear-groups /run.sh
fi

if command -v gosu >/dev/null 2>&1; then
  exec gosu ${PUID}:${PGID} /run.sh
fi

echo "No supported privilege-drop tool is installed (su-exec, setpriv or gosu)." >&2
exit 1
