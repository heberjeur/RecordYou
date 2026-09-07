package com.bnyro.recorder.ui.screens

import android.net.Uri
import android.os.Build
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.bnyro.recorder.util.LanguageHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.AudioFormat
import com.bnyro.recorder.ui.common.CheckboxPref
import com.bnyro.recorder.ui.common.ChipSelector
import com.bnyro.recorder.ui.common.ClickableIcon
import com.bnyro.recorder.ui.common.CustomNumInputPref
import com.bnyro.recorder.ui.common.SelectionDialog
import com.bnyro.recorder.ui.components.NamingPatternPref
import com.bnyro.recorder.ui.dialogs.AboutDialog
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.util.PickFolderContract
import com.bnyro.recorder.util.Preferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateUp: (() -> Unit)? = null) {
    val themeModel: ThemeModel = viewModel(LocalContext.current as ComponentActivity)
    var audioFormat by remember {
        mutableStateOf(AudioFormat.getCurrent())
    }
    var audioChannels by remember {
        mutableStateOf(
            AudioChannels.fromInt(
                Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value)
            )
        )
    }
    var audioDeviceSource by remember {
        mutableStateOf(
            AudioDeviceSource.fromInt(
                Preferences.prefs.getInt(
                    Preferences.audioDeviceSourceKey,
                    AudioDeviceSource.DEFAULT.value
                )
            )
        )
    }
    var screenAudioSource by remember {
        mutableStateOf(
            AudioSource.fromInt(Preferences.prefs.getInt(Preferences.audioSourceKey, 0))
        )
    }
    var videoEncoder by remember {
        mutableStateOf(VideoFormat.getCurrent())
    }
    var countdownSeconds by remember {
        mutableStateOf(Preferences.prefs.getInt(Preferences.countdownSecondsKey, 0))
    }

    var showLanguagePref by remember {
        mutableStateOf(false)
    }
    var audioTargetFolder by remember {
        mutableStateOf(Preferences.prefs.getString(Preferences.audioTargetFolderKey, ""))
    }
    var videoTargetFolder by remember {
        mutableStateOf(Preferences.prefs.getString(Preferences.videoTargetFolderKey, ""))
    }

    val audioDirectoryPicker = rememberLauncherForActivityResult(PickFolderContract()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        audioTargetFolder = uri.toString()
        Preferences.edit { putString(Preferences.audioTargetFolderKey, uri.toString()) }
    }

    val videoDirectoryPicker = rememberLauncherForActivityResult(PickFolderContract()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        videoTargetFolder = uri.toString()
        Preferences.edit { putString(Preferences.videoTargetFolderKey, uri.toString()) }
    }
    var showAbout by remember {
        mutableStateOf(false)
    }
    var showThemePref by remember {
        mutableStateOf(false)
    }

    val view = LocalView.current

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = {
                onNavigateUp?.let {
                    ClickableIcon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    ) {
                        it.invoke()
                    }
                }
            },
            actions = {
                ClickableIcon(
                    imageVector = Icons.Default.Language,
                    contentDescription = stringResource(R.string.language)
                ) {
                    showLanguagePref = true
                }
                ClickableIcon(
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = stringResource(R.string.theme)
                ) {
                    showThemePref = true
                }
                ClickableIcon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.about)
                ) {
                    showAbout = true
                }
            }
        )
    }) { paddingValues ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = stringResource(R.string.directory),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        val lastDir = audioTargetFolder.takeIf { !it.isNullOrBlank() }
                            ?: Preferences.prefs.getString(Preferences.targetFolderKey, "").takeIf { !it.isNullOrBlank() }
                        audioDirectoryPicker.launch(lastDir?.let { Uri.parse(it) })
                    }
                ) {
                    Text(
                        text = stringResource(R.string.audio_directory),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        val lastDir = videoTargetFolder.takeIf { !it.isNullOrBlank() }
                            ?: Preferences.prefs.getString(Preferences.targetFolderKey, "").takeIf { !it.isNullOrBlank() }
                        videoDirectoryPicker.launch(lastDir?.let { Uri.parse(it) })
                    }
                ) {
                    Text(
                        text = stringResource(R.string.video_directory),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    showLanguagePref = true
                }
            ) {
                val currentCode = Preferences.prefs.getString(Preferences.languageKey, "") ?: ""
                val currentOption = LanguageHelper.languages.find { it.code == currentCode }
                val currentName = currentOption?.name ?: stringResource(R.string.system_default)
                val currentIcon = currentOption?.icon ?: "🌐"
                Text(
                    text = "$currentIcon ${stringResource(R.string.language)}: $currentName",
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            ChipSelector(
                title = stringResource(R.string.audio_format),
                entries = AudioFormat.formats.map { it.name },
                values = AudioFormat.formats.map { it.format },
                selections = listOf(audioFormat.format)
            ) { index, newValue ->
                if (newValue) {
                    audioFormat = AudioFormat.formats[index]
                    Preferences.edit { putString(Preferences.audioFormatKey, audioFormat.name) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    CustomNumInputPref(
                        modifier = Modifier.padding(end = 10.dp),
                        key = Preferences.audioSampleRateKey,
                        title = stringResource(R.string.sample_rate),
                        defValue = 44_100
                    )
                }
                item {
                    CustomNumInputPref(
                        modifier = Modifier.padding(end = 10.dp),
                        key = Preferences.audioBitrateKey,
                        title = stringResource(R.string.bitrate),
                        defValue = 192_000
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val audioDeviceSourceValues = AudioDeviceSource.values().map { it.value }
            ChipSelector(
                entries = listOf(
                    R.string.default_audio,
                    R.string.microphone,
                    R.string.camcorder,
                    R.string.unprocessed,
                    R.string.internal_audio
                ).map {
                    stringResource(it)
                },
                values = audioDeviceSourceValues,
                selections = listOf(audioDeviceSource.value)
            ) { index, newValue ->
                if (newValue) {
                    audioDeviceSource = AudioDeviceSource.fromInt(
                        audioDeviceSourceValues[index]
                    )
                    Preferences.edit {
                        putInt(Preferences.audioDeviceSourceKey, audioDeviceSourceValues[index])
                    }
                }
            }
            val audioChannelsValues = AudioChannels.values().map { it.value }
            ChipSelector(
                entries = listOf(R.string.mono, R.string.stereo).map {
                    stringResource(it)
                },
                values = audioChannelsValues,
                selections = listOf(audioChannels.value)
            ) { index, newValue ->
                if (newValue) {
                    audioChannels = AudioChannels.fromInt(audioChannelsValues[index])
                    Preferences.edit {
                        putInt(Preferences.audioChannelsKey, audioChannelsValues[index])
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val audioValues = AudioSource.values().map { it.value }
            ChipSelector(
                title = stringResource(R.string.screen_recorder),
                entries = listOf(R.string.no_audio, R.string.microphone).map {
                    stringResource(it)
                },
                values = audioValues,
                selections = listOf(screenAudioSource.value)
            ) { index, newValue ->
                if (newValue) {
                    screenAudioSource = AudioSource.fromInt(audioValues[index])
                    Preferences.edit { putInt(Preferences.audioSourceKey, audioValues[index]) }
                }
            }
            ChipSelector(
                entries = VideoFormat.codecs.map { it.name },
                values = VideoFormat.codecs.map { it.codec },
                selections = listOf(videoEncoder.codec)
            ) { index, newValue ->
                if (newValue) {
                    videoEncoder = VideoFormat.codecs[index]
                    Preferences.edit { putInt(Preferences.videoCodecKey, videoEncoder.codec) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            CustomNumInputPref(
                modifier = Modifier.fillMaxWidth(),
                key = Preferences.videoBitrateKey,
                title = stringResource(R.string.bitrate),
                defValue = 1_200_000
            )
            Spacer(modifier = Modifier.height(10.dp))
            val countdownValues = listOf(0, 3, 5, 10)
            ChipSelector(
                title = stringResource(R.string.countdown_timer),
                entries = listOf(
                    stringResource(R.string.off),
                    "3s",
                    "5s",
                    "10s"
                ),
                values = countdownValues,
                selections = listOf(countdownSeconds)
            ) { index, newValue ->
                if (newValue) {
                    countdownSeconds = countdownValues[index]
                    Preferences.edit { putInt(Preferences.countdownSecondsKey, countdownSeconds) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                CheckboxPref(
                    prefKey = Preferences.losslessRecorderKey,
                    title = stringResource(R.string.lossless_audio),
                    summary = stringResource(R.string.lossless_audio_desc)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CheckboxPref(
                    prefKey = Preferences.showOverlayAnnotationToolKey,
                    title = stringResource(R.string.screen_recorder_annotation),
                    summary = stringResource(R.string.screen_recorder_annotation_desc)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            CheckboxPref(
                prefKey = Preferences.showVisualizerTimestamps,
                title = stringResource(R.string.audio_visualizer_timestamps),
                summary = stringResource(R.string.audio_visualizer_timestamps_description)
            )
            Spacer(modifier = Modifier.height(10.dp))
            val currentContext = LocalContext.current
            val playerModel: PlayerModel = run {
                val activity = currentContext as? ComponentActivity
                if (activity != null) {
                    viewModel(viewModelStoreOwner = activity, factory = PlayerModel.Factory)
                } else {
                    viewModel(factory = PlayerModel.Factory)
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    playerModel.resetAndReloadWaveforms()
                    android.widget.Toast.makeText(
                        currentContext,
                        currentContext.getString(R.string.reset_waveform_cache_done),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Text(
                    text = "📊 ${stringResource(R.string.reset_waveform_cache)}",
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            NamingPatternPref()
        }
    }

    if (showThemePref) {
        val themeIcons: List<@Composable () -> Unit> = listOf(
            { Text("🌓", fontSize = 24.sp) },
            { Text("☀️", fontSize = 24.sp) },
            { Text("🌙", fontSize = 24.sp) },
            {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E2E2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_black_sun),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                }
            }
        )
        SelectionDialog(
            onDismissRequest = { showThemePref = false },
            title = stringResource(R.string.theme),
            entries = listOf(
                R.string.system,
                R.string.light,
                R.string.dark,
                R.string.amoled_dark
            ).map {
                stringResource(it)
            },
            iconDrawables = themeIcons
        ) {
            themeModel.themeMode = ThemeMode.values()[it]
            Preferences.edit { putString(Preferences.themeModeKey, ThemeMode.values()[it].name) }
        }
    }

    if (showAbout) {
        AboutDialog {
            showAbout = false
        }
    }

    if (showLanguagePref) {
        val values = LanguageHelper.languages.map { it.code }
        val entries = LanguageHelper.languages.map { lang ->
            if (lang.code.isEmpty()) stringResource(R.string.system_default) else lang.name
        }
        val icons = LanguageHelper.languages.map { it.icon }
        val currentContext = LocalContext.current
        SelectionDialog(
            onDismissRequest = { showLanguagePref = false },
            title = stringResource(R.string.language),
            entries = entries,
            icons = icons
        ) { index ->
            val langCode = values[index]
            LanguageHelper.setLanguage(currentContext, langCode)
            showLanguagePref = false
            (currentContext as? ComponentActivity)?.recreate()
        }
    }
}
