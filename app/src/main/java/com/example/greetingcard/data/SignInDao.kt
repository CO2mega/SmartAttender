package com.example.greetingcard.data
import androidx.room.*

@Dao
interface SignInDao {
    @Query("SELECT * FROM sign_in_records WHERE faceId = :faceId AND nfcId = :nfcId LIMIT 1")
    suspend fun getRecord(faceId: Long, nfcId: String): SignInRecord?

    @Query("SELECT * FROM sign_in_records")
    suspend fun getAllRecords(): List<SignInRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: SignInRecord): Long

    @Update
    suspend fun updateRecord(record: SignInRecord)

    @Delete
    suspend fun deleteRecord(record: SignInRecord)
}

