package com.kyant.amdl.engine;

public final class RustLibSmoke {
    public static void main(String[] args) {
        byte[] empty = new byte[0];
        long cdm = RustLib.createCdm();
        if (cdm == 0) {
            throw new AssertionError("createCdm returned null");
        }
        RustLib.releaseCdm(cdm);

        if (RustLib.createCdmLicenseRequest(0, empty) != 0) {
            throw new AssertionError("null CDM should reject a license request");
        }
        RustLib.releaseCdmLicenseRequest(0);
        if (RustLib.getChallenge(0) != null) {
            throw new AssertionError("null request should not return a challenge");
        }
        if (RustLib.getContentKey(0, empty) != null) {
            throw new AssertionError("null request should not return a content key");
        }
        if (RustLib.decryptM4a(new byte[16], empty) != null) {
            throw new AssertionError("invalid M4A input should return null");
        }
        if (RustLib.readM4aTag(empty) != 0 || RustLib.writeM4aTag(0, empty) != null) {
            throw new AssertionError("invalid metadata arguments should return null");
        }

        RustLib.releaseM4aTag(0);
        RustLib.setM4aStringTag(0, 0, "ignored");
        RustLib.setM4aReservedTag(0, 0, empty);
        RustLib.setM4aArtwork(0, empty);
        RustLib.setM4aDisc(0, 1, 1);
        RustLib.setM4aTrack(0, 1, 1);
        RustLib.setM4aCompilation(0);
        RustLib.setM4aIsrc(0, "");

        System.out.println("JNI smoke test passed: 17 native entry points callable");
    }
}

final class RustLib {
    static {
        String library = System.getProperty("amdl.library");
        if (library == null || library.isBlank()) {
            throw new IllegalStateException("amdl.library must point to amdl.dll");
        }
        System.load(library);
    }

    static native long createCdm();
    static native void releaseCdm(long ptr);
    static native long createCdmLicenseRequest(long cdmPtr, byte[] pssh);
    static native void releaseCdmLicenseRequest(long requestPtr);
    static native byte[] getChallenge(long requestPtr);
    static native byte[] getContentKey(long requestPtr, byte[] license);
    static native byte[] decryptM4a(byte[] key, byte[] data);
    static native long readM4aTag(byte[] data);
    static native void releaseM4aTag(long tagPtr);
    static native void setM4aStringTag(long tagPtr, int ident, String value);
    static native void setM4aReservedTag(long tagPtr, int ident, byte[] value);
    static native void setM4aArtwork(long tagPtr, byte[] data);
    static native void setM4aDisc(long tagPtr, int discNumber, int totalDiscs);
    static native void setM4aTrack(long tagPtr, int trackNumber, int totalTracks);
    static native void setM4aCompilation(long tagPtr);
    static native void setM4aIsrc(long tagPtr, String isrc);
    static native byte[] writeM4aTag(long tagPtr, byte[] data);

}
