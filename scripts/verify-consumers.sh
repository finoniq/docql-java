#!/usr/bin/env bash
# scripts/verify-consumers.sh — resolve the released artifact in fresh consumers.
#
# Builds a throwaway Maven project and a throwaway Gradle 9 project that depend
# on com.finoniq:docql:<version>, checks the resolved jar (bytecode major 61 and
# Automatic-Module-Name: com.finoniq.docql), and runs the README quickstart and
# the README jobs example on each project's resolved classpath against the
# pinned stub. Proves D-15 step 1
# (resolve from a local install) and, after the owner presses Publish, the
# Central release itself (D-16); verify-central.yml runs this script with
# --source central.
#
# Usage:
#   scripts/verify-consumers.sh --source local   --repo DIR --version V
#   scripts/verify-consumers.sh --source central            --version V
#
# --source local resolves from the Maven repository at --repo DIR (an
# ./mvnw install target). --source central requires repo1.maven.org to serve
# the version's POM first (CENTRAL_NOT_FOUND otherwise) and resolves into a
# fresh empty repository, so the artifact has to come from Central.
#
# Requires DOCQL_STUB_URL (boot the pinned stub with scripts/stub-up.sh) before
# the README examples run. JAVA_HOME selects the JDK; GRADLE (default "gradle") the
# Gradle binary. Versions are exact strings, never echoed beyond the argument
# check, and no environment values are printed.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

usage() {
  echo "usage: scripts/verify-consumers.sh --source local --repo DIR --version V" >&2
  echo "       scripts/verify-consumers.sh --source central --version V" >&2
  exit 2
}

SOURCE=""
REPO_ARG=""
VERSION=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --source)
      [ "$#" -ge 2 ] || usage
      SOURCE=$2
      shift 2
      ;;
    --repo)
      [ "$#" -ge 2 ] || usage
      REPO_ARG=$2
      shift 2
      ;;
    --version)
      [ "$#" -ge 2 ] || usage
      VERSION=$2
      shift 2
      ;;
    *) usage ;;
  esac
done

VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
if ! [[ "$VERSION" =~ $VERSION_RE ]]; then
  echo "verify-consumers: VERSION is not a plain X.Y.Z release version" >&2
  exit 2
fi

case "$SOURCE" in
  local)
    [ -n "$REPO_ARG" ] || usage
    [ -d "$REPO_ARG" ] || { echo "verify-consumers: --repo is not a directory" >&2; exit 2; }
    LOCAL_REPO=$(cd "$REPO_ARG" && pwd)
    ;;
  central) ;;
  *) usage ;;
esac

# Central mode: the version must be live on repo1.maven.org before anything
# else runs, so a pre-release call fails here instead of mid-verification.
if [ "$SOURCE" = "central" ]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "https://repo1.maven.org/maven2/com/finoniq/docql/$VERSION/docql-$VERSION.pom")
  if [ "$code" != "200" ]; then
    echo "CENTRAL_NOT_FOUND"
    exit 1
  fi
fi

STUB_URL=${DOCQL_STUB_URL:-}
if [ -z "$STUB_URL" ]; then
  echo "DOCQL_STUB_URL is not set: boot the pinned stub (scripts/stub-up.sh)" >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [ "$SOURCE" = "local" ]; then
  MAVEN_LOCAL=$LOCAL_REPO
  GRADLE_REPO_BLOCK="maven { url = uri(\"file://$LOCAL_REPO\") }"
else
  # A fresh empty repository: the jar must come from Central, not a local cache.
  MAVEN_LOCAL="$WORK/central-m2"
  mkdir -p "$MAVEN_LOCAL"
  GRADLE_REPO_BLOCK="mavenCentral()"
fi

# --- Maven consumer ---------------------------------------------------------
CONS_M="$WORK/maven-consumer"
mkdir -p "$CONS_M"
cat > "$CONS_M/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>docql-maven-consumer</artifactId>
  <version>1</version>
  <packaging>jar</packaging>
  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-dependency-plugin</artifactId>
          <version>3.11.0</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
  <dependencies>
    <dependency>
      <groupId>com.finoniq</groupId>
      <artifactId>docql</artifactId>
      <version>$VERSION</version>
    </dependency>
  </dependencies>
</project>
EOF

"$ROOT/mvnw" -B -ntp -q -f "$CONS_M/pom.xml" \
  dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile="$WORK/maven-cp.txt" \
  -Dmaven.repo.local="$MAVEN_LOCAL" > "$WORK/mvn-resolve.log" 2>&1

CP_M=$(cat "$WORK/maven-cp.txt")
if ! grep -q "com/finoniq/docql/$VERSION/docql-$VERSION.jar" "$WORK/maven-cp.txt"; then
  echo "the Maven consumer classpath does not contain docql-$VERSION.jar" >&2
  exit 1
fi

JAR="$MAVEN_LOCAL/com/finoniq/docql/$VERSION/docql-$VERSION.jar"
[ -s "$JAR" ] || { echo "the resolved jar is missing: $JAR" >&2; exit 1; }
python3 -I - "$JAR" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
head = z.open("com/finoniq/docql/DocQL.class").read(8)
major = (head[6] << 8) | head[7]
assert major == 61, "DocQL.class bytecode major version is %d, not 61 (--release 17)" % major
manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8")
entries = {}
for line in manifest.splitlines():
    if ":" in line:
        k, v = line.split(":", 1)
        entries[k.strip()] = v.strip()
assert entries.get("Automatic-Module-Name") == "com.finoniq.docql", manifest
PYEOF
echo "JAR_SHAPE_OK"
echo "MAVEN_CONSUMER_OK"
scripts/run-readme.sh --classpath "$CP_M"
"$ROOT/scripts/run-readme.sh" --block jobs --classpath "$CP_M"

# --- Gradle consumer --------------------------------------------------------
CONS_G="$WORK/gradle-consumer"
mkdir -p "$CONS_G"
cat > "$CONS_G/settings.gradle.kts" <<EOF
rootProject.name = "docql-gradle-consumer"
EOF
cat > "$CONS_G/build.gradle.kts" <<EOF
plugins {
    java
}
repositories {
    $GRADLE_REPO_BLOCK
}
dependencies {
    implementation("com.finoniq:docql:$VERSION")
}
tasks.register("printRuntimeClasspath") {
    doLast {
        val cp = configurations.runtimeClasspath.get().files.joinToString(":") { it.absolutePath }
        println("CP=" + cp)
    }
}
EOF

GRADLE_BIN=${GRADLE:-gradle}
if ! GRADLE_USER_HOME="$WORK/gradle-home" "$GRADLE_BIN" -q --no-daemon --no-configuration-cache \
    -p "$CONS_G" printRuntimeClasspath > "$WORK/gradle-out.txt" 2> "$WORK/gradle-err.txt"; then
  cat "$WORK/gradle-err.txt" >&2
  echo "the Gradle consumer failed to resolve" >&2
  exit 1
fi

CP_G=$(sed -n 's/^CP=//p' "$WORK/gradle-out.txt" | tail -n 1)
[ -n "$CP_G" ] || { echo "the Gradle consumer printed no runtime classpath" >&2; exit 1; }
if ! printf '%s' "$CP_G" | grep -q "docql-$VERSION.jar"; then
  echo "the Gradle consumer classpath does not contain docql-$VERSION.jar" >&2
  exit 1
fi
echo "GRADLE_CONSUMER_OK"
scripts/run-readme.sh --classpath "$CP_G"
"$ROOT/scripts/run-readme.sh" --block jobs --classpath "$CP_G"

echo "CONSUMERS_OK"
