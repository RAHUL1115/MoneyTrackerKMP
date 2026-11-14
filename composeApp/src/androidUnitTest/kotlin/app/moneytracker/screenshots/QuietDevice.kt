package app.moneytracker.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.moneytracker.core.AppInfo
import app.moneytracker.core.BackgroundJobs
import app.moneytracker.core.BackgroundState
import app.moneytracker.core.BiometricStatus
import app.moneytracker.core.Languages
import app.moneytracker.core.Location
import app.moneytracker.core.MessageCapture
import app.moneytracker.core.Network
import app.moneytracker.core.Photos
import app.moneytracker.core.PinSecurity
import app.moneytracker.core.Platform
import app.moneytracker.core.SmartModels
import app.moneytracker.core.SpeechListener
import app.moneytracker.core.SpeechSession
import app.moneytracker.core.Voice
import app.moneytracker.core.WebDashboardControl
import app.moneytracker.core.WebDashboardState
import app.moneytracker.data.BackupFolder
import app.moneytracker.data.BackupStatus
import app.moneytracker.data.Backups
import app.moneytracker.data.FileTransfer
import app.moneytracker.data.LocalBackup
import app.moneytracker.data.PhotoArchive
import app.moneytracker.data.SavedBackups
import app.moneytracker.data.SpreadsheetImport
import app.moneytracker.data.moneymanager.TabularImport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A phone that answers every capability question the same way and does nothing when asked to act, so screens draw as
 * on a typical device: English, voice available, SMS permission granted, no fingerprint, no network.
 */
private object QuietPhone : PinSecurity, Photos, Voice, Location, BackgroundJobs, MessageCapture, WebDashboardControl,
    Languages, AppInfo, Network, SmartModels {
    override fun hashPin(pin: String) = pin
    override fun checkPin(pin: String, stored: String) = pin == stored
    override fun portablePinHash(stored: String): String? = null
    override val biometricStatus: StateFlow<BiometricStatus> = MutableStateFlow(BiometricStatus.UNSUPPORTED)
    override fun openBiometricEnrollment() = Unit
    override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) = Unit

    override fun pickPhoto(onPicked: (fileName: String) -> Unit) = Unit
    override fun takePhoto(onTaken: (fileName: String) -> Unit) = Unit
    override fun deletePhotoFile(fileName: String) = Unit
    @Composable override fun PhotoImage(fileName: String, modifier: Modifier) = Box(modifier)
    @Composable override fun PhotoFull(fileName: String, modifier: Modifier) = Box(modifier)

    override val voiceAvailable = true
    override fun listen(onText: (String) -> Unit) = Unit
    override val speechAvailable = true
    override fun startSpeech(listener: SpeechListener): SpeechSession? = null

    override fun currentLocation(onResult: (lat: Double, lng: Double) -> Unit, onUnavailable: () -> Unit) = onUnavailable()
    override fun openMap(lat: Double, lng: Double) = Unit

    override fun setReminder(enabled: Boolean, hour: Int, minute: Int, onResult: (Boolean) -> Unit) = onResult(enabled)
    override fun setQuickAddNotification(enabled: Boolean, onResult: (Boolean) -> Unit) = onResult(enabled)
    override fun backgroundState() = BackgroundState(maker = "google", restricted = false)
    override fun openBackgroundSettings(autostart: Boolean) = false

    override fun setMessageCapture(enabled: Boolean, onResult: (Boolean) -> Unit) = onResult(enabled)
    override fun importInboxMessages(days: Int, onResult: (Int?) -> Unit) = onResult(0)
    override fun messagePermissionGranted() = true

    override val state: StateFlow<WebDashboardState> = MutableStateFlow(WebDashboardState(wifi = true))
    override fun refreshWebDashboard() = Unit
    override fun startWebDashboard(onResult: (Boolean) -> Unit) = onResult(false)
    override fun stopWebDashboard() = Unit
    override fun disconnectWebComputer(id: String) = Unit
    override fun setWebPassword(password: String?) = Unit
    override fun openWifiSettings() = Unit

    override fun systemLanguage() = "en-US"
    override fun translations(tag: String): Map<String, String> = emptyMap()

    override val appVersion = "1.0.0"
    override fun licenseText(id: String): String? = null
    override fun openUrl(url: String) = Unit
    override fun restartApp() = Unit

    override suspend fun httpGet(url: String): String? = null

    override fun readSmartModel(name: String): ByteArray? = null
}

/** Backups with nothing saved yet, no folder chosen and no import waiting; every action does nothing. */
private object QuietBackups : BackupStatus, SavedBackups, BackupFolder, FileTransfer, SpreadsheetImport, PhotoArchive {
    override val message: StateFlow<String?> = MutableStateFlow(null)
    override fun clearStatus() = Unit

    override val localBackups: StateFlow<List<LocalBackup>> = MutableStateFlow(emptyList())
    override fun backupNow() = Unit
    override fun restoreLocal(name: String) = Unit
    override fun deleteLocal(name: String) = Unit
    override fun reset(everything: Boolean) = Unit

    override val backupFolder: StateFlow<String?> = MutableStateFlow(null)
    override val backupFolderProblem: StateFlow<Boolean> = MutableStateFlow(false)
    override fun pickBackupFolder() = Unit
    override fun clearBackupFolder() = Unit

    override fun exportToFile() = Unit
    override fun shareBackup() = Unit
    override fun importFromFile() = Unit
    override fun exportMoneyManager() = Unit
    override fun exportCsv() = Unit
    override fun exportExcel() = Unit

    override fun importSpreadsheet() = Unit
    override val pendingImport: StateFlow<TabularImport.Parsed?> = MutableStateFlow(null)
    override fun confirmImport(keep: Set<Int>) = Unit
    override fun cancelImport() = Unit

    override fun exportPhotos() = Unit
    override fun importPhotos() = Unit
}

val quietPlatform = Platform(
    pinSecurity = QuietPhone, photos = QuietPhone, voice = QuietPhone, location = QuietPhone,
    backgroundJobs = QuietPhone, messageCapture = QuietPhone, webDashboard = QuietPhone, languages = QuietPhone,
    appInfo = QuietPhone, network = QuietPhone, smartModels = QuietPhone,
)

val quietBackups = Backups(
    status = QuietBackups, saved = QuietBackups, folder = QuietBackups,
    files = QuietBackups, spreadsheet = QuietBackups, photos = QuietBackups,
)
