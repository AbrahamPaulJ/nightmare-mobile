#!/usr/bin/env bash
# Doc size budgets (CLAUDE.md doc rules 8-10). Prints every doc with its line count and
# flags those over budget or missing a Contents list. Exit 1 if anything is over.
cd "$(dirname "$0")/.." || exit 2
over=0
check() { # file budget
  [ -f "$1" ] || return
  local n; n=$(wc -l < "$1")
  local flag=""
  [ "$n" -gt "$2" ] && { flag=" OVER ($2)"; over=1; }
  [ "$n" -gt 150 ] && [ "$1" != CLAUDE.md ] && [ "$1" != README.md ] && \
    ! head -40 "$1" | grep -qi '^## Contents' && flag="$flag no-Contents"
  printf '%5d  %s%s\n' "$n" "$1" "$flag"
}
check CLAUDE.md 150
check notes/PROGRESS.md 300
check notes/HANDOFF.md 500
for f in docs/*.md backend-patches/README.md; do check "$f" 1000; done
exit $over
