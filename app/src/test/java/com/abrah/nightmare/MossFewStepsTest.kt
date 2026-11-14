package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ Item 62 (Saved #7949): an 8-step MOSS model run with fewer steps gets a warning; the 4-step model does not. */
class MossFewStepsTest {
    @Test
    fun anEightStepMossBelowEightIsWarned() {
        assertEquals(8, SdSampler.MOSS.fewMossSteps(mapOf("model" to "moss_image_turbo", "steps" to "4")))
        assertNull(SdSampler.MOSS.fewMossSteps(mapOf("model" to "moss_image_turbo", "steps" to "8")))
        assertNull("the 4-step model at 4", SdSampler.MOSS.fewMossSteps(mapOf("model" to "moss_image_4step", "steps" to "4")))
        assertNull("another family", SdSampler.FLUX2.fewMossSteps(mapOf("model" to "moss_image_turbo", "steps" to "4")))
        assertNull("an unknown model", SdSampler.MOSS.fewMossSteps(mapOf("model" to "nope", "steps" to "4")))
    }
}
