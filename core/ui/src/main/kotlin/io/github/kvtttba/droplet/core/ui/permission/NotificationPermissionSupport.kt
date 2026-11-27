package io.github.kvtttba.droplet.core.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Checks whether Droplet can currently post local notifications on this Android device
 * (Android 12 system switch + Android 13+ `POST_NOTIFICATIONS` runtime permission).
 */
fun Context.isPostNotificationGranted(): Boolean {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
        return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    return true
}

/**
 * Opens Android's per-app notification settings screen so the user can re-enable blocked notifications.
 */
fun Context.openAppNotificationSettings(): Boolean {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)
}

data class NotificationPermissionController(
    val isGranted: Boolean,
    val requestOrOpenSettings: () -> Unit,
    val openSystemSettings: () -> Unit,
)

/**
 * Lifecycle-aware Compose helper for Android 13+ graceful `POST_NOTIFICATIONS` runtime requests
 * with automatic fallback to system app notification settings and `ON_RESUME` status refresh.
 */
@Composable
fun rememberNotificationPermissionController(
    onStatusMessage: (String) -> Unit = {},
): NotificationPermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isGranted by remember { mutableStateOf(context.isPostNotificationGranted()) }
    var hasRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRequestedOnce = true
        val effectiveGranted = granted && context.isPostNotificationGranted()
        isGranted = effectiveGranted
        if (effectiveGranted) {
            onStatusMessage("已开启本地通知权限，还款到期前将及时提醒")
        } else {
            onStatusMessage("未授予通知权限，可随时在系统通知设置中开启")
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isGranted = context.isPostNotificationGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openSettings: () -> Unit = {
        val opened = context.openAppNotificationSettings()
        if (!opened) {
            onStatusMessage("无法打开系统通知设置，请手动前往系统设置开启通知")
        }
    }

    val requestOrOpen: () -> Unit = {
        val currentlyGranted = context.isPostNotificationGranted()
        isGranted = currentlyGranted
        if (currentlyGranted) {
            onStatusMessage("系统通知权限已开启")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasRequestedOnce) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings()
        }
    }

    return NotificationPermissionController(
        isGranted = isGranted,
        requestOrOpenSettings = requestOrOpen,
        openSystemSettings = openSettings,
    )
}
