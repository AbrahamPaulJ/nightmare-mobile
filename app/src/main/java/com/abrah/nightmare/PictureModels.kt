package com.abrah.nightmare

/**
 * ⭐⭐⭐ **Every CPU model that reads a picture, let go at once** — the segmenter,
 * the parser, the pose detector, the depth estimator, IP-Adapter's encoder +
 * heads and the prompt's describe models ([DescribeMode.models]). Each holds itself for [com.abrah.nightmare.segment.IdleRelease.IDLE_MS]
 * after use; this skips the wait.
 *
 * ⚠⚠⚠ Called BEFORE a Swap render reaches the backend ([SdSampler]), not only on
 * memory pressure. Found 2026-10-07 on the S25 Ultra (12 GB): depth + 2 LoRAs +
 * IP-Adapter Face on SDXL Swap killed the FOREGROUND app — `lmkd: Reclaim
 * 'com.abrah.nightmare' … oom_score_adj 0 … 73572kB rss, 2483056kB swap; min
 * watermark is breached`. IP-Adapter's ViT-H encoder (1.2 GB) and SDXL head
 * (425 MB) were still held, inside their 60 s idle window, while the backend
 * loaded the 2.7 GB UNet and the 1.3 GB ControlNet. The render needs the RAM
 * more than the next tap needs a warm model: a picture's K/V, hints and masks
 * are cached, so only a NEW picture pays the reopen.
 *
 * ⚠ ONE list, because the memory-pressure path ([MainActivity.onTrimMemory])
 * named four of the five and left out IP-Adapter, the biggest — a rule honoured
 * in N−1 of N places.
 */
object PictureModels {
    fun releaseAll() {
        com.abrah.nightmare.segment.Segmenter.trim()
        com.abrah.nightmare.segment.Parser.trim()
        com.abrah.nightmare.pose.PoseDetector.trim()
        com.abrah.nightmare.pose.DepthEstimator.trim()
        IpAdapter.trim()
        DescribeMode.models.forEach { it.trim() }
    }
}
