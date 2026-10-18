package com.example.googlelogin

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object AppLang {
    const val ID = "id"
    const val EN = "en"
    const val ZH = "zh"

    var code by mutableStateOf(ID)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        code = prefs(ctx).getString("lang", ID) ?: ID
    }

    fun set(ctx: Context, c: String) {
        code = c
        prefs(ctx).edit().putString("lang", c).apply()
    }

    fun label(c: String = code): String = when (c) {
        EN -> "English"
        ZH -> "中文"
        else -> "Indonesia"
    }

    fun labelWithFlag(c: String): String = when (c) {
        EN -> "🇬🇧  English"
        ZH -> "🇨🇳  中文 (China)"
        else -> "🇮🇩  Indonesia"
    }
}

/** Terjemahan: kunci = teks asli (Indonesia). Teks yang tidak ada di kamus tampil apa adanya. */
fun tr(s: String): String {
    val c = AppLang.code
    if (c == AppLang.ID) return s
    val p = TR_MAP[s] ?: return s
    return if (c == AppLang.EN) p.first else p.second
}

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pilih bahasa")) },
        text = {
            Column {
                listOf(AppLang.ID, AppLang.EN, AppLang.ZH).forEach { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { AppLang.set(ctx, c); onDismiss() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = AppLang.code == c, onClick = { AppLang.set(ctx, c); onDismiss() })
                        Spacer(Modifier.width(8.dp))
                        Text(AppLang.labelWithFlag(c))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Batal")) } },
    )
}

private val TR_MAP: Map<String, Pair<String, String>> = mapOf(
    "+ Tambah foto" to ("+ Add photo" to "+ 添加照片"),
    "Akun" to ("Account" to "账号"),
    "Alat dasar" to ("Basic tools" to "基础工具"),
    "Apa yang sedang kamu pikirkan?" to ("What's on your mind?" to "你在想什么？"),
    "Background room" to ("Room background" to "房间背景"),
    "Bagikan link" to ("Share link" to "分享链接"),
    "Batal" to ("Cancel" to "取消"),
    "Belum ada aktivitas" to ("No activity yet" to "暂无活动"),
    "Belum ada hasil pencarian" to ("No search results yet" to "暂无搜索结果"),
    "Belum ada komentar" to ("No comments yet" to "暂无评论"),
    "Belum diatur" to ("Not set" to "未设置"),
    "Berikan" to ("Send" to "赠送"),
    "Buat" to ("Create" to "创建"),
    "Buat moment" to ("Create moment" to "发布动态"),
    "Buat room" to ("Create room" to "创建房间"),
    "Buka kunci mic" to ("Unlock mic" to "解锁麦位"),
    "Cari negara" to ("Search country" to "搜索国家"),
    "Cari pengguna" to ("Search users" to "搜索用户"),
    "Cari room atau ID" to ("Search room or ID" to "搜索房间或ID"),
    "Dompet" to ("Wallet" to "钱包"),
    "Don't have an account?" to ("Don't have an account?" to "还没有账号？"),
    "Edit profil" to ("Edit profile" to "编辑资料"),
    "Foto room" to ("Room photo" to "房间照片"),
    "Hadiah" to ("Gifts" to "礼物"),
    "Hapus foto" to ("Delete photo" to "删除照片"),
    "ID pengguna" to ("User ID" to "用户ID"),
    "Ikuti" to ("Follow" to "关注"),
    "Ini profil kamu" to ("This is your profile" to "这是你的资料"),
    "Isi" to ("Top up" to "充值"),
    "Isi coin" to ("Top up coins" to "充值金币"),
    "Jadikan publik" to ("Make public" to "设为公开"),
    "Jenis kelamin" to ("Gender" to "性别"),
    "Jumlah diamond" to ("Diamond amount" to "钻石数量"),
    "Jumlah mic" to ("Number of mics" to "麦位数量"),
    "Kamu perlu login lagi untuk memakai aplikasi." to ("You need to sign in again to use the app." to "需要重新登录才能使用应用。"),
    "Kebijakan privasi" to ("Privacy policy" to "隐私政策"),
    "Keluar" to ("Sign out" to "退出"),
    "Keluar dari akun?" to ("Sign out of your account?" to "退出账号？"),
    "Keluar dari room?" to ("Leave the room?" to "离开房间？"),
    "Ketik pesan..." to ("Type a message..." to "输入消息..."),
    "Ketuk untuk lihat profil lengkap ›" to ("Tap to view full profile ›" to "点击查看完整资料 ›"),
    "Kode undangan kamu" to ("Your invite code" to "你的邀请码"),
    "Konfirmasi" to ("Confirm" to "确认"),
    "Kunci kamar" to ("Lock room" to "锁定房间"),
    "Kunci mic" to ("Lock mic" to "锁定麦位"),
    "Level user" to ("User level" to "用户等级"),
    "Lihat profil ›" to ("View profile ›" to "查看资料 ›"),
    "Masuk" to ("Enter" to "进入"),
    "Masukkan ID pengguna" to ("Enter user ID" to "输入用户ID"),
    "Masukkan kode undangan" to ("Enter invite code" to "输入邀请码"),
    "Mengikuti ✓" to ("Following ✓" to "已关注 ✓"),
    "Minimalkan" to ("Minimize" to "最小化"),
    "Mode MC acara" to ("Event MC mode" to "活动主持模式"),
    "Moment" to ("Moment" to "动态"),
    "Nama room" to ("Room name" to "房间名称"),
    "Obrolan" to ("Chat" to "聊天"),
    "Password (min. 4 karakter)" to ("Password (min. 4 characters)" to "密码（至少4位）"),
    "Password (min. 6 karakter)" to ("Password (min. 6 characters)" to "密码（至少6位）"),
    "Pengaturan" to ("Settings" to "设置"),
    "Pengaturan room" to ("Room settings" to "房间设置"),
    "Pengguna lain harus memasukkan password untuk masuk." to ("Other users must enter the password to join." to "其他用户需输入密码才能进入。"),
    "Pengumuman room" to ("Room announcement" to "房间公告"),
    "Pilih mode party" to ("Choose party mode" to "选择派对模式"),
    "Pilih negara" to ("Choose country" to "选择国家"),
    "Posisi kursi host akan berubah jika mengganti jumlah mic" to ("The host seat position changes if you change the number of mics" to "更改麦位数量后主持位置会改变"),
    "Profil tidak ditemukan" to ("Profile not found" to "未找到资料"),
    "Punya kode undangan?" to ("Have an invite code?" to "有邀请码吗？"),
    "Room terkunci" to ("Room locked" to "房间已锁定"),
    "Salin ID" to ("Copy ID" to "复制ID"),
    "Salin kode" to ("Copy code" to "复制代码"),
    "Seat mic" to ("Mic seat" to "麦位"),
    "Segera hadir" to ("Coming soon" to "即将推出"),
    "Simpan" to ("Save" to "保存"),
    "Tambah email & password" to ("Add email & password" to "添加邮箱和密码"),
    "Tukar" to ("Exchange" to "兑换"),
    "Tukar diamond ke coin" to ("Exchange diamonds to coins" to "钻石兑换金币"),
    "Tukar semua" to ("Exchange all" to "全部兑换"),
    "Tulis komentar..." to ("Write a comment..." to "写评论..."),
    "Tulis pesan..." to ("Write a message..." to "写消息..."),
    "Turun Mic" to ("Leave mic" to "下麦"),
    "Turun mic" to ("Leave mic" to "下麦"),
    "Turunkan" to ("Remove from mic" to "请下麦"),
    "Turunkan dari kursi" to ("Remove from seat" to "请下麦位"),
    "Ulangi password" to ("Repeat password" to "重复密码"),
    "Undang teman" to ("Invite friends" to "邀请好友"),
    "or continue with" to ("or continue with" to "或使用以下方式继续"),
    // navigasi & tab
    "Party" to ("Party" to "派对"),
    "Family" to ("Family" to "家族"),
    "Pesan" to ("Messages" to "消息"),
    "Saya" to ("Me" to "我"),
    "Aktivitas" to ("Activity" to "活动"),
    "Cashback" to ("Cashback" to "返现"),
    "Topup" to ("Top up" to "充值"),
    // fitur baru
    "Bahasa" to ("Language" to "语言"),
    "Pilih bahasa" to ("Choose language" to "选择语言"),
    "Semua" to ("All" to "全部"),
    "Belum ada user di mic" to ("No users on mic" to "麦上暂无用户"),
    "Pilih penerima dulu" to ("Select a recipient first" to "请先选择接收者"),
    "Kamar" to ("Rooms" to "房间"),
    "Sawer" to ("Gifters" to "富豪"),
    "Pesona" to ("Charm" to "魅力"),
    "Harian" to ("Daily" to "日榜"),
    "Mingguan" to ("Weekly" to "周榜"),
    "Bulanan" to ("Monthly" to "月榜"),
    "Hitung mundur" to ("Countdown" to "倒计时"),
    "Belum ada data" to ("No data yet" to "暂无数据"),
    "Memuat..." to ("Loading..." to "加载中..."),
)
