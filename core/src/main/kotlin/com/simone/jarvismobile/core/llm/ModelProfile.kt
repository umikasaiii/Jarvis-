package com.simone.jarvismobile.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.security.MessageDigest

/**
 * M3-A — what is KNOWN about an imported model artifact, and how far it has been PROVEN. The levels are ordered and each one needs its own
 * evidence; nothing here is inferred from a file merely being present:
 *  - UNTESTED: imported, nothing checked.
 *  - STATICALLY_COMPATIBLE: the file passed the weak static check ([ModelProfiles.staticCheck]: expected extension, not empty, not a GGUF).
 *    This says nothing about whether the runtime loads it.
 *  - RUNTIME_LOADED: the runtime's `initialize()` returned for this artifact. It does NOT imply that it can answer.
 *  - INFERENCE_TESTED: a real generation returned text on this device/runtime/backend.
 *  - DEVICE_QUALIFIED: the M4 protocol was executed for exactly this device + artifact (hash) + runtime version + backend ([QualificationEvidence]).
 *  - FAILED: the last attempt failed; the real error is kept in [ModelProfile.lastError].
 */
enum class QualificationState { UNTESTED, STATICALLY_COMPATIBLE, RUNTIME_LOADED, INFERENCE_TESTED, DEVICE_QUALIFIED, FAILED }

enum class ArtifactFormat { LITERTLM, GGUF, UNKNOWN }

/** The exact things a device qualification is valid for. Any difference (other device, other file, other runtime version, other backend) voids it. */
data class QualificationEvidence(val deviceKey: String, val sha256: String, val runtime: String, val runtimeVersion: String, val backend: String, val protocolRef: String)

data class ModelProfile(
    /** Stable technical id: the SHA-256 prefix when known, else `name:size`. Never a path. */
    val id: String,
    /** Display name chosen by the user / the file name (no directory). */
    val name: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val format: ArtifactFormat = ArtifactFormat.UNKNOWN,
    val runtime: String = "litertlm",
    val runtimeVersion: String = "",
    /** CPU only unless a GPU backend has really been loaded and tested. */
    val backend: String = "CPU",
    /** Context window declared by the artifact, when it could be read from it; null = unknown (never assumed). */
    val artifactWindow: Int? = null,
    /** Context window CONFIGURED by JARVIS for the runtime (`EngineConfig.maxNumTokens`); distinct from [artifactWindow]. */
    val configuredWindow: Int? = null,
    /** Slot used for the last load attempt: FAST / ADVANCED / CLASSIFIER. */
    val lastSlot: String? = null,
    val declaredCapabilities: Set<String> = emptySet(),
    val testedCapabilities: Set<String> = emptySet(),
    /** "device model | Android | total RAM MB" of the last attempt — a coarse hardware profile, not an identifier of the user. */
    val deviceKey: String? = null,
    val lastAttemptAt: Long? = null,
    val lastLoadMs: Long? = null,
    val pssBeforeBytes: Long? = null,
    val pssAfterBytes: Long? = null,
    val lastError: String? = null,
    val lastInferenceAt: Long? = null,
    val lastInferenceOk: Boolean? = null,
    val lastInferenceMs: Long? = null,
    /** Loads that began and never finished (process killed): the native-crash signal. */
    val interruptedLoads: Int = 0,
    val qualification: QualificationEvidence? = null,
    val state: QualificationState = QualificationState.UNTESTED,
)

object ModelProfiles {
    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    fun format(name: String, header: ByteArray): ArtifactFormat = when {
        header.size >= 4 && header.copyOfRange(0, 4).contentEquals(GGUF_MAGIC) -> ArtifactFormat.GGUF
        name.endsWith(".litertlm", ignoreCase = true) -> ArtifactFormat.LITERTLM
        else -> ArtifactFormat.UNKNOWN
    }

    fun idOf(name: String, sizeBytes: Long, sha256: String?): String = sha256?.take(16)?.let { "sha:$it" } ?: "$name:$sizeBytes"

    /**
     * Weak, honest static check: LiteRT-LM needs a `.litertlm` container. A GGUF (even renamed) is recognised from its magic bytes and refused;
     * an unknown container is not claimed compatible. A pass means "worth trying", never "it loads".
     */
    fun staticCheck(name: String, sizeBytes: Long, header: ByteArray, runtimeVersion: String, nowMs: Long): ModelProfile {
        val fmt = format(name, header)
        val base = ModelProfile(id = idOf(name, sizeBytes, null), name = name, sizeBytes = sizeBytes, format = fmt, runtimeVersion = runtimeVersion, lastAttemptAt = nowMs)
        return when {
            sizeBytes <= 0L -> base.copy(state = QualificationState.FAILED, lastError = "empty_file")
            fmt == ArtifactFormat.GGUF -> base.copy(state = QualificationState.FAILED, lastError = "gguf_not_supported_by_litertlm")
            fmt == ArtifactFormat.LITERTLM -> base.copy(state = QualificationState.STATICALLY_COMPATIBLE)
            else -> base.copy(state = QualificationState.UNTESTED, lastError = "unrecognised_container")
        }
    }

    /** Streaming SHA-256 (hex): constant memory, safe for multi-GB files. The caller runs it off the main thread and only on demand. */
    fun sha256(input: InputStream, onProgress: ((Long) -> Unit)? = null): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n); total += n
            onProgress?.invoke(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun withHash(p: ModelProfile, hash: String): ModelProfile = p.copy(sha256 = hash, id = idOf(p.name, p.sizeBytes, hash))

    fun onLoad(
        p: ModelProfile, ok: Boolean, error: String?, nowMs: Long, loadMs: Long?, slot: String, configuredWindow: Int?, backend: String,
        runtimeVersion: String, deviceKey: String?, pssBefore: Long?, pssAfter: Long?,
    ): ModelProfile {
        val common = p.copy(
            lastAttemptAt = nowMs, lastLoadMs = loadMs, lastSlot = slot, configuredWindow = configuredWindow, backend = backend,
            runtimeVersion = runtimeVersion, deviceKey = deviceKey, pssBeforeBytes = pssBefore, pssAfterBytes = pssAfter,
        )
        if (!ok) return common.copy(state = QualificationState.FAILED, lastError = error?.take(300) ?: "load_failed")
        // a load never lowers a stronger, still-valid proof; it never creates an inference proof
        val keep = common.state == QualificationState.INFERENCE_TESTED || (common.state == QualificationState.DEVICE_QUALIFIED && qualificationStillValid(common))
        return common.copy(lastError = null, state = if (keep) common.state else QualificationState.RUNTIME_LOADED)
    }

    fun onInference(p: ModelProfile, ok: Boolean, nowMs: Long, durationMs: Long?, capability: String? = null): ModelProfile {
        val base = p.copy(lastInferenceAt = nowMs, lastInferenceOk = ok, lastInferenceMs = durationMs)
        if (!ok) return base
        // an inference is only evidence after a load: from UNTESTED/STATICALLY_COMPATIBLE/FAILED it proves nothing about this artifact's state
        val st = when (base.state) {
            QualificationState.RUNTIME_LOADED -> QualificationState.INFERENCE_TESTED
            else -> base.state
        }
        return base.copy(state = st, testedCapabilities = if (capability != null && st >= QualificationState.INFERENCE_TESTED) base.testedCapabilities + capability else base.testedCapabilities)
    }

    fun onInterruptedLoad(p: ModelProfile, nowMs: Long): ModelProfile =
        p.copy(interruptedLoads = p.interruptedLoads + 1, lastAttemptAt = nowMs, state = QualificationState.FAILED, lastError = "load_interrupted_process_ended")

    private fun qualificationStillValid(p: ModelProfile): Boolean {
        val q = p.qualification ?: return false
        return p.sha256 != null && q.sha256 == p.sha256 && q.runtimeVersion == p.runtimeVersion && q.backend == p.backend && q.deviceKey == p.deviceKey
    }

    /**
     * DEVICE_QUALIFIED is granted only by the M4 protocol, for EXACTLY this artifact hash + runtime version + backend + device, and only on top of a
     * proven inference. Anything else is refused (the profile is returned unchanged).
     */
    fun qualify(p: ModelProfile, e: QualificationEvidence): ModelProfile {
        val sameDevice = p.deviceKey != null && p.deviceKey == e.deviceKey
        val ok = p.sha256 != null && e.sha256 == p.sha256 && e.runtimeVersion == p.runtimeVersion && e.backend == p.backend && sameDevice && p.state == QualificationState.INFERENCE_TESTED
        return if (ok) p.copy(qualification = e, state = QualificationState.DEVICE_QUALIFIED) else p
    }

    /** A new runtime version or a different file invalidates a device qualification and any lower proof that depended on them. */
    fun revalidate(p: ModelProfile, currentRuntimeVersion: String, currentSha: String?): ModelProfile {
        if (p.runtimeVersion != currentRuntimeVersion) return p.copy(runtimeVersion = currentRuntimeVersion, qualification = null, state = if (p.state == QualificationState.FAILED) p.state else QualificationState.STATICALLY_COMPATIBLE)
        if (p.sha256 != null && currentSha != null && p.sha256 != currentSha) return ModelProfile(id = idOf(p.name, p.sizeBytes, currentSha), name = p.name, sizeBytes = p.sizeBytes, sha256 = currentSha, format = p.format, runtimeVersion = p.runtimeVersion)
        return p
    }
}

/** The registry of profiles: a small map persisted as JSON by the app (atomic file in app-private storage). Pure codec here. */
class ModelProfileRegistry(initial: Collection<ModelProfile> = emptyList()) {
    private val byId = LinkedHashMap<String, ModelProfile>().also { m -> initial.forEach { m[it.id] = it } }
    @Synchronized fun all(): List<ModelProfile> = byId.values.toList()
    @Synchronized fun get(id: String): ModelProfile? = byId[id]
    @Synchronized fun put(p: ModelProfile) { byId[p.id] = p }
    @Synchronized fun update(id: String, f: (ModelProfile) -> ModelProfile): ModelProfile? = byId[id]?.let(f)?.also { byId[id] = it }
    /** Rekeys a profile whose hash became known (id changes from name:size to sha:…). */
    @Synchronized fun rekey(oldId: String, p: ModelProfile) { byId.remove(oldId); byId[p.id] = p }
    @Synchronized fun findByNameSize(name: String, size: Long): ModelProfile? = byId.values.firstOrNull { it.name == name && it.sizeBytes == size }
    @Synchronized fun remove(id: String) { byId.remove(id) } // a profile only; never the model file

    companion object {
        const val VERSION = 1
        private val json = Json { prettyPrint = false }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.lng(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.set(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()

        fun encode(r: ModelProfileRegistry): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("v", VERSION)
            put("profiles", JsonArray(r.all().map { p ->
                buildJsonObject {
                    put("id", p.id); put("name", p.name); put("size", p.sizeBytes); p.sha256?.let { put("sha", it) }
                    put("format", p.format.name); put("runtime", p.runtime); put("rv", p.runtimeVersion); put("backend", p.backend)
                    p.artifactWindow?.let { put("aw", it) }; p.configuredWindow?.let { put("cw", it) }; p.lastSlot?.let { put("slot", it) }
                    put("decl", JsonArray(p.declaredCapabilities.map(::JsonPrimitive))); put("tested", JsonArray(p.testedCapabilities.map(::JsonPrimitive)))
                    p.deviceKey?.let { put("dev", it) }; p.lastAttemptAt?.let { put("at", it) }; p.lastLoadMs?.let { put("loadMs", it) }
                    p.pssBeforeBytes?.let { put("pss0", it) }; p.pssAfterBytes?.let { put("pss1", it) }; p.lastError?.let { put("err", it) }
                    p.lastInferenceAt?.let { put("iat", it) }; p.lastInferenceOk?.let { put("iok", it) }; p.lastInferenceMs?.let { put("ims", it) }
                    put("interrupted", p.interruptedLoads); put("state", p.state.name)
                    p.qualification?.let { q -> put("q", buildJsonObject { put("dev", q.deviceKey); put("sha", q.sha256); put("rt", q.runtime); put("rv", q.runtimeVersion); put("be", q.backend); put("ref", q.protocolRef) }) }
                }
            }))
        })

        /** Tolerant decode: an unreadable entry is skipped (and counted by the caller through the size), never turned into a stronger state. */
        fun decode(text: String): ModelProfileRegistry {
            val out = ModelProfileRegistry()
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return out
            for (el: JsonElement in (root["profiles"] as? JsonArray).orEmpty()) {
                val o = el as? JsonObject ?: continue
                val id = o.str("id") ?: continue; val name = o.str("name") ?: continue
                val state = runCatching { QualificationState.valueOf(o.str("state") ?: "UNTESTED") }.getOrDefault(QualificationState.UNTESTED)
                val q = (o["q"] as? JsonObject)?.let { qo ->
                    val sha = qo.str("sha"); val dev = qo.str("dev"); val rt = qo.str("rt"); val rv = qo.str("rv"); val be = qo.str("be")
                    if (sha == null || dev == null || rt == null || rv == null || be == null) null else QualificationEvidence(dev, sha, rt, rv, be, qo.str("ref").orEmpty())
                }
                // a DEVICE_QUALIFIED row without complete evidence is demoted, never trusted
                val safeState = if (state == QualificationState.DEVICE_QUALIFIED && q == null) QualificationState.INFERENCE_TESTED else state
                out.put(
                    ModelProfile(
                        id = id, name = name, sizeBytes = o.lng("size") ?: 0L, sha256 = o.str("sha"),
                        format = runCatching { ArtifactFormat.valueOf(o.str("format") ?: "UNKNOWN") }.getOrDefault(ArtifactFormat.UNKNOWN),
                        runtime = o.str("runtime") ?: "litertlm", runtimeVersion = o.str("rv").orEmpty(), backend = o.str("backend") ?: "CPU",
                        artifactWindow = o.lng("aw")?.toInt(), configuredWindow = o.lng("cw")?.toInt(), lastSlot = o.str("slot"),
                        declaredCapabilities = o.set("decl"), testedCapabilities = o.set("tested"), deviceKey = o.str("dev"),
                        lastAttemptAt = o.lng("at"), lastLoadMs = o.lng("loadMs"), pssBeforeBytes = o.lng("pss0"), pssAfterBytes = o.lng("pss1"),
                        lastError = o.str("err"), lastInferenceAt = o.lng("iat"), lastInferenceOk = (o["iok"] as? JsonPrimitive)?.booleanOrNull,
                        lastInferenceMs = o.lng("ims"), interruptedLoads = o.lng("interrupted")?.toInt() ?: 0, qualification = q, state = safeState,
                    ),
                )
            }
            return out
        }
    }
}
