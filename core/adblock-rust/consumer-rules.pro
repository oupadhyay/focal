# Preserve JNI entrypoints on RustAdblockBridge under R8 full mode
-keep class app.focal.adblock.RustAdblockBridge {
    native <methods>;
}
