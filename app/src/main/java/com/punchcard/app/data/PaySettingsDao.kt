package com.punchcard.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PaySettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: PaySettings)

    @Query("SELECT * FROM pay_settings ORDER BY effectiveDate DESC LIMIT 1")
    fun observeLatest(): Flow<PaySettings?>

    @Query("SELECT * FROM pay_settings ORDER BY effectiveDate DESC LIMIT 1")
    suspend fun getLatest(): PaySettings?

    // Most recent settings row whose effectiveDate <= the given date.
    @Query("SELECT * FROM pay_settings WHERE effectiveDate <= :date ORDER BY effectiveDate DESC LIMIT 1")
    suspend fun getForDateOrBefore(date: String): PaySettings?

    // Fallback used when `date` predates every saved settings row, so
    // old back-logged hours still get an estimate rather than none.
    @Query("SELECT * FROM pay_settings ORDER BY effectiveDate ASC LIMIT 1")
    suspend fun getEarliest(): PaySettings?
}
