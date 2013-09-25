package testing.rename

trait AP {
    val second: Int
}

public open class BP: AP {
    override val second = 1
}

class CP: BP() {
    override val second = 2 // <--- Here
}

class CPOther {
    val first: Int = 111
}

fun usagesProp() {
    val b = BP()
    val a: AP = b
    val c = CP()

    a.second
    b.second
    c.second

    CPOther().first
}