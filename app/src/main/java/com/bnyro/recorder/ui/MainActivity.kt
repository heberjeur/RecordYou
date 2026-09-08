package com.bnyro.recorder.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.ui.theme.RecordYouTheme
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.bnyro.recorder.App
import com.bnyro.recorder.ui.common.FullscreenDialog
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.screens.TrimmerScreen
import com.bnyro.recorder.ui.views.VideoView
import com.bnyro.recorder.util.LanguageHelper

class MainActivity : ComponentActivity() {
    private var initialRecorder = RecorderType.NONE
    private var exitAfterRecordingStart = false
    private var trimFileName by mutableStateOf<String?>(null)
    private var openRecordingName by mutableStateOf<String?>(null)
    private var viewVideoUri by mutableStateOf<Uri?>(null)
    private var openAudioFile by mutableStateOf<DocumentFile?>(null)
    private lateinit var mProjectionManager: MediaProjectionManager
    private val recorderModel: RecorderModel by viewModels()
    private val playerModel: PlayerModel by viewModels(factoryProducer = { PlayerModel.Factory })
    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeModel: ThemeModel by viewModels()
        launcher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    recorderModel.startVideoRecorder(this, result)
                }
            }
        mProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        processIntent(intent)
        enableEdgeToEdge()

        setContent {
            val isSystemDark = isSystemInDarkTheme()
            val isDark = when (themeModel.themeMode) {
                ThemeMode.SYSTEM -> isSystemDark
                ThemeMode.DARK -> true
                ThemeMode.AMOLED -> true
                ThemeMode.LIGHT -> false
            }
            RecordYouTheme(
                darkTheme = isDark,
                amoledDark = themeModel.themeMode == ThemeMode.AMOLED
            ) {
                val navController = rememberNavController()
                LaunchedEffect(openRecordingName) {
                    val targetName = openRecordingName ?: return@LaunchedEffect
                    val fileRepo = (application as App).fileRepository
                    val file = fileRepo.getOutputDir().findFile(targetName)
                        ?: fileRepo.getAudioOutputDir().findFile(targetName)
                        ?: fileRepo.getVideoOutputDir().findFile(targetName)
                    if (file != null) {
                        val isVideo = file.type?.startsWith("video/") == true ||
                            listOf(".mp4", ".mov", ".avi", ".mkv", ".webm", ".mpg").any {
                                file.name.orEmpty().endsWith(it, ignoreCase = true)
                            }
                        navController.navigateTo(Destination.RecordingPlayer(showVideo = isVideo).route)
                        if (!isVideo) {
                            playerModel.startPlayback(file)
                        }
                    }
                    openRecordingName = null
                }
                LaunchedEffect(openAudioFile) {
                    val file = openAudioFile ?: return@LaunchedEffect
                    navController.navigateTo(Destination.RecordingPlayer(showVideo = false).route)
                    playerModel.startPlayback(file)
                    openAudioFile = null
                }
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        navController = navController,
                        modifier = Modifier,
                        initialRecorder = initialRecorder
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && trimFileName != null) {
                    val fileRepo = (application as App).fileRepository
                    val file = fileRepo.getOutputDir().findFile(trimFileName!!)
                        ?: fileRepo.getAudioOutputDir().findFile(trimFileName!!)
                        ?: fileRepo.getVideoOutputDir().findFile(trimFileName!!)
                    if (file != null) {
                        TrimmerScreen(
                            onDismissRequest = { trimFileName = null },
                            inputFile = file
                        )
                    }
                }
                if (viewVideoUri != null) {
                    val uri = viewVideoUri!!
                    val fileName = runCatching {
                        DocumentFile.fromSingleUri(this, uri)?.name
                    }.getOrNull() ?: uri.lastPathSegment ?: "Video"
                    FullscreenDialog(
                        title = fileName.substringBeforeLast("."),
                        onDismissRequest = { viewVideoUri = null }
                    ) {
                        VideoView(videoUri = uri)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        processIntent(intent)
        super.onNewIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        val action = intent.action
        val data = intent.data
        if (action == Intent.ACTION_VIEW && data != null) {
            val mimeType = intent.type ?: contentResolver.getType(data).orEmpty()
            val path = data.path.orEmpty()
            val isVideo = mimeType.startsWith("video/") ||
                listOf(".mp4", ".mov", ".avi", ".mkv", ".webm", ".mpg").any { path.endsWith(it, ignoreCase = true) }
            if (isVideo) {
                viewVideoUri = data
            } else {
                val docFile = runCatching {
                    if (data.scheme == ContentResolver.SCHEME_CONTENT) {
                        DocumentFile.fromSingleUri(this, data)
                    } else if (data.scheme == "file" && data.path != null) {
                        DocumentFile.fromFile(java.io.File(data.path!!))
                    } else {
                        DocumentFile.fromSingleUri(this, data)
                    }
                }.getOrNull()
                if (docFile != null) {
                    openAudioFile = docFile
                }
            }
        }
        val openName = intent.getStringExtra(EXTRA_OPEN_RECORDING_NAME)
        if (!openName.isNullOrBlank()) {
            openRecordingName = openName
            intent.removeExtra(EXTRA_OPEN_RECORDING_NAME)
        }
        val trimName = intent.getStringExtra(EXTRA_TRIM_FILE_NAME)
        if (!trimName.isNullOrBlank()) {
            trimFileName = trimName
            intent.removeExtra(EXTRA_TRIM_FILE_NAME)
        }
        val initialRecorderType = intent.getStringExtra(EXTRA_ACTION_KEY)?.let {
            RecorderType.valueOf(it)
        } ?: RecorderType.NONE
        initialRecorder = initialRecorderType
        if (initialRecorderType == RecorderType.AUDIO) {
            recorderModel.startAudioRecorder(this)
        } else if (initialRecorderType == RecorderType.VIDEO) {
            if (recorderModel.hasScreenRecordingPermissions(this)) {
                launcher.launch(mProjectionManager.createScreenCaptureIntent())
            }
        }
        intent.removeExtra(EXTRA_ACTION_KEY)
    }

    override fun onPause() {
        super.onPause()
        if (initialRecorder == RecorderType.VIDEO) {
            exitAfterRecordingStart = true
            initialRecorder = RecorderType.NONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (exitAfterRecordingStart) {
            exitAfterRecordingStart = false
            moveTaskToBack(true)
        }
    }

    companion object {
        const val EXTRA_ACTION_KEY = "action"
        const val EXTRA_TRIM_FILE_NAME = "trimFileName"
        const val EXTRA_OPEN_RECORDING_NAME = "openRecordingName"
    }
}
