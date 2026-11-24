package br.pokebank

import br.pokebank.core.ByteSource
import br.pokebank.core.GameData
import br.pokebank.core.Gen4Game
import br.pokebank.core.Gen4Save
import br.pokebank.core.NdsRom
import br.pokebank.core.NitroGfx
import br.pokebank.core.Pk4
import br.pokebank.core.Pk4Details
import br.pokebank.core.PokemonSpriteSource
import br.pokebank.core.SaveContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

object Fixtures {
    fun res(path: String): ByteArray =
        (Fixtures::class.java.getResourceAsStream("/$path") ?: File("src/test/resources/$path").inputStream()).readBytes()

    private fun le32(o: ByteArrayOutputStream, v: Int) { for (i in 0..3) o.write((v ushr (8 * i)) and 0xFF) }
    private fun le16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v ushr 8) and 0xFF) }

    /** NARC no formato padrao da Nintendo (sem nomes). */
    fun narc(files: List<ByteArray>): ByteArray {
        val img = ByteArrayOutputStream()
        val fat = ByteArrayOutputStream()
        for (f in files) {
            le32(fat, img.size()); img.write(f); le32(fat, img.size())
            while (img.size() % 4 != 0) img.write(0xFF)
        }
        val body = ByteArrayOutputStream()
        body.write("BTAF".toByteArray()); le32(body, 12 + fat.size()); le16(body, files.size); le16(body, 0); body.write(fat.toByteArray())
        body.write("BTNF".toByteArray()); le32(body, 16); le32(body, 4); le16(body, 0); le16(body, 1)
        body.write("GMIF".toByteArray()); le32(body, 8 + img.size()); body.write(img.toByteArray())
        val out = ByteArrayOutputStream()
        out.write("NARC".toByteArray()); le16(out, 0xFFFE); le16(out, 0x0100); le32(out, 16 + body.size()); le16(out, 16); le16(out, 3)
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /** ROM minima com um unico arquivo em dir1/dir2/nome (ou na raiz se dirs vazio). */
    fun rom(gameCode: String, dirs: List<String>, fileName: String, content: ByteArray): ByteArray {
        // FNT: uma entrada por diretorio (raiz + dirs)
        val nDirs = 1 + dirs.size
        val subs = mutableListOf<ByteArray>()
        for (d in 0 until nDirs) {
            val s = ByteArrayOutputStream()
            if (d < dirs.size) { // contem o proximo diretorio
                val n = dirs[d].toByteArray(); s.write(0x80 or n.size); s.write(n); le16(s, 0xF000 or (d + 1))
            } else {
                // um arquivo "lixo" antes do nosso, para testar a contagem de ids
                val junk = "junk.bin".toByteArray(); s.write(junk.size); s.write(junk)
                val n = fileName.toByteArray(); s.write(n.size); s.write(n)
            }
            s.write(0)
            subs.add(s.toByteArray())
        }
        val fnt = ByteArrayOutputStream()
        var off = nDirs * 8
        for (d in 0 until nDirs) {
            le32(fnt, off); le16(fnt, 0); le16(fnt, if (d == 0) nDirs else 0xF000 + maxOf(0, d - 1))
            off += subs[d].size
        }
        subs.forEach { fnt.write(it) }
        val fntBytes = fnt.toByteArray()
        val fntOff = 0x200
        val fatOff = fntOff + ((fntBytes.size + 3) / 4) * 4
        val dataOff = fatOff + 16
        val rom = ByteArray(dataOff + 4 + content.size)
        "POKEMON TEST".toByteArray().copyInto(rom, 0)
        gameCode.toByteArray().copyInto(rom, 12)
        fun p32(o: Int, v: Int) { for (i in 0..3) rom[o + i] = (v ushr (8 * i)).toByte() }
        p32(0x40, fntOff); p32(0x44, fntBytes.size); p32(0x48, fatOff); p32(0x4C, 16)
        fntBytes.copyInto(rom, fntOff)
        p32(fatOff, dataOff); p32(fatOff + 4, dataOff + 4)                   // arquivo 0 = junk
        p32(fatOff + 8, dataOff + 4); p32(fatOff + 12, dataOff + 4 + content.size) // arquivo 1 = nosso
        content.copyInto(rom, dataOff + 4)
        return rom
    }
}

class DataAndSpritesTest {
    private val expected = Fixtures.res("gfx/expected.bin")

    @Test fun ncgrFrontToBackMatchesNitrogfx() {
        val img = NitroGfx.decodeNcgr(Fixtures.res("gfx/pt.NCGR"), NitroGfx.Crypt.FRONT_TO_BACK)
        assertEquals(160, img.width); assertEquals(80, img.height)
        assertTrue(img.pixels.contentEquals(expected))
    }

    @Test fun ncgrBackToFrontMatchesNitrogfx() {
        val img = NitroGfx.decodeNcgr(Fixtures.res("gfx/dp.NCGR"), NitroGfx.Crypt.BACK_TO_FRONT)
        assertTrue(img.pixels.contentEquals(expected))
    }

    @Test fun nclrColors() {
        val pal = NitroGfx.decodeNclr(Fixtures.res("gfx/pal.NCLR"))
        assertTrue(pal.size >= 16)
        assertEquals(0xFFFF0000.toInt(), pal[1]) // (248,0,0) -> vermelho puro em 5 bits
        assertEquals(0xFF0000FF.toInt(), pal[3])
    }

    @Test fun spritesFromSyntheticPlatinumRom() {
        val ncgr = Fixtures.res("gfx/pt.NCGR")
        val pal = Fixtures.res("gfx/pal.NCLR")
        // especies 0..2, 6 arquivos cada; femea vazia
        val files = (0..2).flatMap { listOf(ByteArray(0), ByteArray(0), ByteArray(0), ncgr, pal, pal) }
        val rom = Fixtures.rom("CPUE", listOf("poketool", "pokegra"), "pl_pokegra.narc", Fixtures.narc(files))
        val r = NdsRom(ByteSource(rom))
        assertEquals(1, r.findFile("poketool/pokegra/pl_pokegra.narc"))
        assertNull(r.findFile("poketool/pokegra/nao_existe.narc"))
        PokemonSpriteSource(ByteSource(rom)).use { s ->
            assertEquals(Gen4Game.PT, s.game)
            val argb = s.front(1)!!
            assertEquals(80 * 80, argb.size)
            assertEquals(0, argb[0]) // canto transparente
            val p = NitroGfx.decodeNclr(pal)
            val idx = expected[40 * 160 + 40].toInt()
            assertEquals(p[idx], argb[40 * 80 + 40])
            assertNull(s.front(400)) // nao existe neste pacote de teste
        }
    }

    @Test fun spritesFromSyntheticHgssRom() {
        val files = (0..1).flatMap { listOf(ByteArray(0), ByteArray(0), ByteArray(0), Fixtures.res("gfx/pt.NCGR"), Fixtures.res("gfx/pal.NCLR"), Fixtures.res("gfx/pal.NCLR")) }
        val rom = Fixtures.rom("IPKE", listOf("a", "0", "0"), "4", Fixtures.narc(files))
        PokemonSpriteSource(ByteSource(rom)).use { assertNotNull(it.front(1, shiny = true)) }
    }

    @Test fun gameTexts() {
        assertEquals("Pound", GameData.move(1))
        assertEquals("Shadow Force", GameData.move(467))
        assertEquals("Adamant", GameData.nature(3))
        assertEquals("Fire", GameData.type(9))
        assertEquals("Master Ball", GameData.item(1))
        assertEquals("Twinleaf Town", GameData.location(1))
        val bulba = GameData.species(1)!!
        assertEquals("Grass / Poison", bulba.typesText)
        assertEquals(318, bulba.baseTotal)
    }

    @Test fun statFormulaMatchesBulbapediaGarchompExample() {
        // Exemplo classico da Bulbapedia: Garchomp Nv78, Adamant, IVs 24/12/30/16/23/5, EVs 74/190/91/48/84/23
        // (ordem HP, Atk, Def, AtkEsp, DefEsp, Vel) -> 289/278/193/135/171/171
        val raw = ByteArray(136)
        fun p16(o: Int, v: Int) { raw[o] = v.toByte(); raw[o + 1] = (v shr 8).toByte() }
        fun p32(o: Int, v: Long) { for (i in 0..3) raw[o + i] = (v ushr (8 * i)).toByte() }
        p32(0, 3L) // PID % 25 = 3 = Adamant
        p16(0x08, 445)
        p32(0x10, 1_000_000L) // EXP suficiente: limitamos o nivel abaixo trocando a curva? usamos exp exato
        // Garchomp e "Slow" (1.250.000 no 100). Nv78 = 5*78^3/4 = 593.190
        p32(0x10, 593_190L)
        val ivHp = 24; val ivAtk = 12; val ivDef = 30; val ivSpa = 16; val ivSpd = 23; val ivSpe = 5
        p32(0x38, (ivHp or (ivAtk shl 5) or (ivDef shl 10) or (ivSpe shl 15) or (ivSpa shl 20) or (ivSpd shl 25)).toLong())
        val evs = intArrayOf(74, 190, 91, 23, 48, 84) // HP Atk Def Vel AtkEsp DefEsp
        evs.forEachIndexed { i, v -> raw[0x18 + i] = v.toByte() }
        val pk = Pk4(raw)
        assertEquals(78, pk.level)
        val st = Pk4Details(pk).stats
        assertEquals(listOf(289, 278, 193, 171, 135, 171), st.toList()) // HP Atk Def Vel AtkEsp DefEsp
    }

    @Test fun partyDecryptAndTrainerAndDex() {
        val leafeon = Pk4(TestSaves.res("leafeon.pk4"))
        val chanseyRaw = TestSaves.res("chansey_party.pk4") // 236 bytes descriptografados (com status)
        val g = Gen4Game.PT
        val raw = TestSaves.build(g) { _, _ -> }
        // escreve a party na particao ativa (1) e refaz o CRC do General
        val gen = Gen4Save.PARTITION
        val party = gen + g.partyOffset
        raw[party - 4] = 2
        fun encParty(b: ByteArray): ByteArray {
            val pk = Pk4(b.copyOf(136))
            val enc = pk.encrypt().copyOf(236)
            var seed = pk.pid
            var i = 136
            while (i < 236) {
                seed = (0x41C64E6DL * seed + 0x6073L) and 0xFFFFFFFFL
                val v = ((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)) xor ((seed ushr 16).toInt() and 0xFFFF)
                enc[i] = v.toByte(); enc[i + 1] = (v shr 8).toByte(); i += 2
            }
            return enc
        }
        encParty(chanseyRaw).copyInto(raw, party)
        encParty(leafeon.data.copyOf(236)).copyInto(raw, party + 236)
        // treinador: dinheiro 123456, 3 insignias, tempo 12:34:56
        val t = gen + g.trainerOffset
        raw[t + 0x14] = (123456 and 0xFF).toByte(); raw[t + 0x15] = (123456 shr 8).toByte(); raw[t + 0x16] = (123456 shr 16).toByte()
        raw[t + 0x1A] = 0b111
        raw[t + 0x22] = 12; raw[t + 0x24] = 34; raw[t + 0x25] = 56
        // pokedex: capturou #1 e #25, viu #1, #25, #150
        val dex = gen + g.dexOffset + 4
        fun flag(region: Int, sp: Int) { val i = sp - 1; val o = dex + region * 0x40 + (i shr 3); raw[o] = (raw[o].toInt() or (1 shl (i and 7))).toByte() }
        flag(0, 1); flag(0, 25); flag(1, 1); flag(1, 25); flag(1, 150)
        // CRC do General
        val end = gen + g.generalSize
        val crc = br.pokebank.core.Checksums.crc16Ccitt(raw, gen, end - g.footerSize)
        raw[end - 2] = crc.toByte(); raw[end - 1] = (crc shr 8).toByte()

        val sav = Gen4Save(raw)
        assertTrue(sav.generalChecksumValid)
        val p = sav.party
        assertEquals(2, p.size)
        assertEquals(113, p[0].first.species)
        assertEquals(chanseyRaw[0x8C].toInt() and 0xFF, p[0].second.level)
        assertEquals(leafeon, p[1].first)
        assertEquals(123456L, sav.money)
        assertEquals(3, sav.badgeCount)
        assertEquals("12:34:56", sav.playTime)
        assertEquals(2, sav.dexCaughtCount)
        assertEquals(3, sav.dexSeenCount)
        assertTrue(sav.dexSeen(150) && !sav.dexCaught(150))
        val d = Pk4Details(p[0].first, p[0].second)
        assertEquals(p[0].second.stats.toList(), d.stats.toList())
        assertTrue(d.moves.any { it != 0 })
    }

    @Test fun showdownExport() {
        val txt = br.pokebank.core.Showdown.export(Pk4Details(Pk4(TestSaves.res("leafeon.pk4"))))
        assertTrue(txt.startsWith("Leafeon") || txt.contains("(Leafeon)"))
        assertTrue(txt.contains("Ability: "))
        assertTrue(txt.contains(" Nature\n"))
        assertTrue(txt.lines().any { it.startsWith("- ") })
    }

    @Test fun emulatorSaveFormats() {
        val raw = TestSaves.build(Gen4Game.HGSS)
        val footer = "|<--Snip above here to create a raw sav by excluding this DeSmuME savedata footer:".toByteArray().copyOf(122)
        val dsv = SaveContainer(raw + footer)
        assertTrue(dsv.kind.contains("DraStic"))
        val rebuilt = dsv.rebuild(raw)
        assertEquals(raw.size + 122, rebuilt.size)
        assertTrue(rebuilt.copyOfRange(raw.size, rebuilt.size).contentEquals(footer))
        assertTrue(SaveContainer(raw).kind.contains("puro"))
        assertTrue(SaveContainer(raw + ByteArray(0x80000) { 0xFF.toByte() }).kind.contains("extras"))
    }
}
