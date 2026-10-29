#!/bin/sh
# One-step build. Needs JDK 21 and Gradle 9.x on PATH (only for the first run, to create the wrapper).
set -e
[ -f gradlew ] || gradle wrapper
./gradlew build
echo
echo "Done: build/libs/apex-1.2.1.jar"
