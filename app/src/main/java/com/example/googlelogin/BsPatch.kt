package com.example.googlelogin

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Penggabung file selisih (patch) format BSDIFF40, ditulis murni di Kotlin.
 *
 * Dipakai untuk pembaruan kecil: aplikasi hanya mengunduh bagian APK yang
 * berubah (beberapa MB), lalu menempelkannya ke APK lama yang ada di HP
 * sehingga terbentuk APK utuh versi baru.
 *
 * Format:
 *   "BSDIFF40" | panjang blok kontrol | panjang blok diff | ukuran file baru
 *   lalu tiga aliran bzip2: kontrol, diff, extra.
 *   Tiap tiga angka kontrol = (salin diff+old, salin extra, geser posisi old).
 *
 * Catatan: tanpa library native, jadi aman untuk semua arsitektur HP.
 */
object BsPatch {

    private const val MAGIC = "BSDIFF40"

    /** Batas ukuran potongan baca supaya hemat memori di HP kelas bawah. */
    private const val CHUNK = 64 * 1024

    class Hasil(val ok: Boolean, val pesan: String = "")

    private fun offtin(b: ByteArray, off: Int): Long {
        var y = (b[off + 7].toInt() and 0x7F).toLong()
        for (i in 6 downTo 0) y = (y shl 8) + (b[off + i].toInt() and 0xFF)
        return if (b[off + 7].toInt() and 0x80 != 0) -y else y
    }

    /**
     * Gabungkan [lama] dengan [patchFile] menjadi [hasil].
     * Mengembalikan Hasil(ok=false) kalau patch rusak atau tidak cocok,
     * sehingga pemanggil bisa jatuh ke unduhan APK penuh.
     */
    fun terapkan(lama: File, patchFile: File, hasil: File): Hasil {
        return try {
            RandomAccessFile(patchFile, "r").use { pf ->
                val head = ByteArray(32)
                if (pf.read(head) != 32) return Hasil(false, "patch terlalu pendek")
                val magic = String(head, 0, 8, Charsets.US_ASCII)
                if (magic != MAGIC) return Hasil(false, "format patch tidak dikenal: $magic")
                val ctrlLen = offtin(head, 8)
                val diffLen = offtin(head, 16)
                val newSize = offtin(head, 24)
                if (newSize <= 0 || newSize > 400L * 1024 * 1024) return Hasil(false, "ukuran hasil tidak masuk akal")
                if (ctrlLen <= 0 || diffLen < 0) return Hasil(false, "header patch rusak")

                val mulaiCtrl = 32L
                val mulaiDiff = mulaiCtrl + ctrlLen
                val mulaiExtra = mulaiDiff + diffLen
                val ctrl = bacaBagian(pf, mulaiCtrl, ctrlLen)
                val diff = bacaBagian(pf, mulaiDiff, diffLen)
                val extra = bacaBagian(pf, mulaiExtra, patchFile.length() - mulaiExtra)

                val kontrol = BZip2CompressorInputStream(BufferedInputStream(ctrl.inputStream()), true)
                val diffIn = BZip2CompressorInputStream(BufferedInputStream(diff.inputStream()), true)
                val extraIn = BZip2CompressorInputStream(BufferedInputStream(extra.inputStream()), true)

                RandomAccessFile(lama, "r").use { of ->
                    val lamaLen = of.length()
                    BufferedOutputStream(hasil.outputStream(), CHUNK).use { out ->
                        var oldPos = 0L
                        var newPos = 0L
                        val buf = ByteArray(CHUNK)
                        val bufOld = ByteArray(CHUNK)
                        while (newPos < newSize) {
                            val tri = ByteArray(24)
                            if (kontrol.read(tri) != 24) return Hasil(false, "blok kontrol habis")
                            var x = offtin(tri, 0)
                            var y = offtin(tri, 8)
                            val z = offtin(tri, 16)
                            if (x < 0 || y < 0) return Hasil(false, "blok kontrol tidak valid")

                            // salin x byte: diff + old (byte per byte, dibatasi potongan)
                            while (x > 0) {
                                val n = minOf(x, CHUNK.toLong()).toInt()
                                if (diffIn.read(buf, 0, n) != n) return Hasil(false, "data diff habis")
                                var bacaOld = 0
                                if (oldPos in 0 until lamaLen) {
                                    of.seek(oldPos)
                                    bacaOld = of.read(bufOld, 0, minOf(n.toLong(), lamaLen - oldPos).toInt())
                                }
                                for (i in 0 until n) {
                                    val o = if (i < bacaOld) bufOld[i].toInt() and 0xFF else 0
                                    buf[i] = ((buf[i].toInt() and 0xFF) + o).toByte()
                                }
                                out.write(buf, 0, n)
                                oldPos += n; newPos += n; x -= n
                            }

                            // salin y byte apa adanya dari blok extra
                            while (y > 0) {
                                val n = minOf(y, CHUNK.toLong()).toInt()
                                if (extraIn.read(buf, 0, n) != n) return Hasil(false, "data extra habis")
                                out.write(buf, 0, n)
                                newPos += n; y -= n
                            }

                            oldPos += z
                        }
                    }
                }
                if (hasil.length() != newSize) {
                    hasil.delete()
                    return Hasil(false, "ukuran hasil ${hasil.length()} != $newSize")
                }
            }
            Hasil(true)
        } catch (e: Exception) {
            try { hasil.delete() } catch (ignored: Exception) { }
            Hasil(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun bacaBagian(f: RandomAccessFile, mulai: Long, panjang: Long): ByteArray {
        if (panjang <= 0) return ByteArray(0)
        f.seek(mulai)
        val out = ByteArray(panjang.toInt())
        var sudah = 0
        while (sudah < out.size) {
            val n = f.read(out, sudah, out.size - sudah)
            if (n < 0) break
            sudah += n
        }
        return out
    }
}
