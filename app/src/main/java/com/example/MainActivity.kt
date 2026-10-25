package com.example

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

// دوره‌های زمانی انتخابی
enum class TimeRange(val title: String) {
    HOURLY("ساعتی"),
    DAILY("امروز"),
    WEEKLY("هفته"),
    MONTHLY("ماهانه"),
    YEARLY("سالانه")
}

// مدل نگهداری اطلاعات مصرف برنامه
data class AppUsageInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val mobileBytes: Long,
    val wifiBytes: Long
) {
    val totalBytes: Long get() = mobileBytes + wifiBytes
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFF6750A4),
                        secondary = Color(0xFF625B71),
                        background = Color(0xFFFDF7FF),
                        surface = Color(0xFFFFFFFF),
                        surfaceVariant = Color(0xFFF3EDF7)
                    )
                ) {
                    DataUsageApp()
                }
            }
        }
    }
}

// توابع کمکی برای تبدیل بایت و ارقام به زبان فارسی
fun String.toPersianDigits(): String = map { char ->
    if (char in '0'..'9') char + 1728 else char
}.joinToString("")

fun formatBytesToPersian(bytes: Long): String {
    if (bytes <= 0) return "۰ مگابایت"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f گیگابایت", gb).toPersianDigits()
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f مگابایت", mb).toPersianDigits()
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f کیلوبایت", kb).toPersianDigits()
        else -> String.format(java.util.Locale.US, "%d بایت", bytes).toPersianDigits()
    }
}

// بررسی دسترسی کاربر به آمار برنامه‌ها
fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataUsageApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var selectedRange by remember { mutableStateOf(TimeRange.DAILY) }
    var appList by remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    // بررسی مجدد دسترسی هنگام بازگشت کاربر از صفحه تنظیمات گوشی
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val currentPermission = checkUsageStatsPermission(context)
                if (currentPermission != hasPermission) {
                    hasPermission = currentPermission
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // محاسبه مجدد داده‌ها با تغییر دوره، اخذ دسترسی یا فشردن دکمه بروزرسانی
    LaunchedEffect(hasPermission, selectedRange, reloadTrigger) {
        if (hasPermission) {
            isLoading = true
            appList = withContext(Dispatchers.IO) {
                fetchNetworkUsage(context, selectedRange)
            }
            isLoading = false
        }
    }

    val filteredList = remember(appList, searchQuery) {
        if (searchQuery.isBlank()) {
            appList
        } else {
            appList.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("جستجوی نام برنامه...", fontSize = 14.sp) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_text_field")
                        )
                    } else {
                        Text(
                            text = "مدیریت و پایش مصرف بسته",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                actions = {
                    if (hasPermission) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                            modifier = Modifier.testTag("search_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (isSearchActive) "بستن جستجو" else "جستجو",
                                tint = Color(0xFF49454F)
                            )
                        }
                        IconButton(
                            onClick = { reloadTrigger++ },
                            modifier = Modifier.testTag("refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "بروزرسانی داده‌ها",
                                tint = Color(0xFF6750A4)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFEF7FF)
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasPermission) {
                // صفحه هشدار در صورت فعال نبودن دسترسی در گوشی
                PermissionRequiredScreen(
                    onRequestPermission = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    },
                    onRefresh = {
                        hasPermission = checkUsageStatsPermission(context)
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // تب‌های انتخاب بازه زمانی
                    TimeRangeSelector(
                        selectedRange = selectedRange,
                        onRangeSelected = { selectedRange = it }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // کارت آمار کلی مصرف
                    val totalMobile = appList.sumOf { it.mobileBytes }
                    val totalWifi = appList.sumOf { it.wifiBytes }
                    OverallStatsCard(mobileBytes = totalMobile, wifiBytes = totalWifi)

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تفکیک مصرف برنامه‌ها (بیشترین به کمترین)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1D1B20)
                            )
                        )
                        if (appList.isNotEmpty()) {
                            Text(
                                text = "${filteredList.size.toString().toPersianDigits()} برنامه",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xFF6750A4))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "در حال محاسبه مصرف داده...",
                                    fontSize = 13.sp,
                                    color = Color(0xFF6750A4)
                                )
                            }
                        }
                    } else if (filteredList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DataUsage,
                                    contentDescription = null,
                                    tint = Color(0xFFCAC4D0),
                                    modifier = Modifier.size(54.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (searchQuery.isNotEmpty())
                                        "برنامه‌ای با این نام یافت نشد."
                                    else
                                        "داده‌ای برای این بازه یافت نشد یا اینترنتی مصرف نشده است.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { reloadTrigger++ },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("بررسی مجدد")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("app_usage_list")
                        ) {
                            items(filteredList, key = { it.packageName }) { app ->
                                AppUsageItemCard(app = app)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimeRangeSelector(
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3EDF7))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        TimeRange.entries.forEach { range ->
            val isSelected = range == selectedRange
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color(0xFF6750A4) else Color.Transparent)
                    .clickable { onRangeSelected(range) }
                    .padding(vertical = 8.dp)
                    .testTag("time_range_${range.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = range.title,
                    color = if (isSelected) Color.White else Color(0xFF49454F),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun OverallStatsCard(mobileBytes: Long, wifiBytes: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overall_stats_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF).copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = "داده سیم‌کارت",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("بسته سیم‌کارت:", fontSize = 13.sp, color = Color(0xFF49454F))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatBytesToPersian(mobileBytes),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    color = Color(0xFF6750A4)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(44.dp)
                    .background(Color(0xFFCAC4D0))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "وای‌فای",
                        tint = Color(0xFF1B6E32),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("وای‌فای (Wi-Fi):", fontSize = 13.sp, color = Color(0xFF49454F))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatBytesToPersian(wifiBytes),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    color = Color(0xFF1B6E32)
                )
            }
        }
    }
}

@Composable
fun AppUsageItemCard(app: AppUsageInfo) {
    val bitmap = remember(app.icon) {
        try {
            app.icon?.toBitmap(96, 96, null)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_usage_card_${app.packageName}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFECE6F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // آیکون برنامه
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8DEF8)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "سیم‌کارت: ${formatBytesToPersian(app.mobileBytes)}",
                        fontSize = 11.sp,
                        color = Color(0xFF6750A4)
                    )
                    Text(
                        text = "وای‌فای: ${formatBytesToPersian(app.wifiBytes)}",
                        fontSize = 11.sp,
                        color = Color(0xFF535F70)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // کل مصرف
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatBytesToPersian(app.totalBytes),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = Color(0xFF1D1B20)
                )
                Text(
                    text = "مجموع",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("permission_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = Color(0xFF6750A4),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "نیاز به تأیید مجوز دسترسی به داده‌ها",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "برای اینکه برنامه بتواند مصرف بسته اینترنت هر اپلیکیشن را محاسبه کند، لطفاً در بخش تنظیمات گوشی، دسترسی Usage Access را به این برنامه اعطا کنید.",
            textAlign = TextAlign.Center,
            color = Color(0xFF49454F),
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
            modifier = Modifier.testTag("open_settings_button")
        ) {
            Text("باز کردن تنظیمات دسترسی")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onRefresh,
            modifier = Modifier.testTag("check_permission_button")
        ) {
            Text("تأیید کردم (بررسی مجدد)", color = Color(0xFF6750A4))
        }
    }
}

// تابع واکشی مصرف شبکه از طریق ابزار رسمی NetworkStatsManager
fun fetchNetworkUsage(context: Context, range: TimeRange): List<AppUsageInfo> {
    val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyList()
    val packageManager = context.packageManager
    val cal = Calendar.getInstance()
    val endTime = cal.timeInMillis

    // تعیین زمان شروع بر اساس بازه انتخابی
    when (range) {
        TimeRange.HOURLY -> cal.add(Calendar.HOUR_OF_DAY, -1)
        TimeRange.DAILY -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        TimeRange.WEEKLY -> cal.add(Calendar.DAY_OF_YEAR, -7)
        TimeRange.MONTHLY -> cal.add(Calendar.DAY_OF_YEAR, -30)
        TimeRange.YEARLY -> cal.add(Calendar.DAY_OF_YEAR, -365)
    }
    val startTime = cal.timeInMillis

    val installedPackages = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }
    } catch (_: Exception) {
        emptyList()
    }

    // حافظه پنهان موقت برای سرعت بالا در محاسبه UIDهای مشترک
    val trafficCache = mutableMapOf<Pair<Int, Int>, Long>()

    val results = mutableListOf<AppUsageInfo>()

    for (pkg in installedPackages) {
        val appInfo = pkg.applicationInfo ?: continue
        val uid = appInfo.uid

        // محاسبه بایت‌های دیتای سیم‌کارت (Mobile)
        val mobileKey = Pair(ConnectivityManager.TYPE_MOBILE, uid)
        val mobileBytes = trafficCache.getOrPut(mobileKey) {
            getTrafficForUid(
                networkStatsManager,
                ConnectivityManager.TYPE_MOBILE,
                uid,
                startTime,
                endTime
            )
        }

        // محاسبه بایت‌های شبکه بی‌سیم (Wi-Fi)
        val wifiKey = Pair(ConnectivityManager.TYPE_WIFI, uid)
        val wifiBytes = trafficCache.getOrPut(wifiKey) {
            getTrafficForUid(
                networkStatsManager,
                ConnectivityManager.TYPE_WIFI,
                uid,
                startTime,
                endTime
            )
        }

        val total = mobileBytes + wifiBytes
        // تنها برنامه‌هایی که در این بازه مصرف داشته‌اند لیست می‌شوند
        if (total > 0) {
            val label = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg.packageName
            }
            val icon = try {
                packageManager.getApplicationIcon(appInfo)
            } catch (_: Exception) {
                null
            }
            results.add(
                AppUsageInfo(
                    appName = label,
                    packageName = pkg.packageName,
                    icon = icon,
                    mobileBytes = mobileBytes,
                    wifiBytes = wifiBytes
                )
            )
        }
    }

    // مرتب‌سازی نزولی بر اساس بیشترین حجم مصرف‌شده
    return results.sortedByDescending { it.totalBytes }
}

fun getTrafficForUid(
    manager: NetworkStatsManager,
    networkType: Int,
    uid: Int,
    start: Long,
    end: Long
): Long {
    return try {
        val stats: NetworkStats = manager.queryDetailsForUid(networkType, null, start, end, uid)
        val detailBucket = NetworkStats.Bucket()
        var total = 0L
        while (stats.hasNextBucket()) {
            stats.getNextBucket(detailBucket)
            total += detailBucket.rxBytes + detailBucket.txBytes
        }
        stats.close()
        total
    } catch (_: Exception) {
        0L
    }
}
