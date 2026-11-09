package com.kyant.amdl.engine

class M4aTagger(private val data: ByteArray) : AutoCloseable {

    private val tagPtr: Long = RustLib.readM4aTag(data).also {
        check(it != 0L) { "Failed to read the M4A metadata container" }
    }
    private var closed = false

    fun setStringTag(ident: M4aIdent, value: String) {
        checkOpen()
        RustLib.setM4aStringTag(tagPtr, ident.value, value)
    }

    fun setArtwork(value: ByteArray) {
        checkOpen()
        RustLib.setM4aArtwork(tagPtr, value)
    }

    fun setDisc(discNumber: Int, totalDiscs: Int) {
        checkOpen()
        RustLib.setM4aDisc(tagPtr, discNumber, totalDiscs)
    }

    fun setTrack(trackNumber: Int, totalTracks: Int) {
        checkOpen()
        RustLib.setM4aTrack(tagPtr, trackNumber, totalTracks)
    }

    fun setCompilation() {
        checkOpen()
        RustLib.setM4aCompilation(tagPtr)
    }

    fun setIsrc(isrc: String) {
        checkOpen()
        RustLib.setM4aIsrc(tagPtr, isrc)
    }

    fun writeTag(): ByteArray? {
        checkOpen()
        return RustLib.writeM4aTag(tagPtr, data)
    }

    private fun checkOpen() {
        check(!closed) { "M4A metadata container is closed" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        RustLib.releaseM4aTag(tagPtr)
    }
}
