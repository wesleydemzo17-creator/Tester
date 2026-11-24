package br.pokebank.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import br.pokebank.core.PokemonSpriteSource
import br.pokebank.core.RandomSource
import br.pokebank.core.SpeciesInfo
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Sprites extraidos da ROM do proprio usuario e guardados como PNG no armazenamento do app.
 * O app nao traz nenhuma imagem da Nintendo: tudo sai do jogo que voce escolhe.
 */
class SpriteRepository(private val context: Context) {
    private val dir = File(context.filesDir, "sprites").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(400)

    val hasSprites: Boolean get() = File(dir, "ok.txt").exists()
    val sourceName: String? get() = File(dir, "ok.txt").takeIf { it.exists() }?.readText()

    private fun name(species: Int, shiny: Boolean, female: Boolean) =
        "${species}${if (shiny) "s" else "n"}${if (female) "f" else ""}.png"

    fun get(species: Int, shiny: Boolean = false, female: Boolean = false): Bitmap? {
        if (species !in 1..SpeciesInfo.MAX_SPECIES) return null
        val key = name(species, shiny, female)
        cache.get(key)?.let { return it }
        val f = File(dir, key).takeIf { it.exists() } ?: File(dir, name(species, shiny, false)).takeIf { it.exists() } ?: return null
        return BitmapFactory.decodeFile(f.absolutePath)?.also { cache.put(key, it) }
    }

    /** Le a ROM (.nds) escolhida pelo usuario e extrai os sprites. Roda fora da thread principal. */
    fun extract(uri: Uri, progress: (Int, Int) -> Unit): String {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Nao consegui abrir o arquivo")
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                PokemonSpriteSource(ChannelSource(ch)).use { src ->
                    val total = SpeciesInfo.MAX_SPECIES
                    for (sp in 1..total) {
                        for (shiny in listOf(false, true)) {
                            src.front(sp, shiny)?.let { save(name(sp, shiny, false), it) }
                            // so grava a versao femea quando ela e diferente
                            val fem = src.front(sp, shiny, female = true)
                            val male = src.front(sp, shiny, female = false)
                            if (fem != null && male != null && !fem.contentEquals(male)) save(name(sp, shiny, true), fem)
                        }
                        if (sp % 10 == 0 || sp == total) progress(sp, total)
                    }
                    cache.evictAll()
                    val label = src.romTitle
                    File(dir, "ok.txt").writeText(label)
                    return label
                }
            }
        }
    }

    private fun save(file: String, argb: IntArray) {
        val bmp = Bitmap.createBitmap(argb, 80, 80, Bitmap.Config.ARGB_8888)
        File(dir, file).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        cache.evictAll()
    }

    /** Acesso aleatorio a um arquivo aberto pelo seletor do Android (sem copiar a ROM). */
    private class ChannelSource(private val ch: FileChannel) : RandomSource {
        override val size: Long get() = ch.size()
        override fun read(pos: Long, len: Int): ByteArray {
            val buf = ByteBuffer.allocate(len)
            var p = pos
            while (buf.hasRemaining()) {
                val n = ch.read(buf, p)
                if (n < 0) throw java.io.EOFException()
                p += n
            }
            return buf.array()
        }
        override fun close() {}
    }
}
