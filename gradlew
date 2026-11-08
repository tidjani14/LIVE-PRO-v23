#!/usr/bin/env bash
if [ -x /opt/gradle/gradle-8.6/bin/gradle ]; then
  exec /opt/gradle/gradle-8.6/bin/gradle "$@"
else
  exec gradle "$@"
fi
