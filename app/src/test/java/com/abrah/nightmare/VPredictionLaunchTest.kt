package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class VPredictionLaunchTest {
    @Test
    fun markerAddsBackendFlag() {
        val model = Files.createTempDirectory("vpred-model").toFile()
        try {
            model.resolve(BackendProcess.V_PRED_MARKER).writeBytes(ByteArray(0))
            assertEquals(listOf("--use_v_pred"), BackendProcess.vPredictionArgs(model))
        } finally {
            model.deleteRecursively()
        }
    }

    @Test
    fun flagReachesSd15SdxlAndSwapLaunchTypes() {
        val model = Files.createTempDirectory("vpred-families").toFile()
        try {
            model.resolve(BackendProcess.V_PRED_MARKER).writeBytes(ByteArray(0))
            val families = listOf(
                "SD1.5" to ModelCatalog.SD15_NPU,
                "SDXL" to ModelCatalog.SDXL_NPU,
                // Swap/template intentionally shares the SD1.5 backend type.
                "Swap/template" to ModelCatalog.SD15_NPU,
            )
            for ((family, backendType) in families) {
                val args = BackendProcess.modelLaunchArgs(backendType, model)
                assertEquals("$family backend type", backendType, args[1])
                assertEquals("$family v-pred flag", 1, args.count { it == "--use_v_pred" })
            }
        } finally {
            model.deleteRecursively()
        }
    }

    @Test
    fun ordinaryModelKeepsExistingLaunch() {
        val model = Files.createTempDirectory("epsilon-model").toFile()
        try {
            assertEquals(emptyList<String>(), BackendProcess.vPredictionArgs(model))
        } finally {
            model.deleteRecursively()
        }
    }
}
