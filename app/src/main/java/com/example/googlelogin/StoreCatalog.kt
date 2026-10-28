package com.example.googlelogin

import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/** Item Store yang diatur dari panel developer (disimpan di vipConfig dengan store=true). */
data class CatalogItem(
    val id: String,
    val slot: String,
    val name: String,
    val price: Long,
    val days: Long,
    val order: Long,
    val asset: VipAsset,
) {
    fun toStoreItem() = StoreItem("sx_$id", slot, name, "\uD83C\uDF81", price, days, Color(0xFFD4AF37), 0, asset)
}

object StoreCatalog {
    private val items = mutableStateMapOf<String, CatalogItem>()
    private var reg: ListenerRegistration? = null
    // UTAMA: katalog store kini dari Realtime Database (vipCfg), Firestore jadi cadangan.
    private var rtdbReg: Reg? = null
    private val fsMap = HashMap<String, CatalogItem>()
    private val rtdbMap = HashMap<String, CatalogItem>()

    private fun apply() {
        val merged = HashMap<String, CatalogItem>()
        merged.putAll(fsMap)
        merged.putAll(rtdbMap) // RTDB selalu menang
        items.clear()
        items.putAll(merged)
        saveDisk(merged.values)
    }

    // Katalog disimpan juga di perangkat. Saat app dibuka / Firestore lambat
    // (kuota habis), frame & lencana kustom tetap langsung tampil tanpa nunggu.
    private fun sp() = com.google.firebase.FirebaseApp.getInstance().applicationContext
        .getSharedPreferences("store_catalog", android.content.Context.MODE_PRIVATE)
    private var diskLoaded = false

    private fun saveDisk(all: Collection<CatalogItem>) {
        try {
            val arr = org.json.JSONArray()
            for (it in all) arr.put(
                org.json.JSONObject()
                    .put("id", it.id).put("slot", it.slot).put("name", it.name)
                    .put("price", it.price).put("days", it.days).put("order", it.order)
                    .put("kind", it.asset.kind).put("url", it.asset.url)
                    .put("hasData", it.asset.hasData).put("updatedAt", it.asset.updatedAt)
                    .put("chunks", it.asset.chunks),
            )
            sp().edit().putString("catalog", arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    private fun loadDisk() {
        if (diskLoaded) return
        diskLoaded = true
        try {
            val arr = org.json.JSONArray(sp().getString("catalog", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                items[id] = CatalogItem(
                    id, o.getString("slot"), o.getString("name"),
                    o.optLong("price"), o.optLong("days", 7L).coerceAtLeast(1L), o.optLong("order"),
                    VipAsset(
                        0, "st_$id", o.optString("kind", "image"), o.optString("url"),
                        o.optBoolean("hasData"), o.optLong("updatedAt"), o.optInt("chunks"),
                    ),
                )
            }
        } catch (e: Exception) {
        }
    }

    fun start(force: Boolean = false) {
        loadDisk()
        if (reg != null && !force) return
        reg?.remove()
        rtdbReg?.remove()
        // REALTIME DATABASE (utama): node vipCfg/{store_id}.
        rtdbReg = Rtdb.listen(Rtdb.ref("vipCfg")) { snap ->
            rtdbMap.clear()
            for (c in snap.children) {
                val id = c.child("id").getValue(String::class.java) ?: continue
                if (c.child("store").getValue(Boolean::class.java) != true) continue
                val slot = c.child("slot").getValue(String::class.java) ?: continue
                val asset = VipAsset(
                    0, "st_$id",
                    c.child("kind").getValue(String::class.java) ?: "image",
                    c.child("url").getValue(String::class.java) ?: "",
                    c.child("hasData").getValue(Boolean::class.java) ?: false,
                    c.child("updatedAt").getValue(Long::class.java) ?: 0L,
                    (c.child("chunks").getValue(Long::class.java) ?: 0L).toInt(),
                )
                rtdbMap[id] = CatalogItem(
                    id, slot,
                    c.child("name").getValue(String::class.java) ?: id,
                    c.child("price").getValue(Long::class.java) ?: 0L,
                    (c.child("days").getValue(Long::class.java) ?: 7L).coerceAtLeast(1L),
                    c.child("order").getValue(Long::class.java) ?: 0L,
                    asset,
                )
            }
            apply()
        }
        // FIRESTORE (warisan lama): dimuat SEKALI per buka app, bukan listener
        // realtime, supaya hemat kuota baca harian Firestore.
        kotlinx.coroutines.GlobalScope.launch {
            try {
                val snap = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                    FirebaseFirestore.getInstance().collection("vipConfig")
                        .whereEqualTo("store", true).get().await()
                }
                if (snap != null) {
                    val fresh = HashMap<String, CatalogItem>()
                    for (d in snap.documents) {
                        val id = d.getString("id") ?: continue
                        val slot = d.getString("slot") ?: continue
                        val asset = VipAsset(
                            0, "st_$id",
                            d.getString("kind") ?: "image",
                            d.getString("url") ?: "",
                            d.getBoolean("hasData") ?: false,
                            d.getLong("updatedAt") ?: 0L,
                            (d.getLong("chunks") ?: 0L).toInt(),
                        )
                        fresh[id] = CatalogItem(
                            id, slot,
                            d.getString("name") ?: id,
                            d.getLong("price") ?: 0L,
                            (d.getLong("days") ?: 7L).coerceAtLeast(1L),
                            d.getLong("order") ?: 0L,
                            asset,
                        )
                    }
                    fsMap.clear()
                    fsMap.putAll(fresh)
                    apply()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                QuotaGuard.handle(e)
            }
        }
    }

    fun bySlot(slot: String): List<CatalogItem> =
        items.values.filter { it.slot == slot }.sortedWith(compareBy<CatalogItem>({ it.order }, { it.name }))

    fun find(itemId: String?): CatalogItem? =
        if (itemId != null && itemId.startsWith("sx_")) items[itemId.removePrefix("sx_")] else null
}

/** Aset bingkai yang harus dipasang di avatar: item store (sx_) dulu, lalu bingkai VIP. */
fun frameAssetOf(frameId: String?, vip: Int, until: Long): VipAsset? {
    if (!frameId.isNullOrBlank() && frameId.startsWith("sx_")) return StoreCatalog.find(frameId)?.asset
    val tier = vipFrameTier(frameId, vip, until)
    return if (tier in 1..9) VipAssets.get(tier, "frame") else null
}

@Composable
fun FrameOverlay(a: VipAsset, size: Dp) {
    key(a.updatedAt) { VipMedia(a, Modifier.requiredSize(size), loop = true) }
}

/** Efek masuk untuk satu pesan "masuk kamar": item store yang dipakai dulu, lalu efek VIP. */
fun entryAssetOf(m: RoomMsg): VipAsset? {
    // Item store (sx_) yang dipakai user HARUS menang:
    // jangan fallback ke efek VIP default kalau katalog belum termuat,
    // biarkan pemanggil mencoba lagi (mencegah efek masuk "beda-beda").
    if (m.entry.startsWith("sx_")) return StoreCatalog.find(m.entry)?.asset
    return if (m.vip in 1..9) VipAssets.get(m.vip, "entry") else null
}

/**
 * Ikon lencana: gambar/svga dari store, atau emoji untuk lencana bawaan.
 * Tinggi selalu 'size', lebar otomatis mengikuti rasio file asli,
 * jadi lencana panjang (mis. SVIP9) tampil utuh tanpa mengecil tergencet.
 */
@Composable
fun StoreBadgeIcon(item: StoreItem, size: Dp = 24.dp) {
    val a = item.asset
    if (a == null) {
        Text(item.emoji, fontSize = (size.value * 0.82f).sp)
        return
    }
    
    var ratio by remember(a.n, a.slot, a.updatedAt) { mutableStateOf(2.5f) }
    val ctx = LocalContext.current
    LaunchedEffect(a.n, a.slot, a.updatedAt) {
        ratio = runCatching { measureAssetRatio(ctx, a) }.getOrNull() ?: 2.5f
    }
    VipMedia(a, Modifier.height(size).width((size.value * ratio).dp), loop = true)
}
