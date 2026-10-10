#!/usr/bin/env bash
# scripts/release-precheck.sh — refuse every wrong release tag before the owner approval gate.
#
# Usage (TAG comes from the environment; GITHUB_OUTPUT is optional, Actions sets it):
#   TAG=v0.1.0 scripts/release-precheck.sh          # tag mode (release.yml)
#   scripts/release-precheck.sh --dry-run           # dry-run mode (dry-run.yml, D-15/D-18)
#
# Prints (and, when GITHUB_OUTPUT is set, writes) "version=<X.Y.Z>" and
# "sha=<commit>". Exit codes: 2 = the tag is not a plain vX.Y.Z tag (or the
# arguments are wrong); 1 = a precondition failed (DocQL.java does not say
# "v" + the tag's version, the POM project version differs, no dated CHANGELOG
# entry, the tag does not resolve to a commit, or that commit is not on
# origin/main — D-13; in dry-run mode HEAD is not origin/main).
#
# Versions are compared as exact strings: no case folding and no normalisation
# beyond removing the tag's single leading "v". The build reads sources as
# UTF-8 (project.build.sourceEncoding) whatever the platform charset.
#
# Whether the tag is annotated, whether the commit has green CI on main, and
# whether Maven Central already has the version are checked by release.yml /
# dry-run.yml through the GitHub API and repo1.maven.org, because a checkout of
# a tag ref may not keep the tag object and this script contacts no registry:
# the first fetch of a version on Central is permanent (D-16), so it must only
# ever follow the owner's approval.
#
# The tag is attacker-influenced text that reaches this script, so it is only
# ever matched against an anchored regex and never echoed back (T-13-27).
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

TAG_RE='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  *)
    echo "release-precheck: usage: TAG=vX.Y.Z scripts/release-precheck.sh | scripts/release-precheck.sh --dry-run" >&2
    exit 2
    ;;
esac
if [ "$#" -gt 1 ]; then
  echo "release-precheck: usage: TAG=vX.Y.Z scripts/release-precheck.sh | scripts/release-precheck.sh --dry-run" >&2
  exit 2
fi

if [ "$DRY_RUN" -eq 1 ]; then
  # Dry-run mode (D-15 step 2): the version comes from DocQL.java and the
  # dispatch deploys from main, so HEAD must be origin/main (D-18).
  if ! git -C "$ROOT" rev-parse --verify --quiet "refs/remotes/origin/main^{commit}" >/dev/null; then
    echo "release-precheck: origin/main is not available in this checkout" >&2
    exit 1
  fi
  if [ "$(git -C "$ROOT" rev-parse HEAD)" != "$(git -C "$ROOT" rev-parse refs/remotes/origin/main^{commit})" ]; then
    echo "release-precheck: HEAD is not origin/main (the dry run deploys from main, D-18)" >&2
    exit 1
  fi
  SHA=$(git -C "$ROOT" rev-parse HEAD)
else
  TAG_VALUE=${TAG:-}
  if ! [[ "$TAG_VALUE" =~ $TAG_RE ]]; then
    echo "release-precheck: TAG is not a vX.Y.Z tag" >&2
    exit 2
  fi
  VERSION=${TAG_VALUE#v}
  if ! SHA=$(git -C "$ROOT" rev-parse --verify "refs/tags/$TAG_VALUE^{commit}" 2>/dev/null); then
    echo "release-precheck: the tag does not resolve to a commit" >&2
    exit 1
  fi
fi

# The committed VERSION constant must already be the tag's version.
FILE_VERSION=$(sed -nE 's/^[[:space:]]*public static final String VERSION = "([^"]+)";[[:space:]]*$/\1/p' \
  "$ROOT/src/main/java/com/finoniq/docql/DocQL.java")
if [ -z "$FILE_VERSION" ]; then
  echo "release-precheck: cannot read VERSION from DocQL.java" >&2
  exit 1
fi
if [ "$DRY_RUN" -eq 1 ]; then
  VERSION=$FILE_VERSION
  if ! [[ "v$VERSION" =~ $TAG_RE ]]; then
    echo "release-precheck: DocQL.java VERSION is not a plain release version" >&2
    exit 1
  fi
elif [ "$FILE_VERSION" != "$VERSION" ]; then
  echo "release-precheck: DocQL.java does not say \"v\" + the tag's version" >&2
  exit 1
fi

# The POM's own project <version> (the direct child of <project>) must match too.
POM_VERSION=$(python3 -I -c '
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for child in root:
    if child.tag.rsplit("}", 1)[-1] == "version":
        sys.stdout.write((child.text or "").strip())
        break
' "$ROOT/pom.xml")
if [ "$POM_VERSION" != "$VERSION" ]; then
  echo "release-precheck: the POM project version does not match the tag's version" >&2
  exit 1
fi

# The CHANGELOG entry is reviewed on main before any tag is pushed.
VERSION_ESCAPED=${VERSION//./\\.}
if ! grep -Eq "^## \\[$VERSION_ESCAPED\\] - [0-9]{4}-[0-9]{2}-[0-9]{2}\$" "$ROOT/CHANGELOG.md"; then
  echo "release-precheck: CHANGELOG.md has no dated entry for the tag's version" >&2
  exit 1
fi

# Tag mode: the tagged commit must be on origin/main.
if [ "$DRY_RUN" -ne 1 ]; then
  if ! git -C "$ROOT" rev-parse --verify --quiet "refs/remotes/origin/main^{commit}" >/dev/null; then
    echo "release-precheck: origin/main is not available in this checkout" >&2
    exit 1
  fi
  if ! git -C "$ROOT" merge-base --is-ancestor "$SHA" "refs/remotes/origin/main"; then
    echo "release-precheck: the tagged commit is not on origin/main" >&2
    exit 1
  fi
fi

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    printf 'version=%s\n' "$VERSION"
    printf 'sha=%s\n' "$SHA"
  } >> "$GITHUB_OUTPUT"
fi
printf 'version=%s\n' "$VERSION"
printf 'sha=%s\n' "$SHA"
