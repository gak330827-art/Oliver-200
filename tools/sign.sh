#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  Oliver-200 · подпись исходников.
#  Считает SHA-256 каждого файла проекта и складывает в SIGNATURES.txt.
#  Проверка целостности дерева:  tools/sign.sh --check
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
OUT="SIGNATURES.txt"

list_files() {
    git ls-files 2>/dev/null | grep -vE '^(SIGNATURES\.txt|gradle/wrapper/gradle-wrapper\.jar)$' | sort
}

generate() {
    {
        echo "# Oliver-200 · подписи исходных файлов (SHA-256)"
        echo "# Сгенерировано: tools/sign.sh"
        echo "# Проверка:      tools/sign.sh --check"
        echo "#"
        list_files | while read -r f; do
            [ -f "$f" ] || continue
            printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
        done
    } > "$OUT"
    echo "Записано подписей: $(grep -vc '^#' "$OUT")  ->  $OUT"
}

check() {
    local failed=0 total=0
    while read -r hash file; do
        case "$hash" in \#*) continue ;; esac
        [ -n "${file:-}" ] || continue
        total=$((total + 1))
        if [ ! -f "$file" ]; then
            echo "ОТСУТСТВУЕТ: $file"
            failed=$((failed + 1))
        elif [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$hash" ]; then
            echo "ИЗМЕНЁН:     $file"
            failed=$((failed + 1))
        fi
    done < "$OUT"
    if [ "$failed" -eq 0 ]; then
        echo "Все $total файлов совпадают с подписями."
    else
        echo "Расхождений: $failed из $total"
        exit 1
    fi
}

case "${1:-}" in
    --check) check ;;
    *)       generate ;;
esac
