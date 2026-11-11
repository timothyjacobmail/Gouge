package com.example.installlock;

/** Fixed-parameter JNI boundary. Neither passwords nor verification decisions are cached here. */
final class NativePasswordKdf {
    static final boolean available;
    static {
        boolean loaded;
        try {
            System.loadLibrary("gouge_password_kdf");
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException unavailable) {
            loaded = false;
        }
        available = loaded;
    }
    private NativePasswordKdf() {}
    static native byte[] derive(byte[] passwordUtf8, byte[] salt);
}
