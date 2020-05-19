// IGNORE_BACKEND_FIR: JVM_IR

fun interface KRunnable {
    fun invoke()
}

object OK : () -> Unit {
    override fun invoke() {
        result = "OK"
    }
}

fun foo(k: KRunnable) {
    k.invoke()
}

var result: String = ""

fun box(): String {
    foo(OK)
    return result
}