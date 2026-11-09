package com.kyant.amdl.engine

class CdmLicenseRequest(private val ptr: Long) : AutoCloseable {

    private var closed = false

    @Synchronized
    fun getChallenge(): ByteArray? {
        check(!closed) { "Content license request is closed" }
        return RustLib.getChallenge(ptr)
    }

    @Synchronized
    fun getContentKey(license: ByteArray): ByteArray? {
        check(!closed) { "Content license request is closed" }
        return RustLib.getContentKey(ptr, license)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        RustLib.releaseCdmLicenseRequest(ptr)
    }
}
