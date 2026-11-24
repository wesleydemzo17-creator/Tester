package br.pokebank.core

import java.io.IOException
import java.io.RandomAccessFile

/**
 * Extrai os sprites dos Pokemon da PROPRIA ROM do usuario (nada de imagens embutidas no app).
 *
 * Caminho dentro da ROM (confirmado nos projetos de decompilacao da pret):
 *   Diamond/Pearl : poketool/pokegra/pokegra.narc     (criptografia "do fim para o comeco")
 *   Platinum      : poketool/pokegra/pl_pokegra.narc  (criptografia "do comeco para o fim")
 *   HG/SS         : a/0/0/4                           (criptografia "do comeco para o fim")
 * Cada especie ocupa 6 arquivos: costas femea, costas macho, frente femea, frente macho,
 * paleta normal, paleta shiny. A imagem tem 160x80 = dois quadros de 80x80 lado a lado.
 */

/** Acesso aleatorio a um arquivo grande (ROM), sem carregar tudo na memoria. */
interface RandomSource : AutoCloseable {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray
}

class FileSource(private val f: RandomAccessFile) : RandomSource {
    override val size: Long get() = f.length()
    override fun read(pos: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        f.seek(pos)
        f.readFully(b)
        return b
    }
    override fun close() = f.close()
}

class ByteSource(private val b: ByteArray) : RandomSource {
    override val size: Long get() = b.size.toLong()
    override fun read(pos: Long, len: Int): ByteArray = b.copyOfRange(pos.toInt(), pos.toInt() + len)
    override fun close() {}
}

/** Sistema de arquivos de uma ROM de Nintendo DS (tabelas FNT + FAT). */
class NdsRom(private val src: RandomSource) {
    private val header = src.read(0, 0x200)
    val title: String = String(header, 0, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ')
    val gameCode: String = String(header, 12, 4, Charsets.US_ASCII)
    private val fnt = src.read(header.u32(0x40), header.u32(0x44).toInt())
    private val fatOffset = header.u32(0x48)
    private val fatCount = (header.u32(0x4C) / 8).toInt()

    /** Retorna o id do arquivo para um caminho como "poketool/pokegra/pokegra.narc", ou null. */
    fun findFile(path: String): Int? {
        var dir = 0xF000
        val parts = path.trim('/').split('/')
        for ((i, name) in parts.withIndex()) {
            val last = i == parts.lastIndex
            val entry = (dir and 0xFFF) * 8
            var p = fnt.u32(entry).toInt()
            var fileId = fnt.u16(entry + 4)
            var found: Int? = null
            while (true) {
                val t = fnt.u8(p++)
                if (t == 0) break
                val len = t and 0x7F
                val n = String(fnt, p, len, Charsets.ISO_8859_1)
                p += len
                if (t and 0x80 != 0) {
                    val sub = fnt.u16(p); p += 2
                    if (!last && n == name) { found = sub; break }
                } else {
                    if (last && n == name) { found = fileId; break }
                    fileId++
                }
            }
            found ?: return null
            if (last) return found
            dir = found
        }
        return null
    }

    fun fileRange(id: Int): LongRange {
        require(id in 0 until fatCount) { "arquivo $id" }
        val e = src.read(fatOffset + id * 8L, 8)
        return e.u32(0) until e.u32(4)
    }

    fun readFile(id: Int): ByteArray = fileRange(id).let { src.read(it.first, (it.last - it.first + 1).toInt()) }

    /** Abre um NARC sem ler o conteudo inteiro. */
    fun openNarc(id: Int): Narc = Narc(src, fileRange(id).first)
}

/** Pacote NARC ("NARC" -> BTAF -> BTNF -> GMIF). */
class Narc(private val src: RandomSource, private val base: Long) {
    private val count: Int
    private val fat: ByteArray
    private val dataStart: Long

    init {
        val h = src.read(base, 0x10)
        if (String(h, 0, 4, Charsets.US_ASCII) != "NARC") throw IOException("nao e um NARC")
        val headerSize = h.u16(0xC)
        var p = base + headerSize
        val btaf = src.read(p, 12)
        if (String(btaf, 0, 4, Charsets.US_ASCII) != "BTAF") throw IOException("BTAF ausente")
        count = btaf.u16(8)
        fat = src.read(p + 12, count * 8)
        p += btaf.u32(4)
        val btnf = src.read(p, 8)
        if (String(btnf, 0, 4, Charsets.US_ASCII) != "BTNF") throw IOException("BTNF ausente")
        p += btnf.u32(4)
        val gmif = src.read(p, 8)
        if (String(gmif, 0, 4, Charsets.US_ASCII) != "GMIF") throw IOException("GMIF ausente")
        dataStart = p + 8
    }

    val size: Int get() = count

    fun read(i: Int): ByteArray {
        val s = fat.u32(i * 8)
        val e = fat.u32(i * 8 + 4)
        return if (e <= s) ByteArray(0) else src.read(dataStart + s, (e - s).toInt())
    }
}

/** Imagem com indices de cor (0 = transparente). */
class IndexedImage(val width: Int, val height: Int, val pixels: ByteArray) {
    fun crop(x0: Int, y0: Int, w: Int, h: Int): IndexedImage {
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * width + x0, out, y * w, w)
        return IndexedImage(w, h, out)
    }

    /** Converte para ARGB_8888 (o formato do Bitmap do Android). */
    fun toArgb(palette: IntArray): IntArray = IntArray(pixels.size) { i ->
        val idx = pixels[i].toInt() and 0xFF
        if (idx == 0) 0 else palette.getOrElse(idx) { 0xFFFF00FF.toInt() }
    }

    /** Fracao de pixels transparentes na borda (sprites corretos tem borda vazia). */
    fun borderTransparency(): Double {
        var t = 0; var n = 0
        for (x in 0 until width) { n += 2; if (pixels[x].toInt() == 0) t++; if (pixels[(height - 1) * width + x].toInt() == 0) t++ }
        for (y in 0 until height) { n += 2; if (pixels[y * width].toInt() == 0) t++; if (pixels[y * width + width - 1].toInt() == 0) t++ }
        return t.toDouble() / n
    }
}

object NitroGfx {
    enum class Crypt { NONE, FRONT_TO_BACK, BACK_TO_FRONT }

    /** Decodifica um NCGR "scanned" de 4 bits por pixel (formato dos sprites de Pokemon Gen 4). */
    fun decodeNcgr(file: ByteArray, crypt: Crypt): IndexedImage {
        if (file.size < 0x30 || String(file, 0, 4, Charsets.US_ASCII) != "RGCN") throw IOException("nao e NCGR")
        val ch = 0x10
        if (String(file, ch, 4, Charsets.US_ASCII) != "RAHC") throw IOException("bloco CHAR ausente")
        val bitDepth = if (file.u8(ch + 0xC) == 3) 4 else 8
        val tilesTall = file.u16(ch + 0x8).toShort().toInt()
        val tilesWide = file.u16(ch + 0xA).toShort().toInt()
        val size = (file.u32(ch + 0x4) - 0x20).toInt()
        val data = file.copyOfRange(ch + 0x20, ch + 0x20 + size)
        when (crypt) {
            Crypt.FRONT_TO_BACK -> {
                var key = data.u16(0).toLong()
                var i = 0
                while (i < size) {
                    data.put16(i, data.u16(i) xor (key and 0xFFFF).toInt())
                    key = (key * 1103515245L + 24691L) and 0xFFFFFFFFL
                    i += 2
                }
            }
            Crypt.BACK_TO_FRONT -> {
                var key = data.u16(size - 2).toLong()
                var i = size
                while (i > 0) {
                    data.put16(i - 2, data.u16(i - 2) xor (key and 0xFFFF).toInt())
                    key = (key * 1103515245L + 24691L) and 0xFFFFFFFFL
                    i -= 2
                }
            }
            Crypt.NONE -> {}
        }
        val w = if (tilesWide > 0) tilesWide * 8 else 160
        val h = if (tilesTall > 0) tilesTall * 8 else size * (8 / bitDepth) / w
        val px = ByteArray(w * h)
        if (bitDepth == 4) {
            for (i in 0 until minOf(size, px.size / 2)) {
                val b = data[i].toInt() and 0xFF
                px[i * 2] = (b and 0xF).toByte()      // pixel da esquerda no nibble baixo
                px[i * 2 + 1] = (b shr 4).toByte()
            }
        } else {
            System.arraycopy(data, 0, px, 0, minOf(size, px.size))
        }
        return IndexedImage(w, h, px)
    }

    /** Paleta NCLR (cores BGR555) -> ARGB. */
    fun decodeNclr(file: ByteArray): IntArray {
        if (file.size < 0x28 || String(file, 0x10, 4, Charsets.US_ASCII) != "TTLP") throw IOException("nao e NCLR")
        val pl = 0x10
        val section = file.u32(pl + 4)
        var palSize = file.u32(pl + 0x10)
        if (section - 0x18 != palSize) palSize = 0x200 - palSize
        val start = pl + 0x18
        val n = minOf(palSize.toInt() / 2, (file.size - start) / 2)
        return IntArray(n) { i ->
            val c = file.u16(start + i * 2)
            fun ch(v: Int) = (v shl 3) or (v shr 2)
            (0xFF shl 24) or (ch(c and 31) shl 16) or (ch((c shr 5) and 31) shl 8) or ch((c shr 10) and 31)
        }
    }
}

/** Extrator de alto nivel: abre a ROM, acha o pacote certo e entrega sprites prontos. */
class PokemonSpriteSource(src: RandomSource) : AutoCloseable {
    private val rom = NdsRom(src)
    private val source = src
    val game: Gen4Game
    private val narc: Narc
    private val crypt: NitroGfx.Crypt

    init {
        val prefix = rom.gameCode.take(3)
        game = when (prefix) {
            "ADA", "APA" -> Gen4Game.DP
            "CPU" -> Gen4Game.PT
            "IPK", "IPG" -> Gen4Game.HGSS
            else -> throw IOException("ROM nao suportada: ${rom.title} [${rom.gameCode}]")
        }
        val path = when (game) {
            Gen4Game.DP -> "poketool/pokegra/pokegra.narc"
            Gen4Game.PT -> "poketool/pokegra/pl_pokegra.narc"
            Gen4Game.HGSS -> "a/0/0/4"
        }
        val id = rom.findFile(path) ?: throw IOException("sprites nao encontrados ($path)")
        narc = rom.openNarc(id)
        crypt = if (game == Gen4Game.DP) NitroGfx.Crypt.BACK_TO_FRONT else NitroGfx.Crypt.FRONT_TO_BACK
    }

    val romTitle: String get() = "${rom.title} [${rom.gameCode}]"

    /**
     * Sprite de frente 80x80 em ARGB. Usa a imagem de femea quando existe uma diferente.
     * Retorna null se a especie nao existir no pacote.
     */
    fun front(species: Int, shiny: Boolean = false, female: Boolean = false): IntArray? {
        if (species !in 1..SpeciesInfo.MAX_SPECIES || species * 6 + 5 >= narc.size) return null
        val base = species * 6
        val gfx = (if (female) narc.read(base + 2).takeIf { it.isNotEmpty() } else null) ?: narc.read(base + 3)
        if (gfx.isEmpty()) return null
        var img = NitroGfx.decodeNcgr(gfx, crypt)
        if (img.borderTransparency() < 0.9) { // por seguranca: tenta o outro sentido da criptografia
            val alt = NitroGfx.decodeNcgr(gfx, if (crypt == NitroGfx.Crypt.FRONT_TO_BACK) NitroGfx.Crypt.BACK_TO_FRONT else NitroGfx.Crypt.FRONT_TO_BACK)
            if (alt.borderTransparency() > img.borderTransparency()) img = alt
        }
        val pal = NitroGfx.decodeNclr(narc.read(base + if (shiny) 5 else 4))
        return img.crop(0, 0, 80, 80).toArgb(pal)
    }

    override fun close() = source.close()
}
