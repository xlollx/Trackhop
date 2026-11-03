package android.content

/**
 * A stand-in for Android's Context, for compiling only: the :bridge files the server builds from
 * (BridgeNet.kt) name it in `attach`, which only the Android app calls. Not in the server's jar.
 */
abstract class Context {
    abstract val applicationContext: Context
    abstract val filesDir: java.io.File
}
