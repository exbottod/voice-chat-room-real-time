#!/bin/bash
echo "== Caddyfile"
cat /etc/caddy/Caddyfile 2>/dev/null | head -60
echo "== rute server.mjs (baris berisi app./get/post/path)"
grep -nE "app\.(get|post|use)|req\.url|url\.pathname|listen\(" /opt/rezo-agora/server.mjs 2>/dev/null | head -30
echo "== port mendengarkan"
ss -lntup 2>/dev/null | grep -E "node|caddy|listen" | head -15
