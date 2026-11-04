package com.example.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import com.example.ModifiedViewModelForUnifiedSearch
import com.example.accessibility.EnhancedSearchWithinPageService
import com.example.accessibility.PageSearchResult
import com.example.data.SavedCard
import com.example.ui.theme.BoLiteTheme
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CardDark
import com.example.ui.theme.GrayButton
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.TextHint
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

@Composable
fun SimplifiedMiniPanelComposable(
    viewModel: ModifiedViewModelForUnifiedSearch,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    // TWO TABS: 0 = "البحث في الصفحة" (MODE A - Primary), 1 = "دفتري المحفوظ" (MODE B - Secondary)
    var selectedTab by remember { mutableIntStateOf(0) }

    val pageQuery by viewModel.pageSearchQuery.collectAsStateWithLifecycle()
    val pageResults by viewModel.pageSearchResults.collectAsStateWithLifecycle()
    val pageSourceName by viewModel.pageSourceName.collectAsStateWithLifecycle()
    val isSearchingPage by viewModel.isSearchingPage.collectAsStateWithLifecycle()
    val searchErrorMessage by viewModel.searchErrorMessage.collectAsStateWithLifecycle()

    val savedCards by viewModel.savedCards.collectAsStateWithLifecycle()
    val savedCount by viewModel.savedCount.collectAsStateWithLifecycle()

    var isAccessibilityEnabled by remember {
        mutableStateOf(EnhancedSearchWithinPageService.isServiceEnabled(context))
    }

    // Auto-scan current active screen when panel opens if accessibility is active
    LaunchedEffect(Unit) {
        val enabled = EnhancedSearchWithinPageService.isServiceEnabled(context)
        isAccessibilityEnabled = enabled
        if (enabled) {
            viewModel.searchCurrentPage()
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
                        .testTag("simplified_mini_panel_card"),
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
                                    text = if (selectedTab == 0) "— بحث داخل الصفحة" else "— دفتري المحفوظ",
                                    color = OrangePrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("mini_panel_close_button")
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

                        // TWO TABS ONLY: "البحث في الصفحة" | "دفتري المحفوظ"
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
                                    val enabled = EnhancedSearchWithinPageService.isServiceEnabled(context)
                                    isAccessibilityEnabled = enabled
                                    if (enabled && pageResults.isEmpty()) {
                                        viewModel.searchCurrentPage()
                                    }
                                },
                                text = {
                                    Text(
                                        text = "البحث في الصفحة",
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
                                        text = "دفتري المحفوظ ($savedCount)",
                                        fontSize = 18.sp,
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 1) OrangePrimary else TextMuted
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ==========================================
                        // MODE A: SEARCH WITHIN CURRENT PAGE
                        // ==========================================
                        if (selectedTab == 0) {
                            if (!isAccessibilityEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "للبحث المباشر داخل محتوى هذه الصفحة، يرجى تفعيل خدمة إمكانية الوصول للتطبيق",
                                        color = TextMuted,
                                        fontSize = 18.sp,
                                        lineHeight = 26.sp,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            EnhancedSearchWithinPageService.openAccessibilitySettings(context)
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
                                // Large search box with hint: "ابحث داخل هذه الصفحة..." + Refresh button ↻
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = pageQuery,
                                        onValueChange = { viewModel.onPageQueryChange(it) },
                                        placeholder = {
                                            Text(
                                                text = "ابحث داخل هذه الصفحة...",
                                                color = TextHint,
                                                fontSize = 18.sp
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("page_search_input"),
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
                                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchCurrentPage() })
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    IconButton(
                                        onClick = { viewModel.searchCurrentPage() },
                                        enabled = !isSearchingPage,
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF15151C))
                                            .testTag("refresh_page_search_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "↻ إعادة المسح",
                                            tint = OrangePrimary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Source indicator
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "من صفحة: $pageSourceName",
                                        color = OrangePrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Text(
                                        text = if (isSearchingPage) "جارٍ البحث..." else "النتائج: ${pageResults.size}",
                                        color = TextMuted,
                                        fontSize = 18.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Matching snippets list
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 140.dp, max = 340.dp)
                                ) {
                                    if (pageResults.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (isSearchingPage) "جارٍ قراءة الشاشة..."
                                                else searchErrorMessage
                                                    ?: if (pageQuery.isNotBlank()) "لا توجد نتائج مطابقة لـ '$pageQuery' في هذه الصفحة"
                                                    else "اكتب كلمة (دكتور، مادة، وظيفة...) أو اضغط ↻ لإعادة المسح",
                                                color = TextMuted,
                                                fontSize = 18.sp,
                                                lineHeight = 24.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                        ) {
                                            items(pageResults, key = { it.id }) { result ->
                                                PageSearchResultCard(
                                                    result = result,
                                                    onSave = {
                                                        viewModel.saveSearchResultToNotebook(result) {
                                                            Toast.makeText(context, "تم الحفظ للدفتر", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    onOpenSource = {
                                                        openExternalLink(context, result.sourceUrl, result.text)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // MODE B: MY NOTEBOOK (دفتري المحفوظ)
                        // ==========================================
                        if (selectedTab == 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 360.dp)
                            ) {
                                if (savedCards.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "دفترك فارغ حالياً، احفظ ما يهمك أثناء تصفح الصفحات",
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
                    }
                }
            }
        }
    }
}

@Composable
private fun PageSearchResultCard(
    result: PageSearchResult,
    onSave: () -> Unit,
    onOpenSource: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF14141A),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, OrangePrimary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Category Chip + Source Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip(category = result.category)

                Text(
                    text = "من صفحة: ${result.sourceName.take(18)}",
                    color = TextMuted,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Snippet preview
            Text(
                text = result.text,
                color = TextWhite,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Two Action Buttons: a) "احفظ للدفتر"  b) "افتح المصدر"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("save_to_notebook_button"),
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
                        .height(46.dp)
                        .testTag("open_source_button"),
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

private fun openExternalLink(context: Context, url: String?, text: String) {
    val targetUrl = url ?: run {
        val snippet = text.take(60).replace("\n", " ").trim()
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
