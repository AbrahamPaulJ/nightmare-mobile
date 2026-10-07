package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ⭐⭐⭐ **Find SD 1.5 / SDXL LoRAs on CivitAI and Hugging Face, and turn one into
 * a URL and a size the app's one downloader can fetch** (`docs/LORA-BROWSER.md`).
 *
 * ⚠⚠ Everything here was MEASURED before it was written (2026-10-06/07, §1 of
 * that doc), and the shape follows the measurements, not the APIs' docs:
 * - CivitAI answers `451 REGION_BLOCKED` to search from Australia but not to a
 *   download or a model looked up by id — so every call maps 451 to
 *   [Failure.RegionBlocked] and the UI offers the paste-a-link route.
 * - 32 of 40 popular LoRAs refuse a download without the user's key (401); with
 *   it 28 open and 4 are paid early access (403 + a deadline).
 * - The download is a 307 to a pre-signed storage URL. [resolve] follows it BY
 *   HAND so the key never travels to the storage host, and asks that host for
 *   the exact length — [ModelInstaller.fetch] checks the file against it.
 *
 * ⚠ Blocking network calls: call from `Dispatchers.IO`.
 */
object LoraSources {

    enum class Source { CIVITAI, HUGGINGFACE }

    /**
     * ⭐ What the browser serves — SD 1.5 and SDXL only (the user's call,
     * 2026-10-07). The Swap models are the only SD ones that can APPLY a LoRA;
     * a plain QNN graph has its weights baked.
     *
     * ⚠ `Pony V7` is not here on purpose: it is AuraFlow, not SDXL.
     */
    enum class Target(val label: String, val civitai: List<String>, val hfBases: List<String>) {
        SD15(
            "SD 1.5",
            listOf("SD 1.5", "SD 1.5 LCM", "SD 1.5 Hyper"),
            listOf("stable-diffusion-v1-5/stable-diffusion-v1-5", "runwayml/stable-diffusion-v1-5"),
        ),
        SDXL(
            "SDXL",
            listOf("SDXL 1.0", "Illustrious", "Pony", "NoobAI", "SDXL Lightning", "SDXL Hyper"),
            listOf(
                "stabilityai/stable-diffusion-xl-base-1.0",
                "OnomaAIResearch/Illustrious-xl-early-release-v0",
                "OnomaAIResearch/Illustrious-XL-v1.0",
            ),
        );

        companion object {
            /** The target a model's LoRAs come from, or null when it cannot take one. */
            fun of(family: Family): Target? = when (family) {
                Family.SD15_SWAP -> SD15
                Family.SDXL_SWAP -> SDXL
                else -> null
            }
        }
    }

    enum class Sort(val civitai: String, val hf: String) {
        DOWNLOADS("Most Downloaded", "downloads"),
        RATED("Highest Rated", "likes"),
        NEWEST("Newest", "createdAt"),
    }

    /** One downloadable file. [url] is CivitAI's download endpoint or a Hugging Face `resolve` URL. */
    data class LoraFile(val name: String, val bytes: Long, val sha256: String?, val url: String)

    data class Version(
        val id: String,
        val name: String,
        val baseModel: String,
        val trigger: List<String>,
        /** Null when the version has no `.safetensors` (a pickle is never offered). */
        val file: LoraFile?,
        val earlyAccess: Boolean = false,
    )

    data class Hit(
        val source: Source,
        /** CivitAI model id, or a Hugging Face repo id. */
        val id: String,
        val name: String,
        val creator: String?,
        val thumb: String?,
        val downloads: Long,
        val nsfw: Boolean,
        /** Empty for a Hugging Face search hit until [details] lists the repo's files. */
        val versions: List<Version>,
        val pageUrl: String,
    )

    data class Page(val hits: List<Hit>, val next: String?)

    /** The failures a person can act on — each has its own message in the UI. */
    sealed class Failure(message: String) : IOException(message) {
        class RegionBlocked : Failure("not available in this region")
        class NeedsKey : Failure("needs a CivitAI API key")
        class BadKey : Failure("the CivitAI API key was refused")
        class EarlyAccess(val until: String?) : Failure("early access${until?.let { " until $it" } ?: ""}")
        class Http(val code: Int) : Failure("HTTP $code")
    }

    /**
     * ⚠ A LoRA bigger than this is not a LoRA (a checkpoint, or a mis-tagged
     * repo — the top "Z-Image LoRA" on Hugging Face was 1–11 GB files). The
     * largest SDXL LoRAs in common use are ~1.7 GB full-rank extractions.
     */
    const val MAX_LORA_BYTES = 2L shl 30

    fun civitaiHost(mature: Boolean) = if (mature) "https://civitai.red" else "https://civitai.com"

    // ---- search ------------------------------------------------------------

    fun search(
        source: Source,
        target: Target,
        query: String,
        sort: Sort,
        cursor: String? = null,
        mature: Boolean = false,
        key: String? = null,
    ): Page = when (source) {
        Source.CIVITAI -> civitaiSearch(target, query, sort, cursor, mature, key)
        Source.HUGGINGFACE -> hfSearch(target, query, sort)
    }

    private fun civitaiSearch(target: Target, query: String, sort: Sort, cursor: String?, mature: Boolean, key: String?): Page {
        val q = buildList {
            add("types=LORA")
            add("limit=20")
            add("sort=" + enc(sort.civitai))
            if (query.isNotBlank()) add("query=" + enc(query.trim()))
            target.civitai.forEach { add("baseModels=" + enc(it)) }
            if (mature) add("nsfw=true")
            cursor?.let { add("cursor=" + enc(it)) }
        }.joinToString("&")
        val j = JSONObject(get("${civitaiHost(mature)}/api/v1/models?$q", key))
        return Page(
            parseCivitaiItems(j.optJSONArray("items") ?: JSONArray(), target, mature),
            j.optJSONObject("metadata")?.optString("nextCursor")?.takeIf { it.isNotBlank() },
        )
    }

    /** ⚠ `internal` for the JVM tests — a recorded page goes straight in. */
    internal fun parseCivitaiItems(items: JSONArray, target: Target, mature: Boolean): List<Hit> =
        (0 until items.length()).mapNotNull { i -> parseCivitaiModel(items.getJSONObject(i), target, mature) }

    internal fun parseCivitaiModel(m: JSONObject, target: Target, mature: Boolean): Hit? {
        val versions = m.optJSONArray("modelVersions") ?: return null
        // ⚠ A model can carry versions for several bases; only this target's.
        val mine = (0 until versions.length()).map { versions.getJSONObject(it) }
            .filter { it.optString("baseModel") in target.civitai }
        if (mine.isEmpty()) return null
        val host = civitaiHost(mature)
        return Hit(
            source = Source.CIVITAI,
            id = m.optLong("id").toString(),
            name = m.optString("name"),
            creator = m.optJSONObject("creator")?.optString("username")?.takeIf { it.isNotBlank() },
            thumb = mine.firstNotNullOfOrNull { civitaiThumb(it) },
            downloads = m.optJSONObject("stats")?.optLong("downloadCount") ?: 0L,
            nsfw = m.optBoolean("nsfw"),
            versions = mine.map { v ->
                Version(
                    id = v.optLong("id").toString(),
                    name = v.optString("name"),
                    baseModel = v.optString("baseModel"),
                    trigger = v.optJSONArray("trainedWords").strings().filter { it.isNotBlank() },
                    file = civitaiFile(v, host),
                    earlyAccess = v.optString("availability") == "EarlyAccess",
                )
            },
            pageUrl = "$host/models/${m.optLong("id")}",
        )
    }

    /** The version's `.safetensors` model file — the primary one when there are several. */
    private fun civitaiFile(v: JSONObject, host: String): LoraFile? {
        val files = v.optJSONArray("files") ?: return null
        val all = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("type") == "Model" && it.optString("name").endsWith(".safetensors", ignoreCase = true) }
        val f = all.firstOrNull { it.optBoolean("primary") } ?: all.firstOrNull() ?: return null
        return LoraFile(
            name = f.optString("name"),
            // ⚠ An ESTIMATE from `sizeKB` (a float): [resolve] asks the storage
            // host for the exact length before anything is written.
            bytes = (f.optDouble("sizeKB", 0.0) * 1024).toLong(),
            sha256 = f.optJSONObject("hashes")?.optString("SHA256")?.takeIf { it.isNotBlank() }?.lowercase(),
            url = "$host/api/download/models/${v.optLong("id")}",
        )
    }

    /** A small preview: the first still image, asked for at 256 px wide. */
    private fun civitaiThumb(v: JSONObject): String? {
        val images = v.optJSONArray("images") ?: return null
        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            if (img.optString("type", "image") != "image") continue
            val url = img.optString("url").takeIf { it.isNotBlank() } ?: continue
            return url.replace(Regex("/(original=true|width=\\d+)/"), "/width=256/")
        }
        return null
    }

    private fun hfSearch(target: Target, query: String, sort: Sort): Page {
        // ⚠ Hugging Face ANDs repeated `filter`s, so one request per base, merged.
        val seen = LinkedHashMap<String, Hit>()
        for (base in target.hfBases) {
            val q = buildList {
                add("filter=" + enc("base_model:adapter:$base"))
                if (query.isNotBlank()) add("search=" + enc(query.trim()))
                add("sort=${sort.hf}")
                add("limit=30")
                listOf("cardData", "downloads", "author", "gated").forEach { add("expand%5B%5D=$it") }
            }.joinToString("&")
            val arr = JSONArray(get(Prefs.apply("${Prefs.HF_ORIGIN}api/models?$q"), null))
            parseHfItems(arr).forEach { seen.putIfAbsent(it.id, it) }
        }
        val hits = seen.values.toList()
        return Page(if (sort == Sort.DOWNLOADS) hits.sortedByDescending { it.downloads } else hits, null)
    }

    internal fun parseHfItems(arr: JSONArray): List<Hit> = (0 until arr.length()).mapNotNull { i ->
        val m = arr.getJSONObject(i)
        // ⚠ A gated repo needs a token we do not ask for.
        if (m.opt("gated").let { it != null && it != false && it != "false" }) return@mapNotNull null
        val id = m.optString("id").takeIf { it.contains('/') } ?: return@mapNotNull null
        val card = m.optJSONObject("cardData")
        Hit(
            source = Source.HUGGINGFACE,
            id = id,
            name = id.substringAfter('/'),
            creator = m.optString("author").takeIf { it.isNotBlank() } ?: id.substringBefore('/'),
            thumb = hfThumb(id, card),
            downloads = m.optLong("downloads"),
            nsfw = false,
            versions = emptyList(),
            pageUrl = "${Prefs.HF_ORIGIN}$id",
        )
    }

    private fun hfThumb(repo: String, card: JSONObject?): String? {
        val w = card?.optJSONArray("widget") ?: return null
        for (i in 0 until w.length()) {
            val url = w.optJSONObject(i)?.optJSONObject("output")?.optString("url")?.takeIf { it.isNotBlank() } ?: continue
            return if (url.startsWith("http")) url else "${Prefs.HF_ORIGIN}$repo/resolve/main/${url.trimStart('/')}"
        }
        return null
    }

    // ---- one model ---------------------------------------------------------

    /**
     * ⭐ The full entry for a hit — CivitAI already has it; a Hugging Face repo
     * is listed for its `.safetensors` files, one [Version] each.
     */
    fun details(hit: Hit, target: Target): Hit = when (hit.source) {
        Source.CIVITAI -> hit
        Source.HUGGINGFACE -> {
            val j = JSONObject(get(Prefs.apply("${Prefs.HF_ORIGIN}api/models/${hit.id}?blobs=true"), null))
            hit.copy(versions = parseHfFiles(hit.id, j, target))
        }
    }

    internal fun parseHfFiles(repo: String, j: JSONObject, target: Target): List<Version> {
        val trigger = j.optJSONObject("cardData")?.optString("instance_prompt")
            ?.takeIf { it.isNotBlank() && it != "null" }?.let { listOf(it) }.orEmpty()
        val sib = j.optJSONArray("siblings") ?: return emptyList()
        return (0 until sib.length()).map { sib.getJSONObject(it) }
            .filter { it.optString("rfilename").endsWith(".safetensors", ignoreCase = true) }
            .map { s ->
                val path = s.optString("rfilename")
                Version(
                    id = path,
                    name = path.substringAfterLast('/'),
                    baseModel = target.label,
                    trigger = trigger,
                    file = LoraFile(
                        name = path.substringAfterLast('/'),
                        bytes = s.optLong("size"),
                        sha256 = s.optJSONObject("lfs")?.optString("sha256")?.takeIf { it.isNotBlank() }?.lowercase(),
                        url = "${Prefs.HF_ORIGIN}$repo/resolve/main/" + path.split('/').joinToString("/") { enc(it).replace("+", "%20") },
                    ),
                )
            }
    }

    /** ⭐ A CivitAI model by id — the route a pasted link takes, and the one AU is not blocked on. */
    fun civitaiModel(id: String, target: Target, mature: Boolean, key: String?): Hit? =
        parseCivitaiModel(JSONObject(get("${civitaiHost(mature)}/api/v1/models/$id", key)), target, mature)

    /** A Hugging Face repo as a hit, for a pasted link. */
    fun hfRepo(repo: String, target: Target): Hit {
        val j = JSONObject(get(Prefs.apply("${Prefs.HF_ORIGIN}api/models/$repo?blobs=true"), null))
        return Hit(
            source = Source.HUGGINGFACE,
            id = repo,
            name = repo.substringAfter('/'),
            creator = repo.substringBefore('/'),
            thumb = hfThumb(repo, j.optJSONObject("cardData")),
            downloads = j.optLong("downloads"),
            nsfw = false,
            versions = parseHfFiles(repo, j, target),
            pageUrl = "${Prefs.HF_ORIGIN}$repo",
        )
    }

    // ---- links -------------------------------------------------------------

    sealed class Link {
        data class Civitai(val modelId: String, val versionId: String?) : Link()
        data class HuggingFace(val repo: String) : Link()
    }

    /** ⭐ A pasted CivitAI (`.com` or `.red`) model link or a Hugging Face repo link, else null. */
    fun parseLink(text: String): Link? {
        val t = text.trim()
        Regex("""^https?://(?:www\.)?civitai\.(?:com|red|green)/models/(\d+)(?:[^?#]*)?(?:\?[^#]*?modelVersionId=(\d+))?""")
            .find(t)?.let { return Link.Civitai(it.groupValues[1], it.groupValues[2].ifBlank { null }) }
        Regex("""^https?://(?:huggingface\.co|hf-mirror\.com)/([\w.-]+/[\w.-]+)""")
            .find(t)?.let { m ->
                val repo = m.groupValues[1]
                // ⚠ Not a repo: the site's own pages under the same pattern.
                if (repo.substringBefore('/') in setOf("datasets", "spaces", "docs", "models", "api")) return null
                return Link.HuggingFace(repo)
            }
        return null
    }

    // ---- download ----------------------------------------------------------

    data class Resolved(val url: String, val bytes: Long)

    /**
     * ⭐⭐ The URL [ModelInstaller.fetch] downloads, and its EXACT length.
     *
     * ⚠⚠ CivitAI: GET the download endpoint WITH the key and WITHOUT following
     * the 307, then probe the pre-signed URL WITHOUT the key. A pre-signed URL
     * carries its own signature; sending an `Authorization` header to that host
     * as well is the "two auth mechanisms" request S3-style storage refuses.
     * Resolved again on every attempt: the signature expires (24 h).
     */
    fun resolve(file: LoraFile, key: String?): Resolved {
        if (!file.url.contains("/api/download/models/")) {
            return Resolved(Prefs.apply(file.url), file.bytes)
        }
        val conn = open(file.url, key).apply { instanceFollowRedirects = false }
        val signed = try {
            when (val code = conn.responseCode) {
                in 300..399 -> conn.getHeaderField("Location") ?: throw Failure.Http(code)
                in 200..299 -> file.url
                else -> throw failureOf(code, conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty(), key)
            }
        } finally {
            conn.disconnect()
        }
        return Resolved(signed, lengthOf(signed))
    }

    /** The exact length, from a one-byte range request — a HEAD would not match the GET signature. */
    private fun lengthOf(url: String): Long {
        val conn = open(url, null).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
            }
            if (code in 200..299 && conn.contentLengthLong > 0) return conn.contentLengthLong
            throw Failure.Http(code)
        } finally {
            conn.disconnect()
        }
    }

    // ---- http --------------------------------------------------------------

    private fun open(url: String, key: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NightmareMobile")
            setRequestProperty("Accept", "application/json")
            if (!key.isNullOrBlank() && url.contains("civitai.")) setRequestProperty("Authorization", "Bearer ${key.trim()}")
        }

    private fun get(url: String, key: String?): String {
        val conn = open(url, key)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw failureOf(code, conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty(), key)
            }
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun failureOf(code: Int, body: String, key: String?): Failure = when {
        code == 451 || body.contains("REGION_BLOCKED") -> Failure.RegionBlocked()
        code == 403 && body.contains("Early Access") ->
            Failure.EarlyAccess(runCatching { JSONObject(body).optString("deadline").takeIf { it.isNotBlank() } }.getOrNull())
        code == 401 -> if (key.isNullOrBlank()) Failure.NeedsKey() else Failure.BadKey()
        else -> Failure.Http(code)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
