#!/bin/sh
# Publishes the current commit: builds it, pushes, and makes the GitHub release (tag v<versionCode>, APK attached).
set -e
cd "$(dirname "$0")"
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first: the version is the commit count." >&2; exit 1; }
./build.sh
VERSION=$(git rev-list --count HEAD)
git push origin HEAD
gh release create "v$VERSION" build/rxVision.apk --title "v$VERSION" --notes "$(git log -1 --format=%s)"
echo "Released v$VERSION"
