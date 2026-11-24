// Mini substituto do JUnit, so para compilar o teste ponta a ponta fora do Gradle.
package org.junit
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
object Assert {
    @JvmStatic fun assertTrue(c: Boolean) { if (!c) throw AssertionError("esperado true") }
    @JvmStatic fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionError(m) }
    @JvmStatic fun assertFalse(c: Boolean) { if (c) throw AssertionError("esperado false") }
    @JvmStatic fun assertFalse(m: String, c: Boolean) { if (c) throw AssertionError(m) }
    @JvmStatic fun assertEquals(e: Any?, a: Any?) { if (e != a) throw AssertionError("esperado <$e> mas foi <$a>") }
    @JvmStatic fun assertEquals(e: Long, a: Long) { if (e != a) throw AssertionError("esperado <$e> mas foi <$a>") }
    @JvmStatic fun assertNotEquals(e: Any?, a: Any?) { if (e == a) throw AssertionError("nao esperado <$e>") }
    @JvmStatic fun assertNull(a: Any?) { if (a != null) throw AssertionError("esperado null: $a") }
    @JvmStatic fun assertNotNull(a: Any?) { if (a == null) throw AssertionError("esperado nao-null") }
}
