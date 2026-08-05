/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.settings.custom.obscura

import android.app.ActivityManager
import android.app.ObscuraManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.custom.spoofing.AppPickerSearchField
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ObscuraAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean = false,
    var isHidden: Boolean = false,
    var isLauncherHidden: Boolean = false,
    var isIsolated: Boolean = false,
    var restrictInternet: Boolean = false,
    var restrictStorage: Boolean = false,
    var forceDataIsolation: Boolean = false,
    var spoofAdb: Boolean = false,
    var spoofDevOptions: Boolean = false,
    var spoofWirelessDebug: Boolean = false,
    var spoofPkgVerifier: Boolean = false,
    var spoofUsbVerify: Boolean = false,
    var spoofAccessibility: Boolean = false,
)

private val EXCLUDED_SUFFIXES = listOf(
    ".auto_generated", ".appsearch", ".backup", ".carrier",
    ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
    ".qti", ".qualcomm", ".resources", ".systemui.clocks",
    ".systemui.plugin", ".theme", ".iconpack",
)

class ObscuraAppSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.obscura_title)
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                ObscuraAppSettingsContent(
                    context = requireContext(),
                )
            }
        }
    }
}

@Composable
private fun ObscuraAppSettingsContent(
    context: Context,
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<ObscuraAppEntry>() }

    val obscuraManager = remember {
        context.getSystemService(Context.OBSCURA_SERVICE) as? ObscuraManager
    }

    fun loadAppStates(): Map<String, ObscuraAppEntry> {
        if (obscuraManager == null) return emptyMap()
        val result = mutableMapOf<String, ObscuraAppEntry>()
        for (pkg in obscuraManager.hiddenPackages) {
            result.getOrPut(pkg) { ObscuraAppEntry(pkg, "", null) }.isHidden = true
        }
        for (pkg in obscuraManager.launcherHiddenPackages) {
            result.getOrPut(pkg) { ObscuraAppEntry(pkg, "", null) }.isLauncherHidden = true
        }
        for (pkg in obscuraManager.isolatedPackages) {
            val entry = result.getOrPut(pkg) { ObscuraAppEntry(pkg, "", null) }
            entry.isIsolated = true
            val restrictedGids = obscuraManager.getRestrictedGids(pkg)
            if (restrictedGids != null) {
                for (gid in restrictedGids) {
                    when (gid) {
                        ObscuraManager.GID_INET -> entry.restrictInternet = true
                        in ObscuraManager.STORAGE_GIDS -> entry.restrictStorage = true
                    }
                }
            }
            entry.forceDataIsolation = obscuraManager.isDataIsolationEnabled(pkg)
            val enabledSpoofs = obscuraManager.getEnabledSpoofSettings(pkg)
            entry.spoofAdb = enabledSpoofs.contains("adb_enabled")
            entry.spoofDevOptions = enabledSpoofs.contains("development_settings_enabled")
            entry.spoofWirelessDebug = enabledSpoofs.contains("adb_wifi_enabled")
            entry.spoofPkgVerifier = enabledSpoofs.contains("package_verifier_user_consent")
            entry.spoofUsbVerify = enabledSpoofs.contains("verify_apps_over_usb")
            entry.spoofAccessibility = enabledSpoofs.contains("accessibility_enabled")
        }
        return result
    }

    fun saveEntry(entry: ObscuraAppEntry) {
        if (obscuraManager == null) return
        scope.launch(Dispatchers.IO) {
            val launcherToggled = entry.isHidden || entry.isLauncherHidden
            obscuraManager.setPackageHidden(entry.packageName, entry.isHidden)
            obscuraManager.setPackageLauncherHidden(entry.packageName, entry.isLauncherHidden)

            if (entry.isIsolated) {
                obscuraManager.isolatePackage(entry.packageName)
                val gids = mutableListOf<Int>()
                if (entry.restrictInternet) gids.add(ObscuraManager.GID_INET)
                if (entry.restrictStorage) gids.addAll(ObscuraManager.STORAGE_GIDS.toList())
                obscuraManager.setRestrictedGids(entry.packageName, gids.toIntArray())
                obscuraManager.setDataIsolationEnabled(entry.packageName, entry.forceDataIsolation)

                obscuraManager.setSpoofSettingEnabled(entry.packageName, "adb_enabled", entry.spoofAdb)
                obscuraManager.setSpoofSettingEnabled(entry.packageName, "development_settings_enabled", entry.spoofDevOptions)
                obscuraManager.setSpoofSettingEnabled(entry.packageName, "adb_wifi_enabled", entry.spoofWirelessDebug)
                obscuraManager.setSpoofSettingEnabled(entry.packageName, "package_verifier_user_consent", entry.spoofPkgVerifier)
                obscuraManager.setSpoofSettingEnabled(entry.packageName, "verify_apps_over_usb", entry.spoofUsbVerify)
                obscuraManager.setSpoofSettingEnabled(entry.packageName, "accessibility_enabled", entry.spoofAccessibility)
            } else {
                obscuraManager.unisolatePackage(entry.packageName)
                obscuraManager.setRestrictedGids(entry.packageName, intArrayOf())
                obscuraManager.setDataIsolationEnabled(entry.packageName, false)
            }

            forceStopPackage(context, entry.packageName)
            if (launcherToggled) forceStopDefaultLauncher(context)
        }
    }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val appStates = loadAppStates()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val isExcluded = EXCLUDED_SUFFIXES.any { app.packageName.contains(it) }
                    if (isSystem && isExcluded) return@filter false
                    if (isSystem && !showSystemApps && !appStates.containsKey(app.packageName))
                        return@filter false
                    true
                }
                .sortedWith(compareBy(
                    { !appStates.containsKey(it.packageName) },
                    { pm.getApplicationLabel(it).toString().lowercase() }
                ))
                .map { app ->
                    val existing = appStates[app.packageName]
                    ObscuraAppEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isHidden = existing?.isHidden ?: false,
                        isLauncherHidden = existing?.isLauncherHidden ?: false,
                        isIsolated = existing?.isIsolated ?: false,
                        restrictInternet = existing?.restrictInternet ?: false,
                        restrictStorage = existing?.restrictStorage ?: false,
                        forceDataIsolation = existing?.forceDataIsolation ?: false,
                        spoofAdb = existing?.spoofAdb ?: false,
                        spoofDevOptions = existing?.spoofDevOptions ?: false,
                        spoofWirelessDebug = existing?.spoofWirelessDebug ?: false,
                        spoofPkgVerifier = existing?.spoofPkgVerifier ?: false,
                        spoofUsbVerify = existing?.spoofUsbVerify ?: false,
                        spoofAccessibility = existing?.spoofAccessibility ?: false,
                    )
                }
            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val query = searchQuery.lowercase()
        allApps.filter { app ->
            query.isEmpty() ||
                app.label.lowercase().contains(query) ||
                app.packageName.lowercase().contains(query)
        }
    }

    val activeCount = allApps.count { it.isHidden || it.isLauncherHidden || it.isIsolated }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Obscura",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (activeCount == 0) "No apps configured"
                            else "$activeCount app(s) configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AppPickerSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text("Show system apps") },
                    leadingIcon = if (showSystemApps) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        ObscuraAppItem(
                            entry = app,
                            onToggle = { updated ->
                                val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (i >= 0) {
                                    allApps[i] = updated
                                    saveEntry(updated)
                                }
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ObscuraAppItem(
    entry: ObscuraAppEntry,
    onToggle: (ObscuraAppEntry) -> Unit,
) {
    val hasActive = entry.isHidden || entry.isLauncherHidden || entry.isIsolated

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconBitmap = remember(entry.packageName) {
                    runCatching { entry.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
                }
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = entry.isLauncherHidden,
                    onClick = { onToggle(entry.copy(isLauncherHidden = !entry.isLauncherHidden)) },
                    label = { Text("Hide Launcher", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = entry.isHidden,
                    onClick = { onToggle(entry.copy(isHidden = !entry.isHidden)) },
                    label = { Text("Hide App", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = entry.isIsolated,
                    onClick = {
                        onToggle(entry.copy(isIsolated = !entry.isIsolated))
                    },
                    label = { Text("Isolate", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = { Icon(Icons.Default.Shield, null, Modifier.size(16.dp)) },
                )
            }

            if (entry.isIsolated) {
                Spacer(modifier = Modifier.height(4.dp))
                OptionRow("Restrict Internet", entry.restrictInternet) { onToggle(entry.copy(restrictInternet = it)) }
                OptionRow("Restrict Storage", entry.restrictStorage) { onToggle(entry.copy(restrictStorage = it)) }
                OptionRow("Force Data Isolation", entry.forceDataIsolation) { onToggle(entry.copy(forceDataIsolation = it)) }
                OptionRow("ADB", entry.spoofAdb) { onToggle(entry.copy(spoofAdb = it)) }
                OptionRow("Developer Options", entry.spoofDevOptions) { onToggle(entry.copy(spoofDevOptions = it)) }
                OptionRow("Wireless Debugging", entry.spoofWirelessDebug) { onToggle(entry.copy(spoofWirelessDebug = it)) }
                OptionRow("Package Verifier", entry.spoofPkgVerifier) { onToggle(entry.copy(spoofPkgVerifier = it)) }
                OptionRow("USB App Verification", entry.spoofUsbVerify) { onToggle(entry.copy(spoofUsbVerify = it)) }
                OptionRow("Accessibility", entry.spoofAccessibility) { onToggle(entry.copy(spoofAccessibility = it)) }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheck(!checked) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheck,
        )
    }
}

private fun getDefaultLauncher(context: Context): String {
    val roleManager = context.getSystemService(RoleManager::class.java)
    return roleManager?.getRoleHolders(RoleManager.ROLE_HOME)?.firstOrNull() ?: ""
}

private fun forceStopDefaultLauncher(context: Context) {
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val launcher = getDefaultLauncher(context)
        if (launcher.isNotEmpty()) {
            am.forceStopPackageAsUser(launcher, UserHandle.USER_CURRENT)
        }
    } catch (e: Exception) {
        Log.e("Obscura", "Error force stopping launcher", e)
    }
}

private fun forceStopPackage(context: Context, packageName: String) {
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        am.forceStopPackageAsUser(packageName, UserHandle.USER_CURRENT)
    } catch (e: Exception) {
        Log.e("Obscura", "Error force stopping $packageName", e)
    }
}
