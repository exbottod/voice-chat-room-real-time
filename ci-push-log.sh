#!/bin/bash
# Simpan log build ke branch buildlog supaya bisa dibaca saat build gagal.
cd "$(dirname "$0")" || exit 0
F="build.log"
[ -f "$F" ] || F="$(ls -t */build.log 2>/dev/null | head -1)"
[ -f "$F" ] || exit 0
cp "$F" LOG-BUILD.txt || exit 0
git config user.name "build-bot"
git config user.email "build-bot@users.noreply.github.com"
git add -f LOG-BUILD.txt >/dev/null 2>&1
git commit -qm "log build" >/dev/null 2>&1
git push -f origin HEAD:buildlog >/dev/null 2>&1
exit 0
