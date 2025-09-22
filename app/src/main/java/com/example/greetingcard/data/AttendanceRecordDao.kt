package com.example.greetingcard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE) // Or .REPLACE, depending on desired behavior for duplicate records
    suspend fun insertRecord(record: AttendanceRecordEntity): Long

    @Query("SELECT * FROM attendance_records WHERE userId = :userId ORDER BY timestamp DESC")
    fun getRecordsForUser(userId: Int): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<AttendanceRecordEntity>>

    // Query to get records within a specific time range, useful for exporting
    @Query("SELECT * FROM attendance_records WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getRecordsInTimeRange(startTime: Long, endTime: Long): Flow<List<AttendanceRecordEntity>>

    // You might also want a query to get the latest record for a user
    @Query("SELECT * FROM attendance_records WHERE userId = :userId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestRecordForUser(userId: Int): Flow<AttendanceRecordEntity?>

    @Query("DELETE FROM attendance_records")
    suspend fun clearAllRecords() // Example: for administrative purposes
}
