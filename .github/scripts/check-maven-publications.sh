#!/usr/bin/env bash
# what `./gradlew publish` would upload, checked before it does: publish to a throwaway local
# repository first, then compare the artifact ids with the list below. a new library fails here
# until someone adds it on purpose; the integration tests leaking in fail here too.
# usage: check-maven-publications.sh <local-repo-dir>   (the dir given to -Dmaven.repo.local)
set -euo pipefail

repo="${1:?usage: check-maven-publications.sh <local-repo-dir>}"

expected="wasichai-agent
wasichai-automation
wasichai-bom
wasichai-core
wasichai-documents
wasichai-forms
wasichai-gis
wasichai-notifications
wasichai-pages
wasichai-spring-boot-starter
wasichai-spring-boot-starter-agent
wasichai-spring-boot-starter-automation
wasichai-spring-boot-starter-documents
wasichai-spring-boot-starter-forms
wasichai-spring-boot-starter-gis
wasichai-spring-boot-starter-notifications
wasichai-spring-boot-starter-pages
wasichai-spring-boot-starter-views
wasichai-spring-boot-starter-workflow
wasichai-test
wasichai-views
wasichai-workflow"

if [ ! -d "$repo/wasichai" ]; then
  echo "maven publications: nothing under $repo/wasichai" >&2
  exit 1
fi

actual="$(ls "$repo/wasichai" | LC_ALL=C sort)"
if [ "$actual" != "$(printf '%s\n' "$expected" | LC_ALL=C sort)" ]; then
  echo "maven publications differ from the expected set (< expected, > actual):" >&2
  diff <(printf '%s\n' "$expected" | LC_ALL=C sort) <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi
echo "maven publications: ok ($(printf '%s\n' "$actual" | wc -l | tr -d ' '))"
