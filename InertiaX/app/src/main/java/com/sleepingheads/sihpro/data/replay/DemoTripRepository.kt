package com.sleepingheads.sihpro.data.replay

import android.content.Context
import com.google.gson.Gson
import com.sleepingheads.sihpro.data.model.DemoPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Loads deterministic offline demo trip data from bundled assets.
 */
class DemoTripRepository(private val context: Context) {
    private val gson = Gson()
    private var cachedPackage: DemoPackage? = null

    suspend fun loadDemoTrip(assetPath: String = "demo/demo_trip.json"): Result<DemoPackage> = withContext(Dispatchers.IO) {
        cachedPackage?.let { return@withContext Result.success(it) }
        try {
            context.assets.open(assetPath).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val pkg = gson.fromJson(reader, DemoPackage::class.java)
                    cachedPackage = pkg
                    Result.success(pkg)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
