#!/bin/sh
set -eu
umask 077

fail() {
    printf '%s\n' "Deployment configuration error: $1" >&2
    exit 1
}

require() {
    [ -n "$2" ] || fail "$1 is required. Configure it in the private runtime environment."
}

[ "$#" -eq 0 ] || fail 'This entrypoint does not accept command-line overrides.'
[ "${SPRING_PROFILES_ACTIVE:-prod}" = 'prod' ] || fail 'SPRING_PROFILES_ACTIVE must be prod.'
[ "${WECHAT_MODE:-real}" = 'real' ] || fail 'WECHAT_MODE must be real.'
export SPRING_PROFILES_ACTIVE=prod WECHAT_MODE=real SERVER_ADDRESS=0.0.0.0 SPRING_SQL_INIT_MODE=never

SERVER_PORT=${PORT:-${SERVER_PORT:-8080}}
case "$SERVER_PORT" in ''|*[!0-9]*) fail 'PORT/SERVER_PORT must be an integer.' ;; esac
[ "${#SERVER_PORT}" -le 5 ] && [ "$SERVER_PORT" -ge 1 ] && [ "$SERVER_PORT" -le 65535 ] \
    || fail 'PORT/SERVER_PORT must be between 1 and 65535.'
export SERVER_PORT

TRAINING_MAX_UPLOAD_BYTES=${TRAINING_MAX_UPLOAD_BYTES:-18874368}
case "$TRAINING_MAX_UPLOAD_BYTES" in ''|*[!0-9]*) fail 'TRAINING_MAX_UPLOAD_BYTES must be an integer.' ;; esac
[ "${#TRAINING_MAX_UPLOAD_BYTES}" -le 8 ] && [ "$TRAINING_MAX_UPLOAD_BYTES" -ge 1 ] \
    && [ "$TRAINING_MAX_UPLOAD_BYTES" -le 18874368 ] \
    || fail 'CloudBase uploads must be limited to 18 MiB or less.'
export TRAINING_MAX_UPLOAD_BYTES

require TRAINING_MYSQL_URL "${TRAINING_MYSQL_URL:-}"
require TRAINING_MYSQL_USER "${TRAINING_MYSQL_USER:-}"
require TRAINING_MYSQL_PASSWORD "${TRAINING_MYSQL_PASSWORD:-}"
case "$TRAINING_MYSQL_URL" in jdbc:mysql://*) ;; *) fail 'TRAINING_MYSQL_URL must be a MySQL JDBC URL.' ;; esac
case "$TRAINING_MYSQL_URL" in
    jdbc:mysql://localhost[:/]*|jdbc:mysql://127.*|jdbc:mysql://\[::1\]*)
        fail 'Use the managed MySQL internal address, not a loopback database.' ;;
esac
export SPRING_DATASOURCE_URL="$TRAINING_MYSQL_URL"
export SPRING_DATASOURCE_USERNAME="$TRAINING_MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$TRAINING_MYSQL_PASSWORD"

require WECHAT_APPID "${WECHAT_APPID:-}"
require WECHAT_APP_SECRET "${WECHAT_APP_SECRET:-}"
case "$WECHAT_APPID" in wx*) ;; *) fail 'WECHAT_APPID must be the actual WeChat application ID.' ;; esac
require TRAINING_ADMIN_PASSWORD "${TRAINING_ADMIN_PASSWORD:-}"
[ "$TRAINING_ADMIN_PASSWORD" != 'LocalTrain123!' ] || fail 'The local administrator password is not permitted.'
[ "${#TRAINING_ADMIN_PASSWORD}" -ge 12 ] || fail 'Use an administrator password of at least 12 characters.'
require TRAINING_DATA_KEY "${TRAINING_DATA_KEY:-}"
[ "${#TRAINING_DATA_KEY}" -ge 32 ] || fail 'TRAINING_DATA_KEY must contain at least 32 characters.'
case "$TRAINING_DATA_KEY" in local-only*) fail 'The local encryption key is not permitted.' ;; esac
require TRAINING_PUBLIC_BASE "${TRAINING_PUBLIC_BASE:-}"
printf '%s' "$TRAINING_PUBLIC_BASE" \
    | grep -Eq '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?/?$' \
    || fail 'TRAINING_PUBLIC_BASE must be a public HTTPS origin without path, credentials or query.'

TRAINING_DATA_DIR=${TRAINING_DATA_DIR:-/mnt/training}
case "$TRAINING_DATA_DIR" in /*) ;; *) fail 'TRAINING_DATA_DIR must be an absolute mounted directory.' ;; esac
[ -d "$TRAINING_DATA_DIR" ] || fail 'The persistent media directory does not exist.'
mountpoint -q -- "$TRAINING_DATA_DIR" || fail 'Mount CFS at TRAINING_DATA_DIR before starting; container disk is not persistent storage.'
storage_type=$(findmnt -n -o FSTYPE --target "$TRAINING_DATA_DIR")
case "$storage_type" in nfs|nfs4) ;; *) fail 'This CloudBase deployment requires a persistent CFS/NFS mount.' ;; esac
[ -r "$TRAINING_DATA_DIR" ] && [ -w "$TRAINING_DATA_DIR" ] \
    || fail 'The persistent media directory must be readable and writable by UID/GID 10001.'
export TRAINING_DATA_DIR

for binary in java "${FFPROBE_PATH:-ffprobe}" "${PDFINFO_PATH:-pdfinfo}" "${PDFTOPPM_PATH:-pdftoppm}"; do
    command -v "$binary" > /dev/null 2>&1 || fail 'A required Java or media-processing executable is missing.'
done

printf '%s\n' 'Starting production training service with managed MySQL and persistent media storage.'
exec java -jar /opt/yongsen/app.jar
