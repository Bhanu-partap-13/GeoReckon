package com.sleepingheads.sihpro.data.model

import com.google.gson.annotations.SerializedName

/**
 * Demo package format containing deterministic offline trip data and ground truth metadata.
 */
data class DemoPackage(
    @SerializedName("metadata") val metadata: TripMetadata,
    @SerializedName("events") val events: List<DemoEvent>? = null,
    @SerializedName("buildings") val buildings: List<BuildingModel>? = null,
    @SerializedName("samples") val samples: List<DemoSample>
)

data class TripMetadata(
    @SerializedName("tripId") val tripId: String,
    @SerializedName("title") val title: String,
    @SerializedName("sampleRateHz") val sampleRateHz: Int,
    @SerializedName("durationSec") val durationSec: Double,
    @SerializedName("sampleCount") val sampleCount: Int,
    @SerializedName("outageStartSec") val outageStartSec: Double,
    @SerializedName("outageEndSec") val outageEndSec: Double,
    @SerializedName("outageDurationSec") val outageDurationSec: Double,
    @SerializedName("outageDistanceM") val outageDistanceM: Double,
    @SerializedName("endpointErrorM") val endpointErrorM: Double,
    @SerializedName("rmsErrorM") val rmsErrorM: Double,
    @SerializedName("maxErrorM") val maxErrorM: Double,
    @SerializedName("driftPercent") val driftPercent: Double,
    @SerializedName("isroTargetPercent") val isroTargetPercent: Double,
    @SerializedName("isroPassed") val isroPassed: Boolean,
    @SerializedName("originLat") val originLat: Double,
    @SerializedName("originLon") val originLon: Double,
    @SerializedName("modelVersion") val modelVersion: String,
    @SerializedName("dataType") val dataType: String
)

data class DemoEvent(
    @SerializedName("timestampMs") val timestampMs: Long,
    @SerializedName("type") val type: String,
    @SerializedName("badge") val badge: String,
    @SerializedName("text") val text: String
)

data class BuildingModel(
    @SerializedName("x") val x: Double,
    @SerializedName("y") val y: Double,
    @SerializedName("w") val w: Double,
    @SerializedName("l") val l: Double,
    @SerializedName("h") val h: Double,
    @SerializedName("heading") val heading: Double
)

data class DemoSample(
    @SerializedName("timestampMs") val timestampMs: Long,
    @SerializedName("trueLat") val trueLat: Double,
    @SerializedName("trueLon") val trueLon: Double,
    @SerializedName("trueX") val trueX: Double = 0.0,
    @SerializedName("trueY") val trueY: Double = 0.0,
    @SerializedName("gpsLat") val gpsLat: Double?,
    @SerializedName("gpsLon") val gpsLon: Double?,
    @SerializedName("gnssAvailable") val gnssAvailable: Boolean,
    @SerializedName("estX") val estX: Double,
    @SerializedName("estY") val estY: Double,
    @SerializedName("estLat") val estLat: Double,
    @SerializedName("estLon") val estLon: Double,
    @SerializedName("naiveX") val naiveX: Double = 0.0,
    @SerializedName("naiveY") val naiveY: Double = 0.0,
    @SerializedName("naiveErrorM") val naiveErrorM: Double = 0.0,
    @SerializedName("noAiX") val noAiX: Double = 0.0,
    @SerializedName("noAiY") val noAiY: Double = 0.0,
    @SerializedName("noAiErrorM") val noAiErrorM: Double = 0.0,
    @SerializedName("noNhcX") val noNhcX: Double = 0.0,
    @SerializedName("noNhcY") val noNhcY: Double = 0.0,
    @SerializedName("noNhcErrorM") val noNhcErrorM: Double = 0.0,
    @SerializedName("noMapX") val noMapX: Double = 0.0,
    @SerializedName("noMapY") val noMapY: Double = 0.0,
    @SerializedName("noMapErrorM") val noMapErrorM: Double = 0.0,
    @SerializedName("speedMps") val speedMps: Double,
    @SerializedName("speedKmh") val speedKmh: Double,
    @SerializedName("headingDeg") val headingDeg: Double,
    @SerializedName("aiSpeedMps") val aiSpeedMps: Double,
    @SerializedName("aiUncertainty") val aiUncertainty: Double,
    @SerializedName("mode") val mode: String,
    @SerializedName("health") val health: String,
    @SerializedName("uncertaintyM") val uncertaintyM: Double,
    @SerializedName("alongUncertM") val alongUncertM: Double = 1.8,
    @SerializedName("crossUncertM") val crossUncertM: Double = 1.4,
    @SerializedName("headingUncertDeg") val headingUncertDeg: Double = 1.2,
    @SerializedName("axisRatio") val axisRatio: Double = 1.2,
    @SerializedName("errorM") val errorM: Double,
    @SerializedName("accelX") val accelX: Double,
    @SerializedName("accelY") val accelY: Double,
    @SerializedName("accelZ") val accelZ: Double,
    @SerializedName("gyroX") val gyroX: Double,
    @SerializedName("gyroY") val gyroY: Double,
    @SerializedName("gyroZ") val gyroZ: Double
)
