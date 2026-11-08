package com.simone.jarvismobile.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** BETA 2/8 — missing / unloadable / loading model: each cause gets its own honest message. */
class ModelAvailabilityTest {
    private fun kindOf(state: ModelRuntimeState, path: String?, exists: Boolean, detail: String = ""): ModelUnavailableKind =
        (ModelAvailabilityResolver.resolve(state, path, exists, detail) as ModelAvailability.Unavailable).kind

    @Test fun loadedModelIsReady() {
        assertEquals(ModelAvailability.Ready, ModelAvailabilityResolver.resolve(ModelRuntimeState.LOADED, "/m/x.litertlm", true))
    }

    @Test fun noModelImportedAtAll() {
        assertEquals(ModelUnavailableKind.NO_MODEL_IMPORTED, kindOf(ModelRuntimeState.UNLOADED, null, false))
        assertEquals(ModelUnavailableKind.NO_MODEL_IMPORTED, kindOf(ModelRuntimeState.UNLOADED, "  ", false))
    }

    @Test fun configuredFileDisappeared() {
        assertEquals(ModelUnavailableKind.MODEL_FILE_MISSING, kindOf(ModelRuntimeState.UNLOADED, "/m/x.litertlm", false))
    }

    @Test fun incompatibleOrBrokenModelReportsTheEngineDetailAndNotANetworkProblem() {
        val m = (ModelAvailabilityResolver.resolve(ModelRuntimeState.ERROR, "/m/x.litertlm", true, "IllegalArgumentException: bad magic")
            as ModelAvailability.Unavailable)
        assertEquals(ModelUnavailableKind.LOAD_FAILED, m.kind)
        assertTrue("bad magic" in m.message)
        assertFalse("Internet" in m.message.replace("senza Internet", "") && "rete" in m.message.lowercase(), m.message)
    }

    @Test fun stillLoadingIsNotReportedAsFailure() {
        assertEquals(ModelUnavailableKind.LOADING, kindOf(ModelRuntimeState.LOADING, "/m/x.litertlm", true))
    }

    @Test fun presentButUnloadedAsksToLoad() {
        assertEquals(ModelUnavailableKind.NOT_LOADED, kindOf(ModelRuntimeState.UNLOADED, "/m/x.litertlm", true))
    }

    @Test fun messagesAreDistinctAndNeverEchoUserText() {
        val msgs = listOf(
            kindOf(ModelRuntimeState.UNLOADED, null, false), kindOf(ModelRuntimeState.UNLOADED, "/p", false),
            kindOf(ModelRuntimeState.LOADING, "/p", true), kindOf(ModelRuntimeState.ERROR, "/p", true),
            kindOf(ModelRuntimeState.UNLOADED, "/p", true),
        )
        assertEquals(5, msgs.toSet().size)
        val all = listOf(ModelRuntimeState.UNLOADED, ModelRuntimeState.LOADING, ModelRuntimeState.ERROR).map {
            ModelAvailabilityResolver.resolve(it, "/p", true, "x")
        }
        all.forEach { assertIs<ModelAvailability.Unavailable>(it); assertFalse("Ho capito" in it.message) }
    }

    @Test fun longDetailIsTruncated() {
        val m = ModelAvailabilityResolver.resolve(ModelRuntimeState.ERROR, "/p", true, "x".repeat(900)) as ModelAvailability.Unavailable
        assertTrue(m.message.length < 400)
    }
}
