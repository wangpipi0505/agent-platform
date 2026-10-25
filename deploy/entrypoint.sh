#!/bin/sh
set -eu
# An explicit release decision is required; dev-auth never opens a network login here.
test "${AGENT_RELEASE_GATE:-BLOCKED}" = IAM_SCOPE_ACCEPTED || { echo 'Deployment blocked: IAM and service-scope acceptance required.' >&2; exit 78; }
test "${AGENT_DEV_AUTH:-false}" = false || { echo 'dev-auth is forbidden in this deployment.' >&2; exit 78; }
umask 077
exec java -javaagent:/opt/agent/otel-agent.jar -jar /opt/agent/service.jar
