#!/usr/bin/env bash
# Ensures the git tag matches app/build.gradle.kts versionName.
set -euo pipefail

TAG="${1:?usage: validate-release-version.sh <vX.Y.Z>}"
VERSION="${TAG#v}"

GRADLE_VERSION="$(
  grep -E '^\s*versionName\s*=' app/build.gradle.kts \
    | head -1 \
    | sed -E 's/.*"([^"]+)".*/\1/'
)"

if [ "$GRADLE_VERSION" != "$VERSION" ]; then
  echo "Tag $TAG does not match versionName \"$GRADLE_VERSION\" in app/build.gradle.kts" >&2
  echo "Bump versionName (and versionCode) before tagging." >&2
  exit 1
fi

echo "versionName matches tag: $VERSION"
