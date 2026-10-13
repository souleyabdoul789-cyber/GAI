#!/usr/bin/env sh
# Lance Gradle 8.11.1. Utilise une installation système compatible si elle existe,
# sinon télécharge Gradle uniquement pour construire le projet (jamais depuis l'application).
set -eu

VERSION="8.11.1"
DIST="gradle-${VERSION}-bin"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_HOME_BASE/wrapper/dists/$DIST/local"
GRADLE_BIN="$DIST_DIR/gradle-${VERSION}/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_DIR"
    ARCHIVE="$DIST_DIR/$DIST.zip"
    TEMP_DIR="$DIST_DIR/.extract-$$"
    echo "Gradle $VERSION absent : téléchargement du compilateur…" >&2
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 "https://services.gradle.org/distributions/$DIST.zip" --output "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$ARCHIVE" "https://services.gradle.org/distributions/$DIST.zip"
    else
        echo "Installe curl ou wget pour récupérer Gradle." >&2
        exit 1
    fi
    mkdir -p "$TEMP_DIR"
    unzip -q "$ARCHIVE" -d "$TEMP_DIR"
    mv "$TEMP_DIR/gradle-${VERSION}" "$DIST_DIR/gradle-${VERSION}"
    rm -rf "$TEMP_DIR" "$ARCHIVE"
fi

cd "$ROOT"
exec "$GRADLE_BIN" "$@"
