package testing.rename

trait AP {
    var first: Int
}

public open class BP: AP {
    override var first = 1
}

class CP: BP() {
    override var first = 2
}

class CPOther {
    var first: Int = 111
}

fun usagesProp() {
    val b = BP()
    val a: AP = b
    val c = CP()

    a.first
    b.first
    c.first

    a.first = 1
    b.first = 2
    c.first = 3

    CPOther().first
}