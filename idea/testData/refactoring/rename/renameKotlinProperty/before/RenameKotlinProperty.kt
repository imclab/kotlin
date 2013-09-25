package testing.rename

trait AP {
    val first: Int
}

public open class BP: AP {
    override val first = 1
}

class CP: BP() {
    override val first = 2 // <--- Here
}

class CPOther {
    val first: Int = 111
}

fun usagesProp() {
    val b = BP()
    val a: AP = b
    val c = CP()

    a.first
    b.first
    c.first

    CPOther().first
}