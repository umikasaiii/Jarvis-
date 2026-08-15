package com.simone.jarvismobile.automation.rule

import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the vehicle was left (§16, phase 8) — a single current value, not a
 * history, so nothing accumulates a trail of where the car has been.
 *
 * Storage is the existing `parking_location` Room table. Capturing the position
 * is the caller's job (a manual save from the UI, or the SAVE_PARKING_LOCATION
 * action); this class only persists and reads it.
 */
@Singleton
class ParkingRepository @Inject constructor(
    private val dao: ParkingLocationDao,
) {
    fun observe(): Flow<ParkingLocationEntity?> = dao.observe()

    suspend fun current(): ParkingLocationEntity? = dao.current()

    suspend fun save(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        label: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        dao.save(
            ParkingLocationEntity(
                id = 1,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                savedAt = now.toString(),
                label = label,
            ),
        )
    }

    suspend fun clear() = dao.clear()
}
