package com.example.greetingcard.data
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FaceEntity::class, SignInRecord::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun faceDao(): FaceDao
    abstract fun signInDao(): SignInDao
}
