package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelImportPolicyTest {
    private val gib = 1024L * 1024 * 1024
    private fun v(name: String?, size: Long = 2 * gib, free: Long = 10 * gib, inUse: Boolean = false) =
        ModelImportPolicy.validate(name, size, free, inUse, "model-1.litertlm")

    @Test fun acceptsALitertlmModel() {
        assertEquals(ImportVerdict.Accept("gemma-3n.litertlm"), v("gemma-3n.litertlm"))
        assertIs<ImportVerdict.Accept>(v("GEMMA.LITERTLM"))
    }

    @Test fun rejectsIncompatibleFormatsBeforeCopying() {
        for (n in listOf("model.task", "model.bin", "model.gguf", "model.litertlm.zip", "model")) {
            val r = v(n)
            assertIs<ImportVerdict.Reject>(r, n)
            assertEquals(ImportVerdict.Reason.UNSUPPORTED_FORMAT, r.reason)
            assertTrue("Nessun file è stato copiato" in r.message)
        }
    }

    @Test fun rejectsAnObviouslyTruncatedFile() {
        assertEquals(ImportVerdict.Reason.TOO_SMALL, (v("m.litertlm", size = 4096) as ImportVerdict.Reject).reason)
    }

    @Test fun unknownSizeIsNotRefused() {
        assertIs<ImportVerdict.Accept>(v("m.litertlm", size = -1))
    }

    @Test fun notEnoughFreeSpace() {
        val r = v("m.litertlm", size = 3 * gib, free = 2 * gib) as ImportVerdict.Reject
        assertEquals(ImportVerdict.Reason.NOT_ENOUGH_SPACE, r.reason)
        assertTrue("MiB" in r.message)
    }

    @Test fun refusesToOverwriteTheModelInUse() {
        val r = v("m.litertlm", inUse = true) as ImportVerdict.Reject
        assertEquals(ImportVerdict.Reason.IN_USE, r.reason)
        assertTrue("non è stato toccato" in r.message)
    }

    @Test fun displayNamesCannotCarryAPath() {
        assertFalse('/' in ModelImportPolicy.safeFileName("../../etc/passwd", "f"))
        assertFalse(".." in ModelImportPolicy.safeFileName("..", "f").let { if (it == "f") "" else it })
        assertEquals("f", ModelImportPolicy.safeFileName("   ", "f"))
        assertEquals("a_b.litertlm", ModelImportPolicy.safeFileName("a/b.litertlm", "f"))
        assertIs<ImportVerdict.Accept>(v("../x.litertlm"))
    }
}
