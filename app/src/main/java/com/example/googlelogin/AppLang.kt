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
    const val HI = "hi"
    const val AR = "ar"

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
        HI -> "हिन्दी"
        AR -> "العربية"
        else -> "Indonesia"
    }

    fun labelWithFlag(c: String): String = when (c) {
        EN -> "🇬🇧  English"
        ZH -> "🇨🇳  中文 (China)"
        HI -> "🇮🇳  हिन्दी (India)"
        AR -> "🇸🇦  العربية (Arab Saudi)"
        else -> "🇮🇩  Indonesia"
    }
}

/** Terjemahan: kunci = teks asli (Indonesia). Teks yang tidak ada di kamus tampil apa adanya. */
fun tr(s: String): String {
    val c = AppLang.code
    if (c == AppLang.ID) return s
    val en = TR_MAP[s]?.first ?: s
    return when (c) {
        AppLang.EN -> en
        AppLang.ZH -> TR_MAP[s]?.second ?: en
        AppLang.HI -> TR_HI[s] ?: en
        AppLang.AR -> TR_AR[s] ?: en
        else -> s
    }
}

@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Pilih bahasa")) },
        text = {
            Column {
                listOf(AppLang.ID, AppLang.EN, AppLang.HI, AppLang.AR, AppLang.ZH).forEach { c ->
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
    "Undang teman dan dapatkan koin" to ("Invite friends and get coins" to "邀请好友赚取金币"),
    "Lencana glory" to ("Glory badges" to "荣誉勋章"),
    "Pusat kustom" to ("Customization hub" to "自定义中心"),
    "Store & My Item" to ("Store & My Item" to "商店和我的物品"),
    "Alat peraga" to ("Props" to "道具"),
    "Liga Peringkat Fam" to ("Family Ranking League" to "家族排名联赛"),
    "CP house" to ("CP house" to "CP小屋"),
    "Bantuan" to ("Help" to "帮助"),
    "Email & password" to ("Email & password" to "邮箱和密码"),
    "Login email & password sudah aktif" to ("Email & password login is already active" to "邮箱密码登录已启用"),
    "SVIP Settings" to ("SVIP Settings" to "SVIP 设置"),
    "Tentang Rezo Chat Voice" to ("About Rezo Chat Voice" to "关于 REZO CHAT VOICE"),
    "Panel Developer" to ("Developer panel" to "开发者面板"),
    "Format email tidak valid" to ("Invalid email format" to "邮箱格式无效"),
    "Pengguna" to ("User" to "用户"),
    "User ini mengaktifkan Avoid disturbing (hanya menerima pesan dari orang yang dia ikuti)" to ("This user enabled Avoid disturbing (only receives messages from people they follow)" to "该用户开启了免打扰（只接收自己关注的人的消息）"),
    "Gagal mengirim" to ("Failed to send" to "发送失败"),
    "Belum ada pesan.\nBuka profil seseorang lalu tekan Chat." to ("No messages yet.\nOpen someone's profile and tap Chat." to "暂无消息。\n打开资料并点击聊天。"),
    "🔒 Mic ini dikunci" to ("🔒 This mic is locked" to "🔒 此麦位已锁定"),
    "Background Tab" to ("Tab background" to "标签页背景"),
)

/** Terjemahan Hindi (India) untuk semua kunci kamus. */
private val TR_HI: Map<String, String> = mapOf(
    "+ Tambah foto" to "+ फ़ोटो जोड़ें",
    "Akun" to "खाता",
    "Alat dasar" to "बुनियादी उपकरण",
    "Apa yang sedang kamu pikirkan?" to "आप किस बारे में सोच रहे हैं?",
    "Background room" to "रूम बैकग्राउंड",
    "Bagikan link" to "लिंक शेयर करें",
    "Batal" to "रद्द करें",
    "Belum ada aktivitas" to "अभी कोई गतिविधि नहीं",
    "Belum ada hasil pencarian" to "अभी तक कोई परिणाम नहीं",
    "Belum ada komentar" to "अभी तक कोई टिप्पणी नहीं",
    "Belum diatur" to "सेट नहीं है",
    "Berikan" to "भेजें",
    "Buat" to "बनाएँ",
    "Buat moment" to "मोमेंट बनाएँ",
    "Buat room" to "रूम बनाएँ",
    "Buka kunci mic" to "माइक अनलॉक करें",
    "Cari negara" to "देश खोजें",
    "Cari pengguna" to "यूज़र खोजें",
    "Cari room atau ID" to "रूम या ID खोजें",
    "Dompet" to "वॉलेट",
    "Don't have an account?" to "खाता नहीं है?",
    "Edit profil" to "प्रोफ़ाइल संपादित करें",
    "Foto room" to "रूम फ़ोटो",
    "Hadiah" to "उपहार",
    "Hapus foto" to "फ़ोटो हटाएँ",
    "ID pengguna" to "यूज़र ID",
    "Ikuti" to "फ़ॉलो करें",
    "Ini profil kamu" to "यह आपकी प्रोफ़ाइल है",
    "Isi" to "टॉप अप",
    "Isi coin" to "कॉइन टॉप अप करें",
    "Jadikan publik" to "सार्वजनिक करें",
    "Jenis kelamin" to "लिंग",
    "Jumlah diamond" to "डायमंड राशि",
    "Jumlah mic" to "माइक की संख्या",
    "Kamu perlu login lagi untuk memakai aplikasi." to "ऐप इस्तेमाल करने के लिए फिर से लॉगिन करें।",
    "Kebijakan privasi" to "गोपनीयता नीति",
    "Keluar" to "साइन आउट",
    "Keluar dari akun?" to "खाते से लॉग आउट करें?",
    "Keluar dari room?" to "रूम से बाहर निकलें?",
    "Ketik pesan..." to "संदेश लिखें...",
    "Ketuk untuk lihat profil lengkap ›" to "पूरी प्रोफ़ाइल देखने के लिए टैप करें ›",
    "Kode undangan kamu" to "आपका आमंत्रण कोड",
    "Konfirmasi" to "पुष्टि करें",
    "Kunci kamar" to "रूम लॉक करें",
    "Kunci mic" to "माइक लॉक करें",
    "Level user" to "यूज़र लेवल",
    "Lihat profil ›" to "प्रोफ़ाइल देखें ›",
    "Masuk" to "दाख़िल हों",
    "Masukkan ID pengguna" to "यूज़र ID दर्ज करें",
    "Masukkan kode undangan" to "आमंत्रण कोड दर्ज करें",
    "Mengikuti ✓" to "फ़ॉलो कर रहे हैं ✓",
    "Minimalkan" to "छोटा करें",
    "Mode MC acara" to "इवेंट MC मोड",
    "Moment" to "मोमेंट",
    "Nama room" to "रूम का नाम",
    "Obrolan" to "चैट",
    "Password (min. 4 karakter)" to "पासवर्ड (कम से कम 4 अक्षर)",
    "Password (min. 6 karakter)" to "पासवर्ड (कम से कम 6 अक्षर)",
    "Pengaturan" to "सेटिंग्स",
    "Pengaturan room" to "रूम सेटिंग्स",
    "Pengguna lain harus memasukkan password untuk masuk." to "जुड़ने के लिए दूसरे यूज़र्स को पासवर्ड डालना होगा।",
    "Pengumuman room" to "रूम घोषणा",
    "Pilih mode party" to "पार्टी मोड चुनें",
    "Pilih negara" to "देश चुनें",
    "Posisi kursi host akan berubah jika mengganti jumlah mic" to "माइक की संख्या बदलने पर होस्ट सीट बदल जाती है",
    "Profil tidak ditemukan" to "प्रोफ़ाइल नहीं मिली",
    "Punya kode undangan?" to "आमंत्रण कोड है?",
    "Room terkunci" to "रूम लॉक है",
    "Salin ID" to "ID कॉपी करें",
    "Salin kode" to "कोड कॉपी करें",
    "Seat mic" to "माइक सीट",
    "Segera hadir" to "जल्द आ रहा है",
    "Simpan" to "सहेजें",
    "Tambah email & password" to "ईमेल और पासवर्ड जोड़ें",
    "Tukar" to "एक्सचेंज",
    "Tukar diamond ke coin" to "डायमंड को कॉइन में बदलें",
    "Tukar semua" to "सब एक्सचेंज करें",
    "Tulis komentar..." to "टिप्पणी लिखें...",
    "Tulis pesan..." to "संदेश लिखें...",
    "Turun Mic" to "माइक छोड़ें",
    "Turun mic" to "माइक छोड़ें",
    "Turunkan" to "माइक से हटाएँ",
    "Turunkan dari kursi" to "सीट से हटाएँ",
    "Ulangi password" to "पासवर्ड दोहराएँ",
    "Undang teman" to "दोस्तों को आमंत्रित करें",
    "or continue with" to "या जारी रखें",
    "Party" to "पार्टी",
    "Family" to "फ़ैमिली",
    "Pesan" to "संदेश",
    "Saya" to "मैं",
    "Aktivitas" to "गतिविधि",
    "Cashback" to "कैशबैक",
    "Topup" to "टॉप अप",
    "Bahasa" to "भाषा",
    "Pilih bahasa" to "भाषा चुनें",
    "Semua" to "सभी",
    "Belum ada user di mic" to "माइक पर कोई यूज़र नहीं",
    "Pilih penerima dulu" to "पहले प्राप्तकर्ता चुनें",
    "Kamar" to "रूम",
    "Sawer" to "टॉप गिफ़्टर",
    "Pesona" to "आकर्षण",
    "Harian" to "दैनिक",
    "Mingguan" to "साप्ताहिक",
    "Bulanan" to "मासिक",
    "Hitung mundur" to "काउंटडाउन",
    "Belum ada data" to "अभी तक कोई डेटा नहीं",
    "Memuat..." to "लोड हो रहा है...",
    "Undang teman dan dapatkan koin" to "दोस्तों को आमंत्रित करें और कॉइन पाएँ",
    "Lencana glory" to "ग्लोरी बैज",
    "Pusat kustom" to "कस्टम सेंटर",
    "Store & My Item" to "स्टोर और माय आइटम",
    "Alat peraga" to "प्रॉप्स",
    "Liga Peringkat Fam" to "फ़ैमिली रैंकिंग लीग",
    "CP house" to "CP हाउस",
    "Bantuan" to "सहायता",
    "Email & password" to "ईमेल और पासवर्ड",
    "Login email & password sudah aktif" to "ईमेल-पासवर्ड लॉगिन पहले से चालू है",
    "SVIP Settings" to "SVIP सेटिंग्स",
    "Tentang Rezo Chat Voice" to "REZO CHAT VOICE के बारे में",
    "Panel Developer" to "डेवलपर पैनल",
    "Format email tidak valid" to "ईमेल फ़ॉर्मैट गलत है",
    "Pengguna" to "यूज़र",
    "User ini mengaktifkan Avoid disturbing (hanya menerima pesan dari orang yang dia ikuti)" to "इस यूज़र ने Avoid disturbing चालू किया है (सिर्फ़ फ़ॉलो किए लोगों के संदेश मिलते हैं)",
    "Gagal mengirim" to "भेजने में विफल",
    "Belum ada pesan.\nBuka profil seseorang lalu tekan Chat." to "अभी कोई संदेश नहीं।\nकिसी की प्रोफ़ाइल खोलकर चैट दबाएँ।",
    "🔒 Mic ini dikunci" to "🔒 यह माइक लॉक है",
    "Background Tab" to "टैब बैकग्राउंड",
)

/** Terjemahan Arab (Arab Saudi) untuk semua kunci kamus. */
private val TR_AR: Map<String, String> = mapOf(
    "+ Tambah foto" to "+ إضافة صورة",
    "Akun" to "الحساب",
    "Alat dasar" to "الأدوات الأساسية",
    "Apa yang sedang kamu pikirkan?" to "بماذا تفكر؟",
    "Background room" to "خلفية الغرفة",
    "Bagikan link" to "مشاركة الرابط",
    "Batal" to "إلغاء",
    "Belum ada aktivitas" to "لا يوجد نشاط بعد",
    "Belum ada hasil pencarian" to "لا توجد نتائج بحث بعد",
    "Belum ada komentar" to "لا توجد تعليقات بعد",
    "Belum diatur" to "غير محدد",
    "Berikan" to "إرسال",
    "Buat" to "إنشاء",
    "Buat moment" to "إنشاء لحظة",
    "Buat room" to "إنشاء غرفة",
    "Buka kunci mic" to "فتح الميكروفون",
    "Cari negara" to "البحث عن دولة",
    "Cari pengguna" to "البحث عن مستخدمين",
    "Cari room atau ID" to "ابحث عن غرفة أو معرف",
    "Dompet" to "المحفظة",
    "Don't have an account?" to "ليس لديك حساب؟",
    "Edit profil" to "تعديل الملف الشخصي",
    "Foto room" to "صورة الغرفة",
    "Hadiah" to "الهدايا",
    "Hapus foto" to "حذف الصورة",
    "ID pengguna" to "معرف المستخدم",
    "Ikuti" to "متابعة",
    "Ini profil kamu" to "هذا ملفك الشخصي",
    "Isi" to "شحن",
    "Isi coin" to "شحن العملات",
    "Jadikan publik" to "جعله عاماً",
    "Jenis kelamin" to "الجنس",
    "Jumlah diamond" to "مبلغ الماس",
    "Jumlah mic" to "عدد الميكروفونات",
    "Kamu perlu login lagi untuk memakai aplikasi." to "تحتاج إلى تسجيل الدخول مرة أخرى لاستخدام التطبيق.",
    "Kebijakan privasi" to "سياسة الخصوصية",
    "Keluar" to "تسجيل الخروج",
    "Keluar dari akun?" to "تسجيل الخروج من الحساب؟",
    "Keluar dari room?" to "مغادرة الغرفة؟",
    "Ketik pesan..." to "اكتب رسالة...",
    "Ketuk untuk lihat profil lengkap ›" to "اضغط لعرض الملف الشخصي الكامل ›",
    "Kode undangan kamu" to "رمز الدعوة الخاص بك",
    "Konfirmasi" to "تأكيد",
    "Kunci kamar" to "قفل الغرفة",
    "Kunci mic" to "قفل الميكروفون",
    "Level user" to "مستوى المستخدم",
    "Lihat profil ›" to "عرض الملف الشخصي ›",
    "Masuk" to "دخول",
    "Masukkan ID pengguna" to "أدخل معرف المستخدم",
    "Masukkan kode undangan" to "أدخل رمز الدعوة",
    "Mengikuti ✓" to "متابَع ✓",
    "Minimalkan" to "تصغير",
    "Mode MC acara" to "وضع مقدم الحفل",
    "Moment" to "لحظات",
    "Nama room" to "اسم الغرفة",
    "Obrolan" to "الدردشة",
    "Password (min. 4 karakter)" to "كلمة المرور (4 أحرف على الأقل)",
    "Password (min. 6 karakter)" to "كلمة المرور (6 أحرف على الأقل)",
    "Pengaturan" to "الإعدادات",
    "Pengaturan room" to "إعدادات الغرفة",
    "Pengguna lain harus memasukkan password untuk masuk." to "يجب على المستخدمين الآخرين إدخال كلمة المرور للانضمام.",
    "Pengumuman room" to "إعلان الغرفة",
    "Pilih mode party" to "اختر نمط الحفلة",
    "Pilih negara" to "اختر الدولة",
    "Posisi kursi host akan berubah jika mengganti jumlah mic" to "يتغير مقعد المضيف عند تغيير عدد الميكروفونات",
    "Profil tidak ditemukan" to "لم يتم العثور على الملف الشخصي",
    "Punya kode undangan?" to "لديك رمز دعوة؟",
    "Room terkunci" to "الغرفة مقفلة",
    "Salin ID" to "نسخ المعرف",
    "Salin kode" to "نسخ الرمز",
    "Seat mic" to "مقعد الميكروفون",
    "Segera hadir" to "قريباً",
    "Simpan" to "حفظ",
    "Tambah email & password" to "إضافة البريد الإلكتروني وكلمة المرور",
    "Tukar" to "صرف",
    "Tukar diamond ke coin" to "صرف الماس إلى عملات",
    "Tukar semua" to "صرف الكل",
    "Tulis komentar..." to "اكتب تعليقاً...",
    "Tulis pesan..." to "اكتب رسالة...",
    "Turun Mic" to "ترك الميكروفون",
    "Turun mic" to "ترك الميكروفون",
    "Turunkan" to "إبعاد عن الميكروفون",
    "Turunkan dari kursi" to "إبعاد عن المقعد",
    "Ulangi password" to "إعادة كلمة المرور",
    "Undang teman" to "دعوة الأصدقاء",
    "or continue with" to "أو تابع باستخدام",
    "Party" to "حفلة",
    "Family" to "العائلة",
    "Pesan" to "الرسائل",
    "Saya" to "حسابي",
    "Aktivitas" to "النشاطات",
    "Cashback" to "كاش باك",
    "Topup" to "شحن",
    "Bahasa" to "اللغة",
    "Pilih bahasa" to "اختر اللغة",
    "Semua" to "الكل",
    "Belum ada user di mic" to "لا يوجد مستخدمون على الميكروفون",
    "Pilih penerima dulu" to "اختر مستلماً أولاً",
    "Kamar" to "الغرف",
    "Sawer" to "أفضل المهدِين",
    "Pesona" to "الجاذبية",
    "Harian" to "يومي",
    "Mingguan" to "أسبوعي",
    "Bulanan" to "شهري",
    "Hitung mundur" to "العد التنازلي",
    "Belum ada data" to "لا توجد بيانات بعد",
    "Memuat..." to "جارٍ التحميل...",
    "Undang teman dan dapatkan koin" to "دعوة الأصدقاء والحصول على عملات",
    "Lencana glory" to "أوسمة المجد",
    "Pusat kustom" to "مركز التخصيص",
    "Store & My Item" to "المتجر وأغراضي",
    "Alat peraga" to "أدوات",
    "Liga Peringkat Fam" to "دوري ترتيب العائلات",
    "CP house" to "بيت CP",
    "Bantuan" to "المساعدة",
    "Email & password" to "البريد وكلمة المرور",
    "Login email & password sudah aktif" to "تسجيل الدخول بالبريد مفعّل مسبقاً",
    "SVIP Settings" to "إعدادات SVIP",
    "Tentang Rezo Chat Voice" to "حول REZO CHAT VOICE",
    "Panel Developer" to "لوحة المطور",
    "Format email tidak valid" to "صيغة البريد غير صحيحة",
    "Pengguna" to "مستخدم",
    "User ini mengaktifkan Avoid disturbing (hanya menerima pesan dari orang yang dia ikuti)" to "فعّل هذا المستخدم خاصية عدم الإزعاج (يستقبل الرسائل فقط ممن يتابعهم)",
    "Gagal mengirim" to "فشل الإرسال",
    "Belum ada pesan.\nBuka profil seseorang lalu tekan Chat." to "لا توجد رسائل بعد.\nافتح ملف شخصي واضغط على الدردشة.",
    "🔒 Mic ini dikunci" to "🔒 هذا الميكروفون مقفل",
    "Background Tab" to "خلفية التبويبات",
)
