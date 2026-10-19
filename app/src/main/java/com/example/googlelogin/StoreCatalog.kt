package com.example.googlelogin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    fun start(force: Boolean = false) {
        if (reg != null && !force) return
        reg?.remove()
        reg = FirebaseFirestore.getInstance().collection("vipConfig")
            .whereEqualTo("store", true)
            .addSnapshotListener { snap, e ->
                if (e != null || snap == null) return@addSnapshotListener
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
                items.clear()
                items.putAll(fresh)
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
    if (m.entry.startsWith("sx_")) StoreCatalog.find(m.entry)?.asset?.let { return it }
    return if (m.vip in 1..9) VipAssets.get(m.vip, "entry") else null
}

/** Ikon lencana: gambar/svga dari store, atau emoji untuk lencana bawaan. */
@Composable
fun StoreBadgeIcon(item: StoreItem, size: Dp = 22.dp) {
    val a = item.asset
    if (a != null) key(a.updatedAt) { VipMedia(a, Modifier.size(size), loop = true) }
    else Text(item.emoji, fontSize = (size.value * 0.82f).sp)
}
