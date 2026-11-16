package com.simone.jarvismobile.core.llm

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3 final gate — persistence safety of the registry and the identity of a qualified artifact. */
class M3ProfileIntegrityTest {
    private val dir = File.createTempFile("m3pi", "").let { it.delete(); it.mkdirs(); it }.also { it.deleteOnExit() }
    private fun reg(vararg names: String) = ModelProfileRegistry(names.map { n -> ModelProfiles.staticCheck(n, 1000, ByteArray(8), "0.14.0", 1L) })
    private fun f(n: String = "profiles.json") = File(dir, n + System.nanoTime().let { "" })

    // ---- persistence ----------------------------------------------------------------------------------------------------
    @Test fun aCommittedWriteIsReadBackAndTheJsonFormatIsTheOneAlreadyPublished() {
        val file = File(dir, "a.json"); val pf = ModelProfileFile(file)
        assertTrue(pf.save(reg("x.litertlm", "y.litertlm")))
        val back = ModelProfileFile(file).load()
        assertEquals(ModelProfileFile.Source.MAIN, back.source); assertEquals(2, back.registry.all().size)
        // a file written by the previous build (no "fp" key) is still read
        val legacy = """{"v":1,"profiles":[{"id":"x:1","name":"x","size":1,"format":"LITERTLM","runtime":"litertlm","rv":"0.14.0","backend":"CPU","decl":[],"tested":[],"interrupted":0,"state":"RUNTIME_LOADED"}]}"""
        File(dir, "l.json").writeText(legacy)
        assertEquals(1, ModelProfileFile(File(dir, "l.json")).load().registry.all().size)
    }

    @Test fun aFailedWriteLeavesThePreviousVersionWholeAndReportsFalse() {
        val file = File(dir, "b.json")
        assertTrue(ModelProfileFile(file).save(reg("old.litertlm")))
        val before = file.readText()
        val crashing = ModelProfileFile(file, beforeCommit = { throw IOException("disk full") })
        assertFalse(crashing.save(reg("new.litertlm", "other.litertlm")))
        assertEquals(before, file.readText(), "the only valid version was not touched")
        assertEquals(listOf("old.litertlm"), ModelProfileFile(file).load().registry.all().map { it.name })
    }

    @Test fun anInterruptedWriteLeavesAStaleTmpThatIsIgnoredThenOverwritten() {
        val file = File(dir, "c.json")
        ModelProfileFile(file).save(reg("good.litertlm"))
        File(dir, "c.json.tmp").writeText("{\"v\":1,\"profiles\":[{\"id\":\"half") // a torn temp file
        assertEquals(listOf("good.litertlm"), ModelProfileFile(file).load().registry.all().map { it.name })
        assertTrue(ModelProfileFile(file).save(reg("good.litertlm", "next.litertlm")))
        assertEquals(2, ModelProfileFile(file).load().registry.all().size)
    }

    @Test fun aGarbledMainFileFallsBackToTheLastGoodCopyNotToNoProfiles() {
        val file = File(dir, "d.json"); val pf = ModelProfileFile(file)
        pf.save(reg("one.litertlm")); pf.save(reg("one.litertlm", "two.litertlm")) // the second save backs the first up
        file.writeText("\u0000\u0000garbage")
        val l = ModelProfileFile(file).load()
        assertEquals(ModelProfileFile.Source.BACKUP, l.source); assertEquals(listOf("one.litertlm"), l.registry.all().map { it.name })
        // a garbled main file never replaces the good backup
        assertTrue(pf.save(reg("three.litertlm")))
        assertEquals(listOf("one.litertlm"), ModelProfileFile(File(dir, "d.json.bak").let { b -> File(dir, "d.json").also { } }).let { _ -> ModelProfileRegistry.decode(File(dir, "d.json.bak").readText()).all().map { it.name } })
    }

    @Test fun aMissingMainFileIsRecoveredFromTheBackupAndNothingAtAllIsEmpty() {
        val file = File(dir, "e.json"); val pf = ModelProfileFile(file)
        assertEquals(ModelProfileFile.Source.EMPTY, pf.load().source)
        pf.save(reg("a.litertlm")); pf.save(reg("a.litertlm", "b.litertlm"))
        file.delete()
        assertEquals(ModelProfileFile.Source.BACKUP, pf.load().source)
    }

    @Test fun thePersistenceNeverTouchesModelFiles() {
        val model = File(dir, "m.litertlm").also { it.writeBytes(ByteArray(64) { 1 }) }
        val sum = model.readBytes().contentHashCode()
        val pf = ModelProfileFile(File(dir, "f.json")); pf.save(reg("m.litertlm")); pf.save(reg()); File(dir, "f.json").writeText("x"); pf.load()
        assertEquals(sum, model.readBytes().contentHashCode()); assertTrue(model.isFile)
    }

    // ---- identity of a qualified artifact -----------------------------------------------------------------------------
    private val sha = "ab".repeat(32)
    private val fp1 = ModelProfiles.fingerprint(3_000_000_000, 1000L)
    private fun tested(): ModelProfile {
        val s = ModelProfiles.staticCheck("g.litertlm", 3_000_000_000, ByteArray(8), "0.14.0", 1L).copy(fingerprint = fp1)
        val l = ModelProfiles.onLoad(s, true, null, 2L, 100, "FAST", 4096, "CPU", "0.14.0", "dev", 1, 2)
        return ModelProfiles.onInference(l, true, 3L, 10)
    }
    private val ev = QualificationEvidence("dev", sha, "litertlm", "0.14.0", "CPU", "M4")
    private fun qualified() = ModelProfiles.qualify(ModelProfiles.withHash(tested(), sha, fp1), ev)

    @Test fun sameFileSameStampKeepsTheQualificationWithoutHashing() {
        val q = qualified(); assertEquals(QualificationState.DEVICE_QUALIFIED, q.state)
        assertEquals(q, ModelProfiles.checkArtifact(q, fp1)); assertTrue(ModelProfiles.qualifiedNow(q, fp1))
    }

    @Test fun sameNameAndSizeButADifferentFileLosesEveryProof() {
        val q = qualified()
        val replaced = ModelProfiles.checkArtifact(q, ModelProfiles.fingerprint(3_000_000_000, 2000L)) // same name + size, new mtime
        assertEquals(QualificationState.STATICALLY_COMPATIBLE, replaced.state)
        assertNull(replaced.qualification); assertNull(replaced.sha256); assertNull(replaced.lastInferenceOk)
        assertFalse(ModelProfiles.qualifiedNow(q, ModelProfiles.fingerprint(3_000_000_000, 2000L)))
    }

    @Test fun runtimeAndBackendChangesVoidTheQualification() {
        val q = qualified()
        assertNull(ModelProfiles.revalidate(q, "0.15.0", sha).qualification)
        assertFalse(ModelProfiles.qualifiedNow(q.copy(backend = "GPU"), fp1))
        assertFalse(ModelProfiles.qualifiedNow(q.copy(runtimeVersion = "0.15.0"), fp1))
    }

    @Test fun aProfileFromBeforeTheStampOrWithoutAHashCannotStayQualifiedOrProven() {
        val old = tested().copy(fingerprint = null)
        val c = ModelProfiles.checkArtifact(old, fp1)
        assertEquals(QualificationState.STATICALLY_COMPATIBLE, c.state); assertEquals(fp1, c.fingerprint)
        // qualification evidence without a hash (or without a stamp) is refused at qualification time and demoted when read back
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfiles.qualify(tested(), ev).state)
        val noHash = qualified().copy(sha256 = null)
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfiles.checkArtifact(noHash, fp1).state)
        val json = ModelProfileRegistry.encode(ModelProfileRegistry(listOf(qualified()))).replace(Regex(",\"fp\":\"[^\"]*\""), "")
        assertEquals(QualificationState.INFERENCE_TESTED, ModelProfileRegistry.decode(json).all().single().state)
    }

    @Test fun aRestartWithAQualifiedProfileKeepsItOnlyForTheSameFile() {
        val back = ModelProfileRegistry.decode(ModelProfileRegistry.encode(ModelProfileRegistry(listOf(qualified())))).all().single()
        assertEquals(QualificationState.DEVICE_QUALIFIED, back.state)
        assertTrue(ModelProfiles.qualifiedNow(back, fp1))
        assertNotEquals(QualificationState.DEVICE_QUALIFIED, ModelProfiles.checkArtifact(back, "other").state)
    }

    @Test fun loadingOrASimulatedTestNeverQualifies() {
        var p = ModelProfiles.checkArtifact(tested(), fp1)
        repeat(3) { p = ModelProfiles.onInference(ModelProfiles.onLoad(p, true, null, 5L, 1, "FAST", 4096, "CPU", "0.14.0", "dev", 1, 2), true, 6L, 1) }
        assertNotEquals(QualificationState.DEVICE_QUALIFIED, p.state)
        // evidence for another device / without proven inference is refused
        assertNotEquals(QualificationState.DEVICE_QUALIFIED, ModelProfiles.qualify(ModelProfiles.withHash(p, sha, fp1), ev.copy(deviceKey = "other")).state)
        assertNotEquals(QualificationState.DEVICE_QUALIFIED, ModelProfiles.qualify(ModelProfiles.withHash(ModelProfiles.onLoad(p.copy(state = QualificationState.UNTESTED), true, null, 1, 1, "F", 1, "CPU", "0.14.0", "dev", 1, 1), sha, fp1), ev).state)
    }
}
