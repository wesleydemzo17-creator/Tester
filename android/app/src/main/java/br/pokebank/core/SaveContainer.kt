package br.pokebank.core

/**
 * Embrulha um arquivo de save vindo de qualquer lugar:
 *  - cartucho original / R4 / TWiLight / melonDS: 512 KB puros (.sav)
 *  - DraStic / DeSmuME: 512 KB + rodape de 122 bytes (.dsv), que preservamos
 *  - alguns flashcarts/emuladores criam arquivos maiores (1 MB etc.): usamos os primeiros 512 KB
 */
class SaveContainer(fileBytes: ByteArray) {
    val raw: ByteArray
    private val trailer: ByteArray
    val kind: String

    init {
        if (fileBytes.size < Gen4Save.SAVE_SIZE) throw SaveFormatException("Arquivo pequeno demais (${fileBytes.size} bytes)")
        raw = fileBytes.copyOf(Gen4Save.SAVE_SIZE)
        trailer = fileBytes.copyOfRange(Gen4Save.SAVE_SIZE, fileBytes.size)
        val footer = String(trailer, 0, minOf(trailer.size, 16), Charsets.ISO_8859_1)
        kind = when {
            trailer.isEmpty() -> "save puro (.sav)"
            footer.startsWith("|<--Snip") -> "DraStic/DeSmuME (.dsv)"
            else -> "save com ${trailer.size} bytes extras"
        }
        Gen4Save(raw) // valida
    }

    /** Monta o arquivo final no mesmo formato do original. */
    fun rebuild(newRaw: ByteArray): ByteArray {
        require(newRaw.size == Gen4Save.SAVE_SIZE)
        return newRaw + trailer
    }
}
