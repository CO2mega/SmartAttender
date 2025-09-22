package com.example.greetingcard.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sign_in_records")
data class SignInRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val faceId: Long,
    val nfcId: String,
    val timestamp: Long,
    val isSigned: Boolean = true
)

