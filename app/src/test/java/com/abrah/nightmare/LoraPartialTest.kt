package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ Saved #8071: a LoRA the engine applied in part is named, with its counts, for this request only ([LoraPartial]). */
class LoraPartialTest {
    private val earlier = "  1.0ms [ ERROR ] [dit] lora.hpp:1029 - Only (10 / 20) LoRA tensors have been applied, lora_file_path = /x/_loras/old.safetensors"
    private val partial = "254608.7ms [ ERROR ] [dit] lora.hpp:1029 - Only (840 / 1092) LoRA tensors have been applied, lora_file_path = /storage/emulated/0/Android/data/com.abrah.nightmare/files/models/_loras/odette_v1.safetensors"
    private val whole = "254609.8ms [  INFO ] [dit] lora.hpp:1032 - (840 / 840) LoRA tensors have been applied, lora_file_path = /x/_loras/nffastyle_animaV1.4.safetensors"

    @Test
    fun theLoraAppliedInPartIsNamedWithItsCounts() {
        val lines = listOf(earlier, "Req Rcvd: P:[prompt removed]", partial, whole, partial)
        assertEquals(listOf(LoraPartial.Partial("odette_v1", 840, 1092)), LoraPartial.of(lines))
    }

    @Test
    fun anEarlierRequestsLineIsNotReportedAgain() {
        assertEquals(emptyList<LoraPartial.Partial>(), LoraPartial.of(listOf(partial, "Req Rcvd: P:[prompt removed]", whole)))
    }
}
