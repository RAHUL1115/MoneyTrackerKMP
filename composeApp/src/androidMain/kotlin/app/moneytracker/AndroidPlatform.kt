package app.moneytracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import app.moneytracker.ui.isPortablePinHash
import app.moneytracker.ui.PORTABLE_PIN_PREFIX
import java.security.MessageDigest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import app.moneytracker.ui.BiometricStatus
import app.moneytracker.ui.PlatformServices
import app.moneytracker.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Android implementation of the device features used by the shared UI.
 * Must be created in Activity.onCreate (registers activity-result launchers).
 */
class AndroidPlatform(private val activity: FragmentActivity) : PlatformServices {
    private val ctx: Context get() = activity.applicationContext
    private val photoDir get() = Photos.dir(ctx)

    // ───────────────────────── launchers

    private var onPhoto: ((String) -> Unit)? = null
    private var onVoice: ((String) -> Unit)? = null
    private var onPermission: ((Boolean) -> Unit)? = null
    private var pendingCameraFile: File? = null

    // Several receipts at once.
    private val pickLauncher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.mapNotNull { importPhoto(it) }.forEach { onPhoto?.invoke(it) }
    }
    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCameraFile
        if (ok && f != null) importPhoto(Uri.fromFile(f))?.let { onPhoto?.invoke(it) }
        f?.delete()
    }
    private val voiceLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onVoice?.invoke(it) }
    }
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        onPermission?.invoke(r.values.all { it })
    }

    private fun withPermissions(perms: List<String>, then: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then(true) else { onPermission = then; permissionLauncher.launch(missing.toTypedArray()) }
    }

    // ───────────────────────── security

    /** PBKDF2 with a fresh random salt, stored with the hash as "s2:salt:key" so backups carry everything needed to check it. */
    override fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return PORTABLE_PIN_PREFIX + b64(salt) + ":" + b64(derivePin(pin, salt))
    }

    override fun checkPin(pin: String, stored: String): Boolean {
        if (isPortablePinHash(stored)) {
            val parts = stored.removePrefix(PORTABLE_PIN_PREFIX).split(':')
            if (parts.size != 2) return false
            val salt = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull() ?: return false
            val key = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return false
            return MessageDigest.isEqual(derivePin(pin, salt), key)
        }
        // Older hashes used a salt kept only on this install, so they check out here and nowhere else.
        val prefs = ctx.getSharedPreferences("security", Context.MODE_PRIVATE)
        val salt = prefs.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(b64(derivePin(pin, salt)).toByteArray(), stored.toByteArray())
    }

    private fun derivePin(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val biometricState = MutableStateFlow(readBiometricStatus())
    override val biometricStatus: StateFlow<BiometricStatus> = biometricState

    /** Call from Activity.onResume so enrolling in system Settings is picked up immediately. */
    fun refreshBiometricStatus() { biometricState.value = readBiometricStatus() }

    private fun readBiometricStatus(): BiometricStatus =
        when (BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNSUPPORTED
        }

    override fun openBiometricEnrollment() {
        val intent = when {
            Build.VERSION.SDK_INT >= 30 -> Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
            Build.VERSION.SDK_INT >= 28 -> @Suppress("DEPRECATION") Intent(Settings.ACTION_FINGERPRINT_ENROLL)
            else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { activity.startActivity(intent) }.onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
    }

    override fun authenticate(title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                // Cancelling or choosing "Use passcode" is not an error worth showing.
                if (code != BiometricPrompt.ERROR_NEGATIVE_BUTTON && code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_CANCELED) {
                    onError(message.toString())
                }
                refreshBiometricStatus()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(tr("Use your fingerprint or face"))
                .setNegativeButtonText(tr("Use passcode")).setAllowedAuthenticators(AUTHENTICATORS).setConfirmationRequired(false).build(),
        )
    }

    // ───────────────────────── photos

    override fun pickPhoto(onPicked: (String) -> Unit) {
        onPhoto = onPicked
        pickLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun takePhoto(onTaken: (String) -> Unit) {
        onPhoto = onTaken
        val f = File(activity.cacheDir, "camera-${UUID.randomUUID()}.jpg")
        pendingCameraFile = f
        cameraLauncher.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }

    /** Copies into private storage, downscaled to ≤1600 px JPEG so receipts stay small (~150–300 KB). */
    private fun importPhoto(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val name = "${UUID.randomUUID()}.jpg"
        File(photoDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        name
    }.getOrNull()

    override fun deletePhotoFile(fileName: String) { File(photoDir, fileName).delete() }

    @Composable
    override fun PhotoImage(fileName: String, modifier: Modifier) {
        val bitmap = remember(fileName) {
            // Stored photos are ≤1600 px; half size is sharp enough for the full-width preview.
            BitmapFactory.decodeFile(File(photoDir, fileName).path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        }
        if (bitmap != null) Image(bitmap, tr("Receipt photo"), modifier.clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
    }

    @Composable
    override fun PhotoFull(fileName: String, modifier: Modifier) {
        val bitmap = remember(fileName) { BitmapFactory.decodeFile(File(photoDir, fileName).path)?.asImageBitmap() }
        if (bitmap != null) Image(bitmap, tr("Receipt photo"), modifier, contentScale = ContentScale.Fit)
    }

    // ───────────────────────── voice & location

    private val voiceIntent get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, tr("e.g. “spent 250 on lunch”"))

    override val voiceAvailable: Boolean
        get() = voiceIntent.resolveActivity(ctx.packageManager) != null

    override fun listen(onText: (String) -> Unit) {
        onVoice = onText
        runCatching { voiceLauncher.launch(voiceIntent) }
    }

    /**
     * A fresh fix for a new entry. Asks for precise location; Android 12+ lets the user grant only
     * approximate, which still works (network provider). Waits up to [LOCATION_TIMEOUT_MS], then falls back
     * to a recent last-known fix, never an old one.
     */
    @SuppressLint("MissingPermission")
    override fun currentLocation(onResult: (Double, Double) -> Unit, onUnavailable: () -> Unit) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val perms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Ask only when neither is granted: a user who chose "approximate" isn't prompted on every entry.
        val ask: ((Boolean) -> Unit) -> Unit = if (perms.any(::granted)) { then -> then(true) } else { then -> withPermissions(perms, then) }
        ask {
            val precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!precise && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return@ask onUnavailable()
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            fun recent() = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < MAX_FIX_AGE_MS }
                .minByOrNull { it.accuracy }
            // Without precise access the GPS provider is off-limits (SecurityException before Android 12).
            val provider = (if (precise) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER))
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            var done = false
            fun finish(loc: android.location.Location?) {
                if (done) return
                done = true
                val fix = loc ?: recent()
                if (fix != null) onResult(fix.latitude, fix.longitude) else onUnavailable()
            }
            if (provider == null) return@ask finish(null)
            val cancel = CancellationSignal()
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({ cancel.cancel(); finish(null) }, LOCATION_TIMEOUT_MS)
            runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(ctx)) { finish(it) }
            }.onFailure { finish(null) }
        }
    }

    override val appVersion: String
        get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    override fun licenseText(id: String): String? =
        runCatching { ctx.assets.open("licenses/$id.txt").bufferedReader().use { it.readText() } }.getOrNull()

    override fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun restartApp() {
        // A fresh task with a new activity; the process (and the open database) stays.
        MainActivity.skipLockOnce = true
        activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)))
        activity.finish()
    }

    override fun openMap(lat: Double, lng: Double) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) }
    }

    // ───────────────────────── background features

    private fun notificationPermission(then: (Boolean) -> Unit) =
        if (Build.VERSION.SDK_INT >= 33) withPermissions(listOf(Manifest.permission.POST_NOTIFICATIONS), then) else then(true)

    override fun setReminder(enabled: Boolean, hour: Int, minute: Int, onResult: (Boolean) -> Unit) {
        if (!enabled) { Reminders.cancel(ctx); return onResult(false) }
        notificationPermission { ok -> if (ok) Reminders.schedule(ctx, hour, minute); onResult(ok) }
    }

    override fun setQuickAddNotification(enabled: Boolean, onResult: (Boolean) -> Unit) {
        if (!enabled) { Notifications.cancelQuickAdd(ctx); return onResult(false) }
        notificationPermission { ok -> if (ok) Notifications.showQuickAdd(ctx); onResult(ok) }
    }

    override fun setMessageCapture(enabled: Boolean, onResult: (Boolean) -> Unit) {
        fun toggle(on: Boolean) = ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, SmsReceiver::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (!enabled) { toggle(false); return onResult(false) }
        // READ_SMS lets the app catch up on messages that arrived while it couldn't run (same permission group, no extra prompt).
        val perms = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        withPermissions(perms) { ok -> toggle(ok); onResult(ok) }
    }

    override fun importInboxMessages(days: Int, onResult: (Int?) -> Unit) {
        withPermissions(listOf(Manifest.permission.READ_SMS)) { ok ->
            if (!ok) return@withPermissions onResult(null)
            activity.lifecycleScope.launch {
                val n = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Detected.importInbox(ctx, days) }.getOrNull() }
                onResult(n)
            }
        }
    }

    override fun refreshWidgets() = SummaryWidget.updateAll(ctx)

    override fun systemLanguage(): String = android.content.res.Resources.getSystem().configuration.locales[0].toLanguageTag()
    override fun translations(tag: String): Map<String, String> = Translations.load(ctx, tag)
}

/** Class 2 ("weak") includes most face unlock; fine for an app lock that always has a PIN fallback. */
private const val MAX_PICK = 5

private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

object Photos {
    fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
}

private const val LOCATION_TIMEOUT_MS = 10_000L
/** Older last-known fixes are likely somewhere else; better no location than a wrong one. */
private const val MAX_FIX_AGE_MS = 2 * 60_000L
