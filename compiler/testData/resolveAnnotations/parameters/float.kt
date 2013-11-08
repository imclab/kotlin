package test

annotation class Ann(
        val b1: Float,
        val b2: Float,
        val b3: Float,
        val b4: Float
)

Ann(1.0, 1.toFloat(), 1.0.toFloat()) class MyClass

// EXPECTED: Ann[b1 = 1.0.toFloat(): jet.Float, b2 = 1.0.toFloat(): jet.Float, b3 = 1.0.toFloat(): jet.Float]