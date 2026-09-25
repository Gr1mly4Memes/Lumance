#!/usr/bin/env sh
# Lumance server - pass extra server args after the jar, e.g. ./run.sh nogui
exec java -Xmx4G -jar lumance-*-server.jar nogui "$@"
