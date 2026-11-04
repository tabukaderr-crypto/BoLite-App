package com.example.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.CategoryChip
import com.example.ModifiedViewModelWithLiveFilter
import com.example.accessibility.EnhancedAccessibilityService
import com.example.data.SavedCard
import com.example.ui.theme.BoLiteTheme
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CardDark
import com.example.ui.theme.GrayButton
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.TextHint
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import com.example.util.ParsedPostBlock
import com.example.util.RelevanceTier

private val TealMedium = Color(0xFF00A896)
private val GrayLow = Color(0xFF757582)

@Composable
fun NewMiniPanelComposable(
    viewModel: ModifiedViewModelWithLiveFilter,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // THREE TABS: 0 = "فلتر الصفحة النشطة" (default), 1 = "دفتري المحفوظ", 2 = "بحث يدوي"
    var selectedTab by remember { mutableIntStateOf(0) }

    val liveBlocks by viewModel.liveFilteredBlocks.collectAsStateWithLifecycle()
    val livePageSource by viewModel.livePageSource.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    val savedCards by viewModel.savedCards.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    var isAccessibilityEnabled by remember {
        mutableStateOf(EnhancedAccessibilityService.isServiceEnabled(context))
    }

    // Automatically scan the active screen on initial launch if accessibility is enabled
    LaunchedEffect(Unit) {
        val enabled = EnhancedAccessibilityService.isServiceEnabled(context)
        isAccessibilityEnabled = enabled
        if (enabled) {
            viewModel.scanLiveScreen()
        }
    }

    BoLiteTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClose() }
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* prevent click through */ }
                        .testTag("overlay_lens_panel_card"),
                    shape = RoundedCornerShape(22.dp),
                    color = CardDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Header with Title & Close Button ✕
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "بو لايت",
                                    color = TextWhite,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "— عدسة التصفية",
                                    color = OrangePrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("overlay_lens_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "✕",
                                    tint = TextWhite,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // THREE TABS: "فلتر الصفحة النشطة" | "دفتري المحفوظ" | "بحث يدوي"
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color(0xFF15151C),
                            contentColor = OrangePrimary,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                    color = OrangePrimary,
                                    height = 3.dp
                                )
                            },
                            divider = {
                                HorizontalDivider(color = BorderStroke, thickness = 1.dp)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = {
                                    selectedTab = 0
                                    val enabled = EnhancedAccessibilityService.isServiceEnabled(context)
                                    isAccessibilityEnabled = enabled
                                    if (enabled && liveBlocks.isEmpty()) {
                                        viewModel.scanLiveScreen()
                                    }
                                },
                                text = {
                                    Text(
                                        text = "فلتر الصفحة",
                                        fontSize = 18.sp,
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 0) OrangePrimary else TextMuted
                                    )
                                }
                            )

                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = {
                                    Text(
                                        text = "دفتري المحفوظ",
                                        fontSize = 18.sp,
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 1) OrangePrimary else TextMuted
                                    )
                                }
                            )

                            Tab(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                text = {
                                    Text(
                                        text = "بحث يدوي",
                                        fontSize = 18.sp,
                                        fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 2) OrangePrimary else TextMuted
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // TAB 0: "فلتر الصفحة النشطة" (Live Filter Lens)
                        if (selectedTab == 0) {
                            if (!isAccessibilityEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "لتصفية منشورات واستفسارات فيسبوك وواتساب تلقائياً، يرجى تفعيل خدمة إمكانية الوصول",
                                        color = TextMuted,
                                        fontSize = 18.sp,
                                        lineHeight = 26.sp,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            EnhancedAccessibilityService.openAccessibilitySettings(context)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("open_accessibility_settings_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = OrangePrimary,
                                            contentColor = TextWhite
                                        )
                                    ) {
                                        Text(
                                            text = "افتح إعدادات إمكانية الوصول",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                // Source indicator header + Refresh button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "من صفحة: $livePageSource",
                                            color = OrangePrimary,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (isScanning) "جارٍ فحص الشاشة..." else "المشاركات ذات الصلة: ${liveBlocks.size}",
                                            color = TextMuted,
                                            fontSize = 18.sp
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.scanLiveScreen() },
                                        enabled = !isScanning,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "تحديث التصفية",
                                            tint = OrangePrimary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 140.dp, max = 340.dp)
                                ) {
                                    if (liveBlocks.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (isScanning) "جارٍ قراءة وتصفية الشاشة الحالية..." else "لم يتم العثور على منشورات طلابية في هذه الصفحة",
                                                color = TextMuted,
                                                fontSize = 18.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            items(liveBlocks, key = { it.id }) { block ->
                                                LiveFilteredPostCard(
                                                    block = block,
                                                    onSave = {
                                                        viewModel.saveBlockToNotebook(block) {
                                                            Toast.makeText(context, "تم الحفظ للدفتر", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onOpenSource = {
                                                        openExternalSource(context, block)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // TAB 1: "دفتري المحفوظ" (Saved Room Cards)
                        if (selectedTab == 1) {
                            Text(
                                text = "البطاقات المحفوظة في الدفتر (${savedCards.size}):",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 340.dp)
                            ) {
                                if (savedCards.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "دفترك فارغ حالياً، احفظ المشاركات من فلتر الصفحة",
                                            color = TextMuted,
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(savedCards, key = { it.id }) { item ->
                                            SavedNotebookCardRow(
                                                item = item,
                                                onDelete = { viewModel.deleteSavedCard(item.id) },
                                                onCopy = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Card", item.text))
                                                    Toast.makeText(context, "تم نسخ النص", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // TAB 2: "بحث يدوي" (Manual Search in Room cards)
                        if (selectedTab == 2) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.onSearchQueryChange(it) },
                                placeholder = {
                                    Text(
                                        text = "اسأل: أفضل دكتور للعربي 1؟",
                                        color = TextHint,
                                        fontSize = 18.sp
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_search_input"),
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    color = TextWhite,
                                    textAlign = TextAlign.Start
                                ),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = CardDark,
                                    unfocusedContainerColor = CardDark,
                                    focusedBorderColor = OrangePrimary,
                                    unfocusedBorderColor = BorderStroke,
                                    cursorColor = OrangePrimary
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions.Default
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 90.dp, max = 190.dp)
                            ) {
                                if (searchQuery.isBlank()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "اكتب كلمة للبحث في البطاقات المحفوظة",
                                            color = TextMuted,
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else if (searchResults.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "لا توجد نتائج مطابقة في الدفتر",
                                            color = TextMuted,
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(searchResults, key = { it.id }) { card ->
                                            ManualSearchResultRow(card = card)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = BorderStroke, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(10.dp))

                            // Two action buttons in manual mode:
                            Button(
                                onClick = {
                                    val q = searchQuery.trim()
                                    val url = if (q.isNotEmpty()) "https://www.facebook.com/search/top?q=" + Uri.encode(q) else "https://www.facebook.com"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "تعذر فتح فيسبوك", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("manual_facebook_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GrayButton,
                                    contentColor = TextWhite
                                )
                            ) {
                                Text(
                                    text = "افتح البحث في فيسبوك",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    val textToSave = searchQuery.trim()
                                    if (textToSave.isNotBlank()) {
                                        viewModel.saveQuickCard(textToSave) {
                                            viewModel.onSearchQueryChange("")
                                        }
                                        Toast.makeText(context, "تم حفظ البطاقة بنجاح", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "الرجاء إدخال نص أولاً", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("manual_save_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OrangePrimary,
                                    contentColor = TextWhite
                                )
                            ) {
                                Text(
                                    text = "احفظ كبطاقة جديدة",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveFilteredPostCard(
    block: ParsedPostBlock,
    onSave: () -> Unit,
    onOpenSource: () -> Unit
) {
    // Score highlighting: HIGH = Orange, MEDIUM = Teal, LOW = Gray
    val (borderColor, badgeBg, tierLabel) = when (block.tier) {
        RelevanceTier.HIGH -> Triple(OrangePrimary, OrangePrimary.copy(alpha = 0.2f), "أهمية عالية")
        RelevanceTier.MEDIUM -> Triple(TealMedium, TealMedium.copy(alpha = 0.2f), "أهمية متوسطة")
        RelevanceTier.LOW -> Triple(GrayLow, GrayLow.copy(alpha = 0.15f), "أهمية عادية")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF14141A),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Row: Category Chip + Relevance Tier Badge + Source
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CategoryChip(category = block.category)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = tierLabel,
                            color = borderColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "من صفحة: ${block.sourceName.take(16)}",
                    color = TextMuted,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Post Content Text Preview
            Text(
                text = block.text,
                color = TextWhite,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons: a) "احفظ للدفتر"  b) "افتح المصدر"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OrangePrimary,
                        contentColor = TextWhite
                    )
                ) {
                    Text(
                        text = "احفظ للدفتر",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onOpenSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrayButton,
                        contentColor = TextWhite
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "افتح المصدر",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedNotebookCardRow(
    item: SavedCard,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val title = if (item.text.length > 60) item.text.substring(0, 60) else item.text

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF14141A),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip(category = item.category)

                Row {
                    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = Color(0xFFFF4D4F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            if (item.text.length > 60) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.text,
                    color = TextMuted,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ManualSearchResultRow(card: SavedCard) {
    val title = if (card.text.length > 60) card.text.substring(0, 60) else card.text

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14141A))
            .border(1.dp, BorderStroke, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        CategoryChip(category = card.category)

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = title,
            color = TextWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
    }
}

private fun openExternalSource(context: Context, block: ParsedPostBlock) {
    val targetUrl = block.sourceUrl ?: run {
        val snippet = block.text.take(60).replace("\n", " ").trim()
        "https://www.facebook.com/search/posts/?q=" + Uri.encode(snippet)
    }

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show()
    }
}
