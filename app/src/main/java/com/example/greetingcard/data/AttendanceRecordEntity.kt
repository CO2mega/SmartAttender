package com.example.greetingcard.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = FaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE // Defines action on deletion of parent FaceEntity
        )
    ]
)
data class AttendanceRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val userId: Int, // Foreign key referencing FaceEntity.id
    val timestamp: Long, // Time of sign-in, e.g., System.currentTimeMillis()
    val nfcCardData: String, // Data read from the NFC card at the time of sign-in
    // You could add more details, like location if relevant
)
