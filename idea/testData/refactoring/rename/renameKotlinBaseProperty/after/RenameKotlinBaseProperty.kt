package testing.rename

trait AP {
    var second: Int
}

public open class BP: AP {
    override var second = 1
}

class CP: BP() {
    override var second = 2
}

class CPOther {
    var first: Int = 111
}

fun usagesProp() {
    val b = BP()
    val a: AP = b
    val c = CP()

    a.second
    b.second
    c.second

    a.second = 1
    b.second = 2
    c.second = 3

    CPOther().first
}