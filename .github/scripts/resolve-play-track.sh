#!/usr/bin/env bash
# Resolves Google Play track from a semver tag vs the previous semver tag.
# - Patch-only bump (e.g. 0.0.4 → 0.0.5): beta
# - Minor or major bump (e.g. 0.0.4 → 0.1.0, 0.1.0 → 1.0.0): production
# - First semver tag: beta
set -euo pipefail

TAG="${1:?usage: resolve-play-track.sh <vX.Y.Z>}"
VERSION="${TAG#v}"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Tag must be semver vMAJOR.MINOR.PATCH (got: $TAG)" >&2
  exit 1
fi

CUR_MAJ="${VERSION%%.*}"
REST="${VERSION#*.}"
CUR_MIN="${REST%%.*}"
CUR_PAT="${REST#*.}"

PREV_TAG="$(
  git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname \
    | grep -vFx "$TAG" \
    | head -1 \
    || true
)"

if [ -z "$PREV_TAG" ]; then
  TRACK="beta"
  REASON="no previous semver tag"
else
  PREV_VER="${PREV_TAG#v}"
  PREV_MAJ="${PREV_VER%%.*}"
  PREV_REST="${PREV_VER#*.}"
  PREV_MIN="${PREV_REST%%.*}"
  PREV_PAT="${PREV_REST#*.}"

  if [ "$CUR_MAJ" != "$PREV_MAJ" ] || [ "$CUR_MIN" != "$PREV_MIN" ]; then
    TRACK="production"
    REASON="minor or major bump ($PREV_TAG → $TAG)"
  else
    TRACK="beta"
    REASON="patch-only bump ($PREV_TAG → $TAG)"
  fi
fi

echo "Resolved Play track: $TRACK ($REASON)"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "track=$TRACK" >> "$GITHUB_OUTPUT"
  echo "version=$VERSION" >> "$GITHUB_OUTPUT"
  echo "previous_tag=${PREV_TAG:-}" >> "$GITHUB_OUTPUT"
fi
