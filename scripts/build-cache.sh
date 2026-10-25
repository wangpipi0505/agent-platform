#!/bin/sh
# Only run inside the new AgentPlatformV12 distro; never touches another distro or global Windows tools.
set -eu
archive="$1"
prefix=/opt/agent-platform/redis-7.2.16
if [ -x "$prefix/src/redis-server" ]; then
    "$prefix/src/redis-server" --version
    exit 0
fi
mkdir -p /opt/agent-platform
tar -xzf "$archive" -C /opt/agent-platform
make -C "$prefix" -j4 MALLOC=libc OPT=-O2 redis-server redis-cli
if ! id redis >/dev/null 2>&1; then
    addgroup -S redis
    adduser -S -D -H -G redis redis
fi
mkdir -p /var/lib/agent-platform-redis7
chown redis:redis /var/lib/agent-platform-redis7
"$prefix/src/redis-server" --version
