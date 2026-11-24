package br.pokebank.core

/** Leitura/escrita little-endian (a ordem de bytes do Nintendo DS). */
internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)

internal fun ByteArray.put16(o: Int, v: Int) {
    this[o] = v.toByte(); this[o + 1] = (v ushr 8).toByte()
}

internal fun ByteArray.put32(o: Int, v: Long) {
    put16(o, (v and 0xFFFF).toInt()); put16(o + 2, ((v ushr 16) and 0xFFFF).toInt())
}

object Checksums {
    /** CRC-16/CCITT (polinomio 0x1021, inicio 0xFFFF), igual ao usado nos saves Gen 4. */
    fun crc16Ccitt(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in start until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** CRC-32 padrao (zlib). O DS calcula o mesmo para verificar as transferencias. */
    fun crc32(data: ByteArray, start: Int = 0, end: Int = data.size): Long {
        val c = java.util.zip.CRC32()
        c.update(data, start, end - start)
        return c.value
    }
}
