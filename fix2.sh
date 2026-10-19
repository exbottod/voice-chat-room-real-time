#!/usr/bin/env bash
set -e
D=app/src/main/java/com/example/googlelogin
[ -f "$D/Social.kt" ] || { echo "Jalankan di root repo"; exit 1; }

python3 - <<'PY'
D = "app/src/main/java/com/example/googlelogin/"

def patch(path, pairs):
    s = open(path, encoding="utf-8").read()
    if "PERSIST_PUB" in s:
        print("sudah dipatch:", path); return
    for old, new in pairs:
        if old not in s:
            raise SystemExit("GAGAL: teks tidak ditemukan di %s:\n%s" % (path, old[:80]))
        s = s.replace(old, new, 1)
    s = s.replace("package com.example.googlelogin\n", "package com.example.googlelogin\n\n// PERSIST_PUB\n", 1)
    open(path, "w", encoding="utf-8").write(s)
    print("OK:", path)

OLD_CACHE = '''object UserCache {
    private val map = ConcurrentHashMap<String, Pair<Long, PublicUser>>()
    fun peek(uid: String): PublicUser? = map[uid]?.second
    suspend fun byUid(uid: String, force: Boolean = false): PublicUser? {
        val c = map[uid]
        if (!force && c != null && System.currentTimeMillis() - c.first < 300_000) return c.second
        return try {
            UserDirectory.findByUid(uid)?.also { map[uid] = System.currentTimeMillis() to it }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            c?.second
        }
    }
}'''

NEW_CACHE = '''object UserCache {
    private val map = ConcurrentHashMap<String, Pair<Long, PublicUser>>()

    private fun sp() = com.google.firebase.FirebaseApp.getInstance().applicationContext
        .getSharedPreferences("user_pub_cache", android.content.Context.MODE_PRIVATE)

    private fun save(u: PublicUser) {
        try {
            sp().edit().putString(
                u.uid,
                org.json.JSONObject().put("userId", u.userId).put("name", u.name)
                    .put("countryCode", u.countryCode).put("photoUrl", u.photoUrl ?: "")
                    .put("gender", u.gender).put("exp", u.exp).put("vip", u.vip)
                    .put("vipUntil", u.vipUntil).put("frame", u.frame)
                    .put("bubble", u.bubble).put("badge", u.badge).toString(),
            ).apply()
        } catch (e: Exception) { }
    }

    private fun disk(uid: String): PublicUser? = try {
        val o = org.json.JSONObject(sp().getString(uid, null) ?: "")
        PublicUser(
            userId = o.optString("userId"), uid = uid, name = o.optString("name"),
            countryCode = o.optString("countryCode", "ID"), bio = "", avatarB64 = null,
            photoUrl = o.optString("photoUrl").ifBlank { null }, gender = o.optString("gender"),
            exp = o.optLong("exp"), vip = o.optInt("vip"), vipUntil = o.optLong("vipUntil"),
            frame = o.optString("frame"), bubble = o.optString("bubble"), badge = o.optString("badge"),
        )
    } catch (e: Exception) { null }

    private suspend fun rtdb(uid: String, base: PublicUser?): PublicUser? = try {
        val s = kotlinx.coroutines.withTimeoutOrNull(6000L) { Rtdb.ref("pub/$uid").get().await() }
        if (s == null || !s.exists()) null else {
            val b = base ?: PublicUser("", uid, "", "ID", "", null, null)
            b.copy(
                exp = (s.child("exp").value as? Number)?.toLong() ?: b.exp,
                vip = (s.child("vip").value as? Number)?.toInt() ?: b.vip,
                vipUntil = (s.child("vipUntil").value as? Number)?.toLong() ?: b.vipUntil,
                frame = s.child("frame").getValue(String::class.java) ?: b.frame,
                bubble = s.child("bubble").getValue(String::class.java) ?: b.bubble,
                badge = s.child("badge").getValue(String::class.java) ?: b.badge,
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    fun peek(uid: String): PublicUser? = map[uid]?.second ?: disk(uid)

    suspend fun byUid(uid: String, force: Boolean = false): PublicUser? {
        val c = map[uid]
        if (!force && c != null && System.currentTimeMillis() - c.first < 300_000) return c.second
        val fromDb = try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) { UserDirectory.findByUid(uid) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        if (fromDb != null) {
            map[uid] = System.currentTimeMillis() to fromDb
            save(fromDb)
            return fromDb
        }
        val base = c?.second ?: disk(uid)
        val r = rtdb(uid, base) ?: base
        if (r != null) map[uid] = (System.currentTimeMillis() - 240_000L) to r
        return r
    }
}'''

patch(D + "Social.kt", [(OLD_CACHE, NEW_CACHE)])

patch(D + "LevelVip.kt", [
 ("            val key = data.toString()\n            if (lastKey[uid] == key) return\n",
  '''            val key = data.toString()
            if (lastKey[uid] == key) return
            try {
                Rtdb.ref("pub/$uid").setValue(
                    hashMapOf<String, Any?>(
                        "exp" to w.exp, "vip" to w.vip.toLong(), "vipUntil" to w.vipUntil,
                        "frame" to activeEquip(w, "frame"), "bubble" to activeEquip(w, "bubble"),
                        "badge" to activeEquip(w, "badge"),
                    ),
                ).await()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
'''),
])

import json, collections
p = "database.rules.json"
j = json.load(open(p, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)
if "pub" not in j["rules"]:
    j["rules"]["pub"] = collections.OrderedDict([
        (".read", "auth != null"),
        ("$uid", collections.OrderedDict([(".write", "auth != null && auth.uid === $uid")])),
    ])
    json.dump(j, open(p, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print("OK:", p)
PY

git add -A
git commit -m "Frame/VIP user lain tetap tampil publik saat kuota Firestore habis"
git push
