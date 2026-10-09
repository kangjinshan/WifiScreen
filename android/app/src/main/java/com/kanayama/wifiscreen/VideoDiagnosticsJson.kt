package com.kanayama.wifiscreen

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.value(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

fun VideoDiagnosticSnapshot.toJson(): JSONObject = JSONObject()
    .put("elapsedMs", elapsedMs).value("decoderAgeMs", decoderAgeMs).value("lastKeyframeAgeMs", keyframeAgeMs)
    .value("lastIdrAgeMs", idrAgeMs).put("decoderStarts", decoderStarts).put("decoderReleases", decoderReleases)
    .put("lastResetReason", lastResetReason).put("configurationVersion", configurationVersion)
    .put("configurationHash", configurationHash)
    .value("source", source?.let { s -> JSONObject().put("profile", s.profile).put("level", s.level)
        .put("chromaFormat", s.chromaFormat).put("bitDepthLuma", s.bitDepthLuma).put("bitDepthChroma", s.bitDepthChroma)
        .put("frameOnly", s.frameOnly).put("spsId", s.id)
        .value("colorPrimaries", s.color.primaries).value("colorTransfer", s.color.transfer)
        .value("colorMatrix", s.color.matrix).value("fullRange", s.color.fullRange) })
    .value("output", output?.let { f -> JSONObject().value("width", f.width).value("height", f.height)
        .value("colorFormat", f.colorFormat).value("colorStandard", f.colorStandard)
        .value("colorTransfer", f.colorTransfer).value("colorRange", f.colorRange)
        .value("stride", f.stride).value("sliceHeight", f.sliceHeight) })
    .value("lastInputPtsUs", lastInputPtsUs).value("lastOutputPtsUs", lastOutputPtsUs)
    .put("events", JSONArray().also { array -> events.forEach { e -> array.put(JSONObject()
        .put("elapsedMs", e.elapsedMs).put("kind", e.kind).put("detail", e.detail)) } })

fun VideoRepairStatus.toJson(): JSONObject = JSONObject().put("state", state.name).put("message", message)
    .put("attempts", attempts).value("requestedAtMs", requestedAtMs).value("completedAtMs", completedAtMs)
    .put("active", active).put("keyframeRequestMode", "natural_only")
