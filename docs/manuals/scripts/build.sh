#!/usr/bin/env bash
# builds the manuals: html (one site, both languages) and pdf (one book per manual and language).
# usage: docs/manuals/scripts/build.sh [all|html|pdf|clean]   from any directory; needs ruby + bundler
# output: docs/manuals/output/{html,pdf}. warnings fail the build: a broken xref, include or image is an error.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/output"
LANGS=(es en)
BOOKS=(user-manual admin-manual system-manual)
cd "$ROOT"

bundle_ready() {
    command -v bundle >/dev/null || { echo "build: bundler is missing (gem install bundler)" >&2; exit 1; }
    bundle config set --local path .bundle/gems >/dev/null
    bundle check >/dev/null 2>&1 || bundle install --quiet
}

gem_dir() { bundle exec ruby -e "print Gem.loaded_specs['$1'].full_gem_path"; }

html() {
    bundle_ready
    rm -rf "$OUT/html" && mkdir -p "$OUT/html/theme/fonts"
    for lang in "${LANGS[@]}"; do
        bundle exec asciidoctor --failure-level=WARN \
            -a linkcss -a stylesdir=../theme -a stylesheet=wasichai-docs.css -a toc=left -a docinfo=shared \
            -D "$OUT/html/$lang" "$lang/index.adoc" "${BOOKS[@]/#/$lang/}"
    done
    # the landing page picks the language
    bundle exec asciidoctor --failure-level=WARN -a linkcss -a stylesdir=theme -a stylesheet=wasichai-docs.css \
        -a imagesdir=assets -D "$OUT/html" index.adoc
    cp "$(gem_dir asciidoctor)/data/stylesheets/asciidoctor-default.css" "$OUT/html/theme/"
    cp theme/html/wasichai-docs.css "$OUT/html/theme/"
    cp theme/fonts/web/*.woff2 theme/fonts/OFL-IBM-Plex.txt "$OUT/html/theme/fonts/"
    mkdir -p "$OUT/html/assets"
    cp -r assets/branding assets/diagrams assets/screenshots "$OUT/html/assets/"
    rm -rf "$OUT/html/assets/diagrams/src"
    # the pdfs sit next to the site when they were built first, so the landing page's links resolve
    [[ -d "$OUT/pdf" ]] && cp -r "$OUT/pdf" "$OUT/html/pdf"
    echo "html: $OUT/html/index.html"
}

pdf() {
    bundle_ready
    rm -rf "$OUT/pdf" && mkdir -p "$OUT/pdf"
    local gemfonts
    gemfonts="$(gem_dir asciidoctor-pdf)/data/fonts"
    for lang in "${LANGS[@]}"; do
        for book in "${BOOKS[@]}"; do
            bundle exec asciidoctor-pdf --failure-level=WARN \
                -a pdf-fontsdir="$ROOT/theme/fonts/pdf;$gemfonts" \
                -o "$OUT/pdf/wasichai-$book-$lang.pdf" "$lang/$book.adoc"
            echo "pdf:  $OUT/pdf/wasichai-$book-$lang.pdf"
        done
    done
}

case "${1:-all}" in
    html) html ;;
    pdf) pdf ;;
    all) pdf && html ;;
    # only the generated folder: sources, screenshots and diagrams are kept
    clean) rm -rf "$OUT" && echo "removed $OUT" ;;
    *) echo "usage: $0 [all|html|pdf|clean]" >&2 && exit 2 ;;
esac
