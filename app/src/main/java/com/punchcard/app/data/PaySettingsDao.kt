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

    @Query("SELECT * FROM pay_settings WHERE id = 0 LIMIT 1")
    fun observe(): Flow<PaySettings?>

    @Query("SELECT * FROM pay_settings WHERE id = 0 LIMIT 1")
    suspend fun get(): PaySettings?
}
