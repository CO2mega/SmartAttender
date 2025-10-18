package com.example.greetingcard.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "faces")
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val faceFeature: String, // 人脸特征（可用Base64或特征向量字符串）
    val nfcId: String, // 绑定的NFC卡号
    val faceImagePath: String? = null, // 本地保存的人脸图片路径（用于可视化保存）
    val embedding: String? = null // 未来：人脸embedding的序列化字符串（可选）
)
