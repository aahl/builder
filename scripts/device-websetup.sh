#!/system/bin/sh
# Modified or added for XiaoSeek LX04 Web Setup. See NOTICE.
# SPDX-License-Identifier: Apache-2.0
# Run on the speaker over an authenticated root ADB shell to start the LAN-only
# browser setup page. No credentials are passed on the command line.
set -eu
umask 077
APP=/data/adb/lx04-deepseek
cd "$APP"
[ -x ./lx04-websetup ] || { echo "lx04-websetup missing" >&2; exit 2; }
[ -x ./lx04-bootstrap ] || { echo "lx04-bootstrap missing" >&2; exit 2; }

# Stop a running agent so it does not traffic in the same config/token while
# setup rewrites them. The supervisor in run.sh keeps retrying and will start a
# fresh agent once the new config and .mi.token are in place.
if [ -r agent.pid ]; then
    oldpid=$(cat agent.pid 2>/dev/null || true)
    case "$oldpid" in
        ''|*[!0-9]*) ;;
        *) kill "$oldpid" 2>/dev/null || true ;;
    esac
fi

export HOME="$APP"
export SSL_CERT_DIR=/system/etc/security/cacerts
# Use the DNS already learned by device-run.sh when present.
if [ -r dns-server.txt ]; then
    XIAOBOT_DNS_SERVER=$(cat dns-server.txt)
    export XIAOBOT_DNS_SERVER
fi

echo "Starting web setup; read $APP/websetup.url for the one-time URL."
exec ./lx04-websetup \
    -config "$APP/config.json" \
    -device-file "$APP/device-match.json" \
    -listen 0.0.0.0:9997
