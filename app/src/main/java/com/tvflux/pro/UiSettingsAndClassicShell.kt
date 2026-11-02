package com.tvflux.pro

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun SettingsAndVpnView(
    preferences: PlayerPreferences,
    rawBouquets: List<BouquetSummary>,
    allChannels: List<TvChannel>,
    vpnStatus: CloudflareTunnelStatus,
    vpnPausedTemporarily: Boolean = LiveProGlobalActions.vpnPausedTemporarily,
    compact: Boolean,
    onReconnectVpn: () -> Unit,
    onPauseVpnTemporarily: () -> Unit = { LiveProGlobalActions.onPauseVpnTemporarily() },
    onDisableVpnPermanently: () -> Unit = { LiveProGlobalActions.onDisableVpnPermanently() },
    onUpdatePreferences: (PlayerPreferences) -> Unit
) {
    val context = LocalContext.current
    val lang = preferences.appLanguage
    var cacheSizeLabel by remember { mutableStateOf(FluxBitmapCache.getCacheSizeMb(context)) }
    var showBouquetReorderModal by remember { mutableStateOf(false) }
    var showChannelReorderModal by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        item {
            Text(
                text = "⚙️ ${AppStrings.tr(lang, "إعدادات التطبيق واللغة وإدارة الباقات", "Paramètres, Langue & Gestion des Bouquets", "App Settings, Language & Bouquet Management")}",
                color = Color.White,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // 0. UI Theme Customization (Classic LIVE PRO Classic vs Modern Royal) + Low-RAM Device Acceleration + Network Speed
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🎨 ${AppStrings.tr(lang, "تخصيص مظهر الواجهة وتسريع الأجهزة الضعيفة وعرض سرعة الشبكة:", "Personnalisation de l'interface, Accélération & Vitesse réseau :", "UI Theme Customization, Low-RAM Acceleration & Network Speed:")}",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 14.sp else 17.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UiLayoutStyle.values().forEach { styleOpt ->
                        BeinFocusableButton(
                            text = (if (preferences.uiLayoutStyle == styleOpt) "✓ " else "") + styleOpt.label(lang),
                            isPrimary = preferences.uiLayoutStyle == styleOpt,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdatePreferences(preferences.copy(uiLayoutStyle = styleOpt))
                            }
                        )
                    }
                }
                Text(
                    text = "🎬 ${AppStrings.tr(lang, "اختر محرك المشغل الداخلي المدمج (ExoPlayer أو VLC):", "Choisir le moteur vidéo interne (ExoPlayer ou VLC) :", "Select Built-In Internal Player Engine (ExoPlayer or VLC):")}",
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 13.sp else 15.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InternalPlayerEngine.values().forEach { engOpt ->
                        BeinFocusableButton(
                            text = (if (preferences.playerEngine == engOpt) "✓ " else "") + engOpt.label(lang),
                            isPrimary = preferences.playerEngine == engOpt,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdatePreferences(preferences.copy(playerEngine = engOpt))
                            }
                        )
                    }
                }
                                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BeinFocusableButton(
                        text = if (preferences.lowEndDeviceMode) {
                            "🚀 تسريع المشغل للأجهزة الضعيفة: مفعّل ✓"
                        } else {
                            "🚀 تسريع المشغل للأجهزة الضعيفة: تلقائي"
                        },
                        isPrimary = preferences.lowEndDeviceMode,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val nextLow = !preferences.lowEndDeviceMode
                            onUpdatePreferences(
                                preferences.copy(
                                    lowEndDeviceMode = nextLow,
                                    bufferProfile = if (nextLow) BufferProfile.LITE_LOW_RAM else BufferProfile.BALANCED
                                )
                            )
                        }
                    )
                    BeinFocusableButton(
                        text = if (preferences.showNetworkSpeed) {
                            "🌐 عرض سرعة الشبكة (Vitesse réseau): مفعّل ✓"
                        } else {
                            "🌐 عرض سرعة الشبكة (Vitesse réseau): مخفي"
                        },
                        isPrimary = preferences.showNetworkSpeed,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onUpdatePreferences(preferences.copy(showNetworkSpeed = !preferences.showNetworkSpeed))
                        }
                    )
                }
            }
        }

        // 1. Application Language Selector (Arabic / Français / English)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🌐 ${AppStrings.tr(lang, "لغة التطبيق (العربية / Français / English):", "Langue de l'application :", "Application Language:")}",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 14.sp else 17.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppUiLanguage.values().forEach { lOpt ->
                        BeinFocusableButton(
                            text = lOpt.nativeLabel,
                            isPrimary = preferences.appLanguage == lOpt,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onUpdatePreferences(preferences.copy(appLanguage = lOpt))
                            }
                        )
                    }
                }
            }
        }

        // 2. Hide Unwanted Bouquets & Reorder Bouquets / Channels
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📂 ${AppStrings.tr(lang, "إخفاء الباقات غير المهمة وترتيب الباقات والقنوات بالريموت:", "Masquer les bouquets inutiles et réorganiser :", "Hide Unwanted Bouquets & Reorder via Remote:")}",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 14.sp else 17.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BeinFocusableButton(
                        text = "👁 ${AppStrings.tr(lang, "إخفاء / ترتيب الباقات", "Masquer / Trier Bouquets", "Hide / Reorder Bouquets")} (${rawBouquets.size})",
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { showBouquetReorderModal = true }
                    )

                    BeinFocusableButton(
                        text = "📺 ${AppStrings.tr(lang, "ترتيب القنوات", "Trier les chaînes", "Reorder Channels")} (${allChannels.size})",
                        isPrimary = false,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { showChannelReorderModal = true }
                    )

                    if (preferences.customBouquetOrder.isNotEmpty() ||
                        preferences.customChannelOrder.isNotEmpty() ||
                        preferences.hiddenBouquets.isNotEmpty()
                    ) {
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "إظهار الكل", "Tout afficher", "Reset All"),
                            isPrimary = false,
                            compact = compact,
                            onClick = {
                                onUpdatePreferences(
                                    preferences.copy(
                                        customBouquetOrder = emptyList(),
                                        hiddenBouquets = emptySet(),
                                        customChannelOrder = emptyList()
                                    )
                                )
                            }
                        )
                    }
                }

                // Direct 1-Click Toggles for hiding/showing "متعدد جودات" and "★ المفضلة" in the Bouquets sidebar
                val isMqHidden = CustomOrderHelper.isMultiQualityBouquetHidden(preferences.hiddenBouquets)
                val isFavBqHidden = CustomOrderHelper.isFavoritesBouquetHidden(preferences.hiddenBouquets)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BeinFocusableButton(
                        text = if (isMqHidden) {
                            "🚫 باقة (متعدد جودات): مخفية (اضغط للإظهار)"
                        } else {
                            "⚡ باقة (متعدد جودات): ظاهرة في الباقات ✓"
                        },
                        isPrimary = !isMqHidden,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onUpdatePreferences(
                                preferences.copy(
                                    hiddenBouquets = CustomOrderHelper.toggleMultiQualityBouquetHidden(preferences.hiddenBouquets)
                                )
                            )
                        }
                    )
                    BeinFocusableButton(
                        text = if (isFavBqHidden) {
                            "🚫 باقة (★ المفضلة) بالباقات: مخفية (اضغط للإظهار)"
                        } else {
                            "★ باقة (المفضلة) بالباقات: ظاهرة ✓"
                        },
                        isPrimary = !isFavBqHidden,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onUpdatePreferences(
                                preferences.copy(
                                    hiddenBouquets = CustomOrderHelper.toggleFavoritesBouquetHidden(
                                        preferences.hiddenBouquets,
                                        AppStrings.favoritesLabel(lang)
                                    )
                                )
                            )
                        }
                    )
                }
            }
        }

        // 2.B Anti-Freeze & Internet Fluctuation Cushion Settings (إعدادات منع التقطيع ومقاومة هزات الإنترنت)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "🛡️ ${AppStrings.tr(lang, "نظام منع التقطيع ومقاومة هزات الإنترنت (Anti-Freeze Engine):", "Système Anti-Coupure & Stabilité Réseau :", "Anti-Freeze & Network Jitter Cushion:")}",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 14.sp else 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                BufferProfile.values().forEach { bp ->
                    val selected = preferences.bufferProfile == bp
                    var bpFocused by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(if (bpFocused) 1.02f else 1.0f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    bpFocused -> Color.White
                                    selected -> BeinPrimaryPurple
                                    else -> BeinSurface
                                }
                            )
                            .border(
                                width = if (bpFocused) 2.5.dp else if (selected) 1.5.dp else 1.dp,
                                color = if (bpFocused) BeinFocusGlow else if (selected) Color.White else BeinBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .onFocusChanged { bpFocused = it.isFocused }
                            .focusable()
                            .clickable { onUpdatePreferences(preferences.copy(bufferProfile = bp)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = (if (selected) "✓ " else "") + bp.titleAr,
                            color = if (bpFocused) Color.Black else Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = bp.descAr,
                            color = if (bpFocused) Color.DarkGray else Color(0xFFE2E8F0),
                            fontSize = if (compact) 11.sp else 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 3. Smart Encrypted VPN Control (Auto-Start + Temporary Pause + Permanent Stop + Anti-Sniffing Protection)
        item {
            val isVpnActive = vpnStatus.connected && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(
                        1.8.dp,
                        if (isVpnActive) EmeraldLive else Color(0xFFF59E0B),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (isVpnActive) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (isVpnActive) EmeraldLive else Color(0xFFF59E0B)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isVpnActive) {
                                    "🔒 نفق الـ VPN المشفّر السريع: متصّل ونشط ✓ (${vpnStatus.statusText})"
                                } else if (vpnPausedTemporarily) {
                                    "⏸️ نفق الـ VPN متوقف مؤقتاً لهذه الجلسة (تم إخفاء القائمة الرئيسية لحماية الروابط)"
                                } else {
                                    "🛑 نفق الـ VPN متوقف دائماً (القائمة الرئيسية مخفية تلقائياً لمنع اعتراض الروابط)"
                                },
                                color = if (isVpnActive) EmeraldLive else Color(0xFFFDE68A),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 13.sp else 16.sp
                            )
                            Text(
                                text = "🛡️ تمر جميع بيانات التطبيق عبر نفق VPN المشفّر لمنع التقطيع ومنع سحب روابط البث. عند إيقاف الـ VPN تختفي القائمة الرئيسية تلقائياً.",
                                color = Color(0xFFE2E8F0),
                                fontSize = if (compact) 11.sp else 13.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "🔒 تشغيل وتفعيل VPN الآن", "🔒 Activer VPN", "🔒 Enable VPN Now"),
                        isPrimary = isVpnActive,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onReconnectVpn
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "⏸️ إيقاف مؤقت (لهذه الجلسة)", "⏸️ Pause Temporaire", "⏸️ Pause Temporarily"),
                        isPrimary = vpnPausedTemporarily && !isVpnActive,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onPauseVpnTemporarily
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "🛑 إيقاف دائم", "🛑 Désactiver Toujours", "🛑 Disable Permanently"),
                        isPrimary = LiveProGlobalActions.vpnPermanentDisable && !isVpnActive,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onDisableVpnPermanently
                    )
                }

                BeinFocusableButton(
                    text = if (LiveProGlobalActions.vpnAutoStartOnLaunch) {
                        "🚀 البدء التلقائي الدائم مع الـ VPN عند فتح التطبيق: مفعّل دائماً ✓ (موصى به)"
                    } else {
                        "🚀 البدء التلقائي مع الـ VPN عند فتح التطبيق: متوقف (اضغط للتفعيل التلقائي)"
                    },
                    isPrimary = LiveProGlobalActions.vpnAutoStartOnLaunch,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val nextAuto = !LiveProGlobalActions.vpnAutoStartOnLaunch
                        LiveProGlobalActions.updateVpnPrefs(
                            context = context,
                            permanentDisable = if (nextAuto) false else LiveProGlobalActions.vpnPermanentDisable,
                            autoStart = nextAuto
                        )
                        if (nextAuto) {
                            onReconnectVpn()
                        }
                    }
                )
            }
        }

        // 4. Thumbnail Cache Quota & Software Decoder Toggle
        item {
            var perfMetrics by remember { mutableStateOf<DatabasePerformanceMetrics?>(null) }
            LaunchedEffect(allChannels.size, cacheSizeLabel) {
                perfMetrics = TiviMateDiagnostics.buildSnapshot(context)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🖼️ إدارة ذاكرة الصور المصغرة ($cacheSizeLabel / الحد: ${preferences.thumbnailCacheLimit.titleAr})",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 13.sp else 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "مسح كاش الصور", "Vider le cache", "Clear Thumbnails"),
                        isPrimary = false,
                        compact = true,
                        onClick = {
                            FluxBitmapCache.clear(context)
                            cacheSizeLabel = FluxBitmapCache.getCacheSizeMb(context)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThumbnailCacheLimitOption.values().forEach { opt ->
                        BeinFocusableButton(
                            text = opt.name.replace("MB_", "") + " MB",
                            isPrimary = preferences.thumbnailCacheLimit == opt,
                            compact = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                FluxBitmapCache.maxDiskCacheBytes = opt.maxBytes
                                FluxBitmapCache.enforceDiskQuota(context, opt.maxBytes)
                                cacheSizeLabel = FluxBitmapCache.getCacheSizeMb(context)
                                onUpdatePreferences(preferences.copy(thumbnailCacheLimit = opt))
                            }
                        )
                    }
                    BeinFocusableButton(
                        text = if (preferences.preferSoftwareDecoder) "🎞 فك الترميز: برمجي (SW)" else "🎞 فك الترميز: تلقائي (HW+SW)",
                        isPrimary = preferences.preferSoftwareDecoder,
                        compact = true,
                        modifier = Modifier.weight(1.4f),
                        onClick = {
                            onUpdatePreferences(preferences.copy(preferSoftwareDecoder = !preferences.preferSoftwareDecoder))
                        }
                    )
                }

                // TiviMate SQLite & Codec Diagnostics Summary
                val m = perfMetrics
                if (m != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BeinDarkBg)
                            .border(1.dp, BeinBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📊 محرك TiviMate SQLite المحلي: ${m.totalChannelsInDb} قناة • ${m.totalMoviesInDb} فيلم • ${m.totalSeriesInDb} مسلسل",
                            color = EmeraldLive,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 12.sp else 14.sp
                        )
                        Text(
                            text = "💾 حجم قاعدة البيانات: ${m.dbFileSizeMb} • الذاكرة العشوائية (RAM): ${m.ramUsedMb}/${m.ramMaxMb} MB • زمن البحث: ${m.lastQueryTimeMs}ms",
                            color = Color(0xFFE2E8F0),
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                        Text(
                            text = "🎬 ترميزات الفيديو المدعومة بالجهاز: ${m.supportedVideoCodecs.joinToString(" ، ")}",
                            color = Color.White,
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                        Text(
                            text = "🔊 ترميزات الصوت المدعومة بالجهاز: ${m.supportedAudioCodecs.joinToString(" ، ")}",
                            color = Color.LightGray,
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                        Text(
                            text = "🔄 آخر مزامنة تفاضلية: ${m.lastSyncSummary}",
                            color = Color(0xFFCBD5E1),
                            fontSize = if (compact) 11.sp else 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showBouquetReorderModal) {
        BouquetReorderDialog(
            rawBouquets = rawBouquets,
            preferences = preferences,
            compact = compact,
            onSave = { newOrder, newHidden ->
                onUpdatePreferences(
                    preferences.copy(
                        customBouquetOrder = newOrder,
                        hiddenBouquets = newHidden
                    )
                )
            },
            onDismiss = { showBouquetReorderModal = false }
        )
    }

    if (showChannelReorderModal) {
        ChannelReorderDialog(
            allChannels = allChannels,
            preferences = preferences,
            compact = compact,
            onSaveOrder = { newChannelOrder ->
                onUpdatePreferences(
                    preferences.copy(customChannelOrder = newChannelOrder)
                )
            },
            onDismiss = { showChannelReorderModal = false }
        )
    }
}

@Composable
internal fun BouquetReorderDialog(
    rawBouquets: List<BouquetSummary>,
    preferences: PlayerPreferences,
    compact: Boolean,
    onSave: (List<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var orderedNames by remember(rawBouquets, preferences.customBouquetOrder) {
        val currentSorted = CustomOrderHelper.sortBouquets(
            rawBouquets,
            preferences.customBouquetOrder,
            preferences.hiddenBouquets,
            includeHidden = true
        ).map { it.name }
        mutableStateOf(currentSorted)
    }
    var hiddenSet by remember(preferences.hiddenBouquets) {
        mutableStateOf(preferences.hiddenBouquets)
    }
    val countsMap = remember(rawBouquets) { rawBouquets.associate { it.name to it.count } }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 660.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                .padding(if (compact) 12.dp else 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "📂 إخفاء الباقات غير المهمة وترتيبها بالريموت (▲ / ▼)",
                        color = Color.White,
                        fontSize = if (compact) 16.sp else 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                BeinFocusableButton(
                    text = "حفظ وإغلاق ✓",
                    isPrimary = true,
                    compact = true,
                    onClick = {
                        onSave(orderedNames, hiddenSet)
                        onDismiss()
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(orderedNames, key = { idx, name -> "${idx}_${name}" }) { idx, bName ->
                    val isHidden = when {
                        bName == MultiQualityEngine.BOUQUET_NAME -> CustomOrderHelper.isMultiQualityBouquetHidden(hiddenSet)
                        bName.startsWith("★") -> CustomOrderHelper.isFavoritesBouquetHidden(hiddenSet) || bName in hiddenSet
                        else -> bName in hiddenSet
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isHidden) Color(0xFF1A0D18) else BeinCard)
                            .border(1.dp, if (isHidden) CrimsonAlert.copy(alpha = 0.5f) else BeinBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}. $bName (${countsMap[bName] ?: 0})" + if (isHidden) " [مخفية]" else "",
                            color = if (isHidden) Color.Gray else Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DpadSmallControlBtn(
                                label = if (isHidden) "👁 إظهار" else "🚫 إخفاء",
                                enabled = true,
                                onClick = {
                                    hiddenSet = when {
                                        bName == MultiQualityEngine.BOUQUET_NAME ->
                                            CustomOrderHelper.toggleMultiQualityBouquetHidden(hiddenSet)
                                        bName.startsWith("★") ->
                                            CustomOrderHelper.toggleFavoritesBouquetHidden(hiddenSet, bName)
                                        isHidden -> hiddenSet - bName
                                        else -> hiddenSet + bName
                                    }
                                    onSave(orderedNames, hiddenSet)
                                }
                            )
                            DpadSmallControlBtn(
                                label = "⏫ للأول",
                                enabled = idx > 0,
                                onClick = {
                                    orderedNames = CustomOrderHelper.moveItem(orderedNames, idx, 0)
                                    onSave(orderedNames, hiddenSet)
                                }
                            )
                            DpadSmallControlBtn(
                                label = "▲",
                                enabled = idx > 0,
                                onClick = {
                                    orderedNames = CustomOrderHelper.moveItem(orderedNames, idx, idx - 1)
                                    onSave(orderedNames, hiddenSet)
                                }
                            )
                            DpadSmallControlBtn(
                                label = "▼",
                                enabled = idx < orderedNames.lastIndex,
                                onClick = {
                                    orderedNames = CustomOrderHelper.moveItem(orderedNames, idx, idx + 1)
                                    onSave(orderedNames, hiddenSet)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChannelReorderDialog(
    allChannels: List<TvChannel>,
    preferences: PlayerPreferences,
    compact: Boolean,
    onSaveOrder: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(allChannels) {
        listOf("الكل") + allChannels.map { it.group }.distinct()
    }
    var selectedBouquet by remember { mutableStateOf(groups.firstOrNull() ?: "الكل") }
    var channelList by remember(allChannels, selectedBouquet) {
        val filtered = if (selectedBouquet == "الكل") allChannels else allChannels.filter { it.group == selectedBouquet }
        mutableStateOf(filtered)
    }

    fun persistNewList(updatedGroupChannels: List<TvChannel>) {
        channelList = updatedGroupChannels
        val updatedKeys = updatedGroupChannels.map { it.stableKey }
        val updatedSet = updatedKeys.toHashSet()
        val fullKeys = mutableListOf<String>()
        fullKeys.addAll(updatedKeys)
        for (ch in allChannels) {
            if (ch.stableKey !in updatedSet) {
                fullKeys.add(ch.stableKey)
            }
        }
        onSaveOrder(fullKeys)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 660.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                .padding(if (compact) 12.dp else 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "📺 ترتيب القنوات بالأسهم (▲ لأعلى / ▼ لأسفل / ⏫ للأول)",
                        color = Color.White,
                        fontSize = if (compact) 15.sp else 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                BeinFocusableButton(
                    text = "تم ✓",
                    isPrimary = true,
                    compact = true,
                    onClick = onDismiss
                )
            }

            Spacer(Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups, key = { it }) { g ->
                    BeinFocusableButton(
                        text = g,
                        isPrimary = g == selectedBouquet,
                        compact = true,
                        onClick = { selectedBouquet = g }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(channelList, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { idx, ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BeinCard)
                            .border(1.dp, BeinBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${idx + 1}. ${ch.name} (${ch.group})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DpadSmallControlBtn(
                                label = "⏫ للأول",
                                enabled = idx > 0,
                                onClick = {
                                    persistNewList(CustomOrderHelper.moveItem(channelList, idx, 0))
                                }
                            )
                            DpadSmallControlBtn(
                                label = "▲",
                                enabled = idx > 0,
                                onClick = {
                                    persistNewList(CustomOrderHelper.moveItem(channelList, idx, idx - 1))
                                }
                            )
                            DpadSmallControlBtn(
                                label = "▼",
                                enabled = idx < channelList.lastIndex,
                                onClick = {
                                    persistNewList(CustomOrderHelper.moveItem(channelList, idx, idx + 1))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DpadSmallControlBtn(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .scale(if (focused) 1.07f else 1.0f)
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    !enabled -> Color(0xFF130D1E)
                    focused -> Color.White
                    else -> BeinPrimaryPurple
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) BeinFocusGlow else Color.White.copy(alpha = 0.4f),
                shape = RoundedCornerShape(7.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (enabled && ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    true
                } else false
            }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (!enabled) Color.Gray else if (focused) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}


/**
 * Smart Diagnostic Popup Modal for:
 * 1) Expired Xtream Account (ACCOUNT_EXPIRED)
 * 2) Invalid Username/Password (AUTH_INVALID)
 * 3) Customer Internet Connection Issue (INTERNET_ERROR)
 * 4) Temporary Stream Server Outage (SERVER_OUTAGE)
 */
@Composable
internal fun SmartDiagnosticAlertModal(
    alert: DiagnosticAlertInfo,
    deviceId: String,
    compact: Boolean,
    onOpenCustomerSpace: () -> Unit,
    onRetrySync: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isExpiredAlert = alert.type == DiagnosticAlertType.ACCOUNT_EXPIRED
    val borderColor = when (alert.type) {
        DiagnosticAlertType.ACCOUNT_EXPIRED -> CrimsonAlert
        DiagnosticAlertType.AUTH_INVALID -> Color(0xFFF97316)
        DiagnosticAlertType.INTERNET_ERROR -> Color(0xFFF59E0B)
        DiagnosticAlertType.SERVER_OUTAGE -> BeinHighlightPurple
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = if (compact) 520.dp else 620.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF120724), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.dp, borderColor, RoundedCornerShape(22.dp))
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            Text(
                text = alert.title,
                color = if (isExpiredAlert) Color(0xFFFCA5A5) else Color.White,
                fontSize = if (compact) 17.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = alert.message,
                color = Color(0xFFE2E8F0),
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BeinDarkBg)
                    .border(1.dp, BeinBorder, RoundedCornerShape(12.dp))
                    .clickable { copyDeviceCodeToClipboard(context, deviceId) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📞 $ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER",
                    color = EmeraldLive,
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = deviceId,
                        color = Color.White,
                        fontSize = if (compact) 14.sp else 16.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isExpiredAlert) {
                    BeinFocusableButton(
                        text = "📲 فتح فضاء الزبون",
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenCustomerSpace
                    )
                } else {
                    BeinFocusableButton(
                        text = "🔄 إعادة المحاولة الآن",
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onRetrySync
                    )
                }
                BeinFocusableButton(
                    text = "حسناً، فهمت ✓",
                    isPrimary = false,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
            }
        }
    }
}


/**
 * VOD Resume Playback vs Start Over Prompt Modal (استئناف المشاهدة من حيث توقفت أو البدء من البداية)
 */
@Composable
internal fun VodResumePromptModal(
    channel: TvChannel,
    progress: VodWatchProgress,
    lang: AppUiLanguage,
    compact: Boolean,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit
) {
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(90L)
        runCatching { resumeFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = if (compact) 460.dp else 540.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF140828), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.dp, EmeraldLive, RoundedCornerShape(22.dp))
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = AppStrings.tr(
                    lang,
                    "⏯ متابعة المشاهدة من حيث توقفت",
                    "⏯ Reprendre la lecture",
                    "⏯ Resume Playback"
                ),
                color = Color.White,
                fontSize = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = channel.name,
                color = Color(0xFFE2E8F0),
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C241B))
                    .border(1.dp, EmeraldLive, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "⏱ ما شاهدته سابقاً: ${progress.formattedPosition} من أصل ${progress.formattedDuration} (${(progress.progressFraction * 100).toInt()}%)",
                    color = EmeraldLive,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x55000000))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.progressFraction.coerceIn(0.05f, 1f))
                            .background(EmeraldLive)
                    )
                }
            }

            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "▶ استئناف المشاهدة من (${progress.formattedPosition})",
                    "▶ Reprendre à (${progress.formattedPosition})",
                    "▶ Resume from (${progress.formattedPosition})"
                ),
                isPrimary = true,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(resumeFocus),
                onClick = onResume
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "🔄 البدء من البداية", "🔄 Recommencer", "🔄 Start Over"),
                    isPrimary = false,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onStartOver
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "إلغاء", "Annuler", "Cancel"),
                    isPrimary = false,
                    compact = compact,
                    modifier = Modifier.weight(0.7f),
                    onClick = onDismiss
                )
            }
        }
    }
}

/**
 * Authentic Classic LIVE PRO Classic Dashboard Layout (الشكل الكلاسيكي مثل LIVE PRO Classic)
 */
@Composable
internal fun ClassicLiveProDashboard(
    deviceId: String,
    subscription: SubscriptionInfo?,
    vpnStatus: CloudflareTunnelStatus,
    networkSpeedLabel: String,
    showScreenCode: Boolean = true,
    onToggleScreenCodeVisibility: () -> Unit = {},
    liveCount: Int,
    moviesCount: Int,
    seriesCount: Int,
    playlistsCount: Int,
    favoritesCount: Int,
    continueWatchingCount: Int,
    lang: AppUiLanguage,
    compact: Boolean,
    onOpenTab: (MainTab) -> Unit,
    onSwitchToModernUi: () -> Unit,
    onOpenQuickAddAccount: () -> Unit = { LiveProGlobalActions.onOpenQuickAddAccount() },
    onOpenVpnControl: () -> Unit = { LiveProGlobalActions.onOpenVpnControl() },
    onRefreshAll: () -> Unit,
    onOpenActivation: () -> Unit,
    onExit: () -> Unit
) {
    val maskedDeviceId = if (showScreenCode) deviceId else "DEV-••••-••••"
    val hPad = if (compact) 12.dp else 28.dp
    val vPad = if (compact) 10.dp else 20.dp
    val gap = if (compact) 10.dp else 14.dp
    val chipH = if (compact) 40.dp else 46.dp
    val chipFs = if (compact) 12.sp else 13.sp

    val firstTileFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(90L)
        runCatching { firstTileFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0B0B10), Color(0xFF08080C), Color(0xFF050508))
                )
            )
            .padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
    ) {
        // ── Premium top command bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chipH + 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE6111118))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            @Composable
            fun TopChip(
                label: String,
                accent: Color = Color.White.copy(alpha = 0.12f),
                fg: Color = Color.White,
                onClick: () -> Unit
            ) {
                var f by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .height(chipH)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (f) Color.White else accent)
                        .border(1.dp, if (f) BeinFocusGlow else Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .onFocusChanged { f = it.isFocused }
                        .focusable()
                        .clickable { onClick() }
                        .padding(horizontal = if (compact) 12.dp else 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (f) Color.Black else fg,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = chipFs,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            TopChip("↻ " + AppStrings.tr(lang, "تحديث", "Maj", "Refresh"), Color(0xFF1E293B)) { onRefreshAll() }
            TopChip("✦ " + AppStrings.tr(lang, "الوضع المتطور", "Moderne", "Modern"), Color(0xFF312E81)) { onSwitchToModernUi() }
            TopChip(
                if (vpnStatus.connected) "🔒 VPN " + AppStrings.tr(lang, "متصل", "ON", "ON")
                else "🔓 VPN",
                if (vpnStatus.connected) Color(0xFF064E3B) else Color(0xFF7C2D12),
                if (vpnStatus.connected) EmeraldLive else Color(0xFFFBBF24)
            ) { onOpenVpnControl() }
            TopChip("+ " + AppStrings.tr(lang, "إضافة حساب", "Compte", "Add"), Color(0xFF1E3A5F), Color(0xFF93C5FD)) { onOpenQuickAddAccount() }

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A0A0F))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = networkSpeedLabel.ifBlank { "—" },
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 11.sp else 12.sp,
                    textAlign = TextAlign.Center
                )
                Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.15f)))
                Text(
                    text = if (subscription != null) "⏳ ${subscription.daysLeft} " + AppStrings.tr(lang, "يوم", "j", "d") else "—",
                    color = EmeraldLive,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 12.sp else 13.sp,
                    textAlign = TextAlign.Center
                )
                Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.15f)))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { onToggleScreenCodeVisibility() }
                ) {
                    Text(
                        text = if (showScreenCode) "👁" else "🙈",
                        fontSize = 12.sp
                    )
                    Text(
                        text = "ID: $maskedDeviceId",
                        color = Color(0xFFBAE6FD),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 36.dp else 42.dp),
                cornerRadius = 12.dp
            )
        }

        // ── Primary content row (Live / Movies / Series) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.15f),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "المسلسلات", "SÉRIES", "SERIES"),
                subtitle = "$seriesCount " + AppStrings.tr(lang, "مسلسل كامل", "Séries", "Series"),
                iconEmoji = "🎞️",
                accentColors = listOf(Color(0xFF065F46), Color(0xFF022C22)),
                modifier = Modifier.weight(1f).fillMaxHeight().then(
                    if (!compact) Modifier else Modifier
                ),
                compact = compact,
                onClick = { onOpenTab(MainTab.SERIES) }
            )
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "الأفلام", "FILMS", "MOVIES"),
                subtitle = "$moviesCount " + AppStrings.tr(lang, "فيلم سينمائي", "Films", "Movies"),
                iconEmoji = "🎬",
                accentColors = listOf(Color(0xFF9A3412), Color(0xFF431407)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compact,
                onClick = { onOpenTab(MainTab.MOVIES) }
            )
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "البث المباشر", "TV LIVE", "LIVE TV"),
                subtitle = "$liveCount " + AppStrings.tr(lang, "قناة مباشرة", "Chaînes", "Channels"),
                iconEmoji = "📺",
                accentColors = listOf(Color(0xFFF8FAFC), Color(0xFFE2E8F0)),
                titleColor = Color(0xFF0F172A),
                subtitleColor = Color(0xFF334155),
                modifier = Modifier
                    .weight(1.08f)
                    .fillMaxHeight()
                    .focusRequester(firstTileFocus),
                compact = compact,
                emphasized = true,
                onClick = { onOpenTab(MainTab.LIVE) }
            )
        }

        // ── Secondary tools row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.85f),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "الإعدادات والتخصيص", "PARAMÈTRES", "SETTINGS"),
                subtitle = AppStrings.tr(lang, "المظهر • الشبكة • السرعة", "Thème • Réseau", "Theme • Network"),
                iconEmoji = "⚙️",
                accentColors = listOf(Color(0xFF312E81), Color(0xFF1E1B4B)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compact,
                onClick = { onOpenTab(MainTab.SETTINGS) }
            )
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "حالة التفعيل والرمز", "ACTIVATION", "ACTIVATION"),
                subtitle = maskedDeviceId,
                iconEmoji = "🔑",
                accentColors = listOf(Color(0xFF115E59), Color(0xFF042F2E)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compact,
                onClick = onOpenActivation
            )
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "المفضلة وما شاهدته", "FAVORIS", "FAVORITES"),
                subtitle = "$favoritesCount / $continueWatchingCount",
                iconEmoji = "⭐",
                accentColors = listOf(Color(0xFF9D174D), Color(0xFF500724)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compact,
                onClick = { onOpenTab(MainTab.FAVORITES) }
            )
            ClassicLiveProBigTile(
                title = AppStrings.tr(lang, "إضافة وإدارة القوائم", "LISTES", "PLAYLISTS"),
                subtitle = "$playlistsCount " + AppStrings.tr(lang, "قائمة نشطة", "Listes", "Lists"),
                iconEmoji = "📲",
                accentColors = listOf(Color(0xFF1D4ED8), Color(0xFF1E3A8A)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                compact = compact,
                onClick = { onOpenTab(MainTab.XTREAM) }
            )
        }
    }
}

@Composable
internal fun ClassicLiveProBigTile(
    title: String,
    subtitle: String,
    iconEmoji: String,
    accentColors: List<Color>,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.White,
    subtitleColor: Color = Color(0xFFE2E8F0),
    compact: Boolean = false,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (compact) 18.dp else 22.dp)
    val iconSize = when {
        compact && emphasized -> 36.sp
        compact -> 32.sp
        emphasized -> 44.sp
        else -> 38.sp
    }
    val titleSize = when {
        compact && emphasized -> 16.sp
        compact -> 14.sp
        emphasized -> 20.sp
        else -> 17.sp
    }
    val subSize = when {
        compact -> 12.sp
        emphasized -> 14.sp
        else -> 13.sp
    }
    val bgBrush = when {
        focused && emphasized -> Brush.verticalGradient(listOf(Color.White, Color(0xFFF1F5F9)))
        focused -> Brush.verticalGradient(listOf(Color.White, Color(0xFFF8FAFC)))
        else -> Brush.verticalGradient(accentColors)
    }
    val tColor = if (focused) Color(0xFF0F172A) else titleColor
    val sColor = if (focused) Color(0xFF334155) else subtitleColor

    Box(
        modifier = modifier
            .scale(if (focused) 1.035f else 1.0f)
            .clip(shape)
            .background(bgBrush)
            .border(
                width = if (focused) 2.5.dp else if (emphasized) 1.5.dp else 1.dp,
                color = when {
                    focused -> BeinFocusGlow
                    emphasized -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.16f)
                },
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("CLASSIC_TILE_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("CLASSIC_TILE_CLICK", 200L) { onClick() } }
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 12.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 48.dp else 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (focused) Color(0xFF0F172A).copy(alpha = 0.08f)
                        else Color.Black.copy(alpha = 0.22f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = iconEmoji, fontSize = iconSize, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            Text(
                text = title,
                color = tColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = titleSize,
                textAlign = TextAlign.Center,
                maxLines = 2,
                lineHeight = (titleSize.value + 4).sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = sColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = subSize,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

