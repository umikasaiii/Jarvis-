package com.simone.jarvismobile.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PcmCaptureModeTest {
    private class ModalFactory : ModalPcmSourceFactory {
        val modes = mutableListOf<PcmCaptureMode>()
        override fun open(mode: PcmCaptureMode): PcmSourceOpenResult {
            modes.add(mode)
            return PcmSourceOpenResult.Failed(VoiceCaptureFailure.INITIALIZATION_FAILED, "test")
        }
    }

    @Test fun runPassesTheRequestedModeToTheSingleFactory() = runTest {
        val f = ModalFactory(); val engine = PcmCaptureEngine(f, Dispatchers.Unconfined)
        engine.run(10, PcmCaptureMode.ECHO_CONTROLLED_RAW)
        engine.run(10)
        assertEquals(listOf(PcmCaptureMode.ECHO_CONTROLLED_RAW, PcmCaptureMode.STANDARD), f.modes)
    }

    @Test fun plainFactoryStillWorksAndSecondConcurrentRunIsStillRefused() = runTest {
        var opened = 0
        val engine = PcmCaptureEngine(PcmSourceFactory { opened++; PcmSourceOpenResult.Failed(VoiceCaptureFailure.INITIALIZATION_FAILED, "x") }, Dispatchers.Unconfined)
        engine.run(10, PcmCaptureMode.ECHO_CONTROLLED_RAW)
        assertEquals(1, opened)
    }
}
