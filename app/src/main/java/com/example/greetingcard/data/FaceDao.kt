package com.example.greetingcard.data
import androidx.room.*

@Dao
interface FaceDao {
    @Query("SELECT * FROM faces WHERE nfcId = :nfcId LIMIT 1")
    suspend fun getFaceByNfcId(nfcId: String): FaceEntity?

    @Query("SELECT * FROM faces WHERE id = :id LIMIT 1")
    suspend fun getFaceById(id: Long): FaceEntity?

    @Query("SELECT * FROM faces")
    suspend fun getAllFaces(): List<FaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    @Update
    suspend fun updateFace(face: FaceEntity)

    @Delete
    suspend fun deleteFace(face: FaceEntity)
}

