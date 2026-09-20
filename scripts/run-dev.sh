#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -d "$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home" ]; then
  export JAVA_HOME="$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home"
fi
if [ -x "$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn" ]; then
  MAVEN_BIN="${MAVEN_BIN:-$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn}"
else
  MAVEN_BIN="${MAVEN_BIN:-$(command -v mvn || true)}"
fi
if [ ! -x "$MAVEN_BIN" ]; then
  echo "Cannot find Maven. Install Maven or set MAVEN_BIN."
  exit 1
fi
if [ "${AI_PROVIDER:-deepseek}" = "deepseek" ] && [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "Set DEEPSEEK_API_KEY before starting MindBridge."
  exit 1
fi

exec "$MAVEN_BIN" -Dmaven.repo.local=.m2/repository spring-boot:run
