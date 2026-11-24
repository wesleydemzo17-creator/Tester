package br.pokebank

import br.pokebank.core.BankSession
import br.pokebank.core.BankStore
import br.pokebank.core.Checksums
import br.pokebank.core.Gen4Game
import br.pokebank.core.Gen4Save
import br.pokebank.core.Pk4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Monta saves sinteticos com rodapes e CRCs validos, para testar sem cartucho. */
object TestSaves {
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0..3) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }

    private fun footer(b: ByteArray, start: Int, size: Int, footerSize: Int, counter: Long) {
        val end = start + size
        put32(b, end - 0x14, counter)      // contador principal
        put32(b, end - 0x10, 0)            // contador secundario
        put32(b, end - 0xC, size.toLong()) // tamanho do bloco
        put32(b, end - 0x8, 0x20060623L)   // "magic" internacional
        put16(b, end - 2, Checksums.crc16Ccitt(b, start, end - footerSize))
    }

    /**
     * @param newer qual particao (0 ou 1) e a mais recente; a outra recebe lixo de outra "gravacao antiga".
     */
    fun build(game: Gen4Game, newer: Int = 1, fill: (ByteArray, Int) -> Unit = { _, _ -> }): ByteArray {
        val b = ByteArray(Gen4Save.SAVE_SIZE) { 0xFF.toByte() }
        for (part in 0..1) {
            val base = part * Gen4Save.PARTITION
            java.util.Arrays.fill(b, base, base + game.storageStart + game.storageSize, 0)
            // slots vazios como o jogo/PKHeX gravam
            val sBase = base + game.storageStart
            for (box in 0 until 18) for (slot in 0 until 30)
                System.arraycopy(Pk4.emptyEncrypted(), 0, b, sBase + game.boxOffset(box) + slot * 136, 136)
            if (part == newer) fill(b, sBase)
            val counter = if (part == newer) 10L else 9L
            footer(b, base, game.generalSize, game.footerSize, counter)
            footer(b, base + game.storageStart, game.storageSize, game.footerSize, counter)
        }
        return b
    }

    fun res(name: String): ByteArray =
        (TestSaves::class.java.getResourceAsStream("/pk4/$name") ?: File("src/test/resources/pk4/$name").inputStream()).readBytes()
}

class CoreTest {
    private val leafeon = Pk4(TestSaves.res("leafeon.pk4"))
    private val chansey = Pk4(TestSaves.res("chansey_party.pk4"))

    @Test fun crc16MatchesKnownVector() {
        // CRC-16/CCITT-FALSE de "123456789" = 0x29B1
        assertEquals(0x29B1, Checksums.crc16Ccitt("123456789".toByteArray()))
    }

    @Test fun realPk4FilesParse() {
        assertTrue(leafeon.isChecksumValid)
        assertEquals(470, leafeon.species)
        assertEquals("Leafeon", leafeon.speciesName)
        assertTrue(leafeon.level in 1..100)
        assertTrue(chansey.isValid)
        assertEquals(113, chansey.species)
        val happiny = Pk4(TestSaves.res("happiny_jp.pk4"))
        assertEquals(440, happiny.species)
        assertEquals("ピンプク", happiny.nickname)
        val egg = Pk4(TestSaves.res("egg.pk4"))
        assertTrue(egg.isEgg)
        assertEquals("Ovo", egg.displayName)
    }

    @Test fun encryptDecryptRoundTripForAllShuffles() {
        for (sv in 0 until 32) {
            val raw = leafeon.data.copyOf()
            // forca a PID a ter cada valor de embaralhamento
            val pid = (sv.toLong() shl 13) or 0x1234L
            for (i in 0..3) raw[i] = (pid ushr (8 * i)).toByte()
            val pk = Pk4(raw).also { it.refreshChecksum() }
            val enc = pk.encrypt()
            assertFalse("sv=$sv nao criptografou", enc.copyOfRange(8, 136).contentEquals(pk.data.copyOfRange(8, 136)))
            assertEquals(pk, Pk4.decrypt(enc))
        }
    }

    @Test fun emptySlotDecryptsToSpeciesZero() {
        assertTrue(Pk4.decrypt(Pk4.emptyEncrypted()).isEmpty)
    }

    @Test fun detectsEachGameAndActivePartition() {
        for (g in Gen4Game.values()) for (newer in 0..1) {
            val raw = TestSaves.build(g, newer) { b, s -> System.arraycopy(leafeon.encrypt(), 0, b, s + g.boxOffset(3) + 5 * 136, 136) }
            val sav = Gen4Save(raw)
            assertEquals(g, sav.game)
            assertTrue(sav.generalChecksumValid && sav.storageChecksumValid)
            assertEquals(leafeon, sav.getBoxSlot(3, 5))
            assertNull(sav.getBoxSlot(0, 0))
        }
    }

    @Test fun rejectsGarbage() {
        val ex = runCatching { Gen4Save(ByteArray(Gen4Save.SAVE_SIZE)) }.exceptionOrNull()
        assertNotNull(ex)
    }

    @Test fun depositWithdrawCommitCycle() {
        val dir = Files.createTempDirectory("bank").toFile()
        val bank = BankStore(dir)
        val g = Gen4Game.PT
        val raw = TestSaves.build(g) { b, s ->
            System.arraycopy(leafeon.encrypt(), 0, b, s + g.boxOffset(0) + 0 * 136, 136)
            System.arraycopy(chansey.preparedFor(g).encrypt(), 0, b, s + g.boxOffset(0) + 1 * 136, 136)
        }
        val session = BankSession(raw, bank)
        session.deposit(0, 0)
        assertNull(session.save.getBoxSlot(0, 0))
        val (bytes, summary) = session.buildWrite()
        assertTrue(summary.contains("Leafeon"))
        assertEquals(1, bank.pendingCount())
        assertTrue(Gen4Save(bytes).storageChecksumValid)
        assertEquals(0, bank.list().size) // nada muda antes da confirmacao do DS
        session.commit(bytes)
        assertEquals(0, bank.pendingCount())
        val entries = bank.list()
        assertEquals(1, entries.size)
        assertEquals(leafeon, entries[0].pk)

        // agora retira para outro slot
        val pos = session.withdraw(entries[0].uid, 2 to 7)
        assertEquals(2 to 7, pos)
        assertEquals(0, session.bankView().size)
        val (bytes2, _) = session.buildWrite()
        session.commit(bytes2)
        val after = Gen4Save(bytes2)
        assertEquals(leafeon, after.getBoxSlot(2, 7))
        assertEquals(113, after.getBoxSlot(0, 1)!!.species)
        assertEquals(0, bank.list().size)
        dir.deleteRecursively()
    }

    @Test fun onlyStorageBlockChanges() {
        val g = Gen4Game.HGSS
        val raw = TestSaves.build(g) { b, s -> System.arraycopy(leafeon.encrypt(), 0, b, s + g.boxOffset(17) + 29 * 136, 136) }
        val sav = Gen4Save(raw)
        sav.setBoxSlot(17, 29, null)
        val out = sav.finish()
        val changed = (out.indices).filter { out[it] != raw[it] }
        assertTrue(changed.isNotEmpty())
        val sStart = Gen4Save.PARTITION + g.storageStart
        assertTrue(changed.all { it in sStart until sStart + g.storageSize })
        assertNotEquals(-1, changed.first())
    }

    @Test fun blocksItemsThatDoNotExistInTargetGame() {
        val raw = leafeon.data.copyOf()
        raw[0x0A] = (500 and 0xFF).toByte(); raw[0x0B] = (500 shr 8).toByte() // item so de HGSS
        val pk = Pk4(raw).also { it.refreshChecksum() }
        assertNotNull(pk.incompatibilityWith(Gen4Game.DP))
        assertNull(pk.incompatibilityWith(Gen4Game.HGSS))
        val dir = Files.createTempDirectory("bank3").toFile()
        val bank = BankStore(dir)
        val uid = bank.add(pk)
        val session = BankSession(TestSaves.build(Gen4Game.DP), bank)
        assertNotNull(runCatching { session.withdraw(uid) }.exceptionOrNull())
        assertFalse(session.hasChanges)
        dir.deleteRecursively()
    }

    @Test fun cancelingWithdrawByDepositingBack() {
        val dir = Files.createTempDirectory("bank2").toFile()
        val bank = BankStore(dir)
        val uid = bank.add(leafeon)
        val session = BankSession(TestSaves.build(Gen4Game.DP), bank)
        val pos = session.withdraw(uid)
        session.deposit(pos.first, pos.second)
        assertFalse(session.hasChanges)
        dir.deleteRecursively()
    }
}
