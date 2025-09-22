package com.example.greetingcard.viewmodel

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.greetingcard.data.AppDatabase
import com.example.greetingcard.data.FaceEntity
import com.example.greetingcard.data.SignInRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder

// UI state for camera permission and rationale
data class CameraUiState(
    val cameraPermissionGranted: Boolean = false,
    val showPermissionRationale: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "greeting_card_db"
    ).build()
    val faceDao = db.faceDao()
    val signInDao = db.signInDao()

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Expose faces and records as state flows for UI
    private val _faces = MutableStateFlow<List<FaceEntity>>(emptyList())
    val faces: StateFlow<List<FaceEntity>> = _faces.asStateFlow()

    private val _records = MutableStateFlow<List<SignInRecord>>(emptyList())
    val records: StateFlow<List<SignInRecord>> = _records.asStateFlow()

    init {
        // Load initial data
        viewModelScope.launch {
            _faces.value = faceDao.getAllFaces()
            _records.value = signInDao.getAllRecords()
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _faces.value = faceDao.getAllFaces()
            _records.value = signInDao.getAllRecords()
        }
    }

    fun addOrUpdateFace(face: FaceEntity, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (face.id == 0L) {
                faceDao.insertFace(face)
            } else {
                faceDao.updateFace(face)
            }
            _faces.value = faceDao.getAllFaces()
            onComplete?.invoke()
        }
    }

    fun deleteFace(face: FaceEntity, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            faceDao.deleteFace(face)
            _faces.value = faceDao.getAllFaces()
            onComplete?.invoke()
        }
    }

    fun exportSignInCsv(context: Context): File? {
        // Synchronous helper - writes CSV to Documents and returns File
        return try {
            val recordsList = runBlockingGetRecords()
            val sb = StringBuilder()
            sb.append("faceId,nfcId,timestamp,isSigned\n")
            for (r in recordsList) {
                sb.append("${r.faceId},${r.nfcId},${r.timestamp},${r.isSigned}\n")
            }
            val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val outFile = File(documentsDir, "sign_in_export.csv")
            FileOutputStream(outFile).use { it.write(sb.toString().toByteArray()) }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun runBlockingGetRecords(): List<SignInRecord> {
        // Helper to synchronously get records (used by exportSignInCsv)
        var list: List<SignInRecord> = emptyList()
        val job = viewModelScope.launch {
            list = signInDao.getAllRecords()
        }
        runBlockingJoin(job)
        return list
    }

    private fun runBlockingJoin(job: kotlinx.coroutines.Job) {
        // Wait for the job to complete
        while (!job.isCompleted) {
            Thread.sleep(10)
        }
    }

    fun onCameraPermissionGranted() {
        _uiState.update { it.copy(cameraPermissionGranted = true, showPermissionRationale = false) }
    }

    fun onCameraPermissionDenied(showRationale: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = false, showPermissionRationale = showRationale) }
    }

    fun matchFaceAndNfc(faceFeature: String, nfcId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val face = faceDao.getFaceByNfcId(nfcId)
            val matched = face != null && face.faceFeature == faceFeature
            if (matched) {
                // face is non-null here because matched == true
                signInDao.insertRecord(
                    SignInRecord(
                        faceId = face.id,
                        nfcId = nfcId,
                        timestamp = System.currentTimeMillis(),
                        isSigned = true
                    )
                )
            }
            onResult(matched)
        }
    }

    // Admin unlock flow: used when biometric succeeds to signal Compose UI
    private val _adminUnlocked = MutableStateFlow(false)
    val adminUnlocked: StateFlow<Boolean> = _adminUnlocked.asStateFlow()

    fun setAdminUnlocked(value: Boolean) {
        _adminUnlocked.value = value
    }
}
