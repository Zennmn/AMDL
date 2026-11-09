package com.kyant.amdl.engine

class Cdm : AutoCloseable {

    private val ptr: Long = RustLib.createCdm().also {
        check(it != 0L) { "Failed to initialize the native content decryption module" }
    }
    private var closed = false

    @Synchronized
    fun createLicenseRequest(pssh: ByteArray): CdmLicenseRequest {
        check(!closed) { "Content decryption module is closed" }
        val requestPtr = RustLib.createCdmLicenseRequest(ptr, pssh)
        check(requestPtr != 0L) { "Failed to create a content license request" }
        return CdmLicenseRequest(requestPtr)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        RustLib.releaseCdm(ptr)
    }
}
