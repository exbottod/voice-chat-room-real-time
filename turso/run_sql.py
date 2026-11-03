#!/usr/bin/env python3
"""Jalankan berkas SQL ke Turso lewat HTTP pipeline."""
import json, os, re, sys, urllib.request

URL = "https://rezo-voice-chat-officialex.aws-ap-southeast-1.turso.io"
TOK = os.environ["TURSO_TOKEN"]

def stmts(path):
    txt = open(path, encoding="utf-8").read()
    txt = re.sub(r"--[^\n]*", "", txt)
    return [s.strip() for s in txt.split(";") if s.strip()]

def run(sql_list):
    body = {"requests": [{"type": "execute", "stmt": {"sql": s}} for s in sql_list] + [{"type": "close"}]}
    req = urllib.request.Request(URL + "/v2/pipeline", data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + TOK, "Content-Type": "application/json"})
    j = json.load(urllib.request.urlopen(req, timeout=60))
    for i, r in enumerate(j["results"]):
        if r.get("type") == "error":
            print("ERROR:", r["error"]["message"], "| pada:", sql_list[i][:70])
            sys.exit(1)
    return j

if __name__ == "__main__":
    ls = stmts(sys.argv[1])
    run(ls)
    print(f"selesai: {len(ls)} perintah")
