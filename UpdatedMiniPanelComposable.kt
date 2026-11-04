package com.example.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.data.ArchivedPost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.example.NewDeepScanViewModel
import com.example.PageScanState
import com.example.data.PostData
import com.example.data.SavedCard
import com.example.ui.theme.BoLiteTheme
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CardDark
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.TextHint
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import com.example.util.FuzzySearchUtility

@Composable
fun UpdatedMiniPanelComposable(
    viewModel: NewDeepScanViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val detectedGroupName by viewModel.detectedGroupName.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filteredPosts by viewModel.filteredPosts.collectAsStateWithLifecycle()
    val rawPosts by viewModel.rawPosts.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val scanProgressText by viewModel.scanProgressText.collectAsStateWithLifecycle()

    val savedCards by viewModel.savedCards.collectAsStateWithLifecycle()
    val savedCount by viewModel.savedCount.collectAsStateWithLifecycle()
    val isSavedNotebookSheetOpen by viewModel.isSavedNotebookSheetOpen.collectAsStateWithLifecycle()
    val watchKeywords by viewModel.watchKeywords.collectAsStateWithLifecycle()
    val isWatchListSheetOpen by viewModel.isWatchListSheetOpen.collectAsStateWithLifecycle()
    val selectedPostForDetail by viewModel.selectedPostForDetail.collectAsStateWithLifecycle()
    val isFacebookGroupActive by viewModel.isFacebookGroupActive.collectAsStateWithLifecycle()
    val isSimulatedFacebookGroup by viewModel.isSimulatedFacebookGroup.collectAsStateWithLifecycle()
    val scanSessionId by viewModel.scanSessionId.collectAsStateWithLifecycle()

    BoLiteTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClose() }
                    .padding(horizontal = 12.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* prevent backdrop dismissal */ }
                        .testTag("simplified_lens_card"),
                    shape = RoundedCornerShape(22.dp),
                    color = CardDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            // Header: App Title, Detected Source, Close ✕
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "بو لايت",
                                            color = TextWhite,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (scanState is PageScanState.Loaded) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "— خريطة المحتوى",
                                                color = OrangePrimary,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    if (detectedGroupName.isNotBlank()) {
                                        Text(
                                            text = "📍 $detectedGroupName",
                                            color = TextMuted,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.openWatchListSheet() },
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("open_watchlist_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.NotificationsActive,
                                            contentDescription = "كلمات المراقبة والإشعارات",
                                            tint = if (watchKeywords.isNotEmpty()) OrangePrimary else TextMuted,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    if (savedCount > 0) {
                                        IconButton(
                                            onClick = { viewModel.openSavedNotebookSheet() },
                                            modifier = Modifier
                                                .size(38.dp)
                                                .testTag("open_saved_notebook_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Bookmark,
                                                contentDescription = "دفتري المحفوظ",
                                                tint = OrangePrimary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = onClose,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .testTag("mini_panel_close_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "✕ إغلاق",
                                            tint = TextWhite,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            when (scanState) {
                                // ==========================================
                                // STEP 1: THE TRIGGER BUTTON
                                // ONE prominent button labeled: "ابدأ البحث في هذه الصفحة"
                                // No other visible controls initially. Clean interface.
                                // ==========================================
                                is PageScanState.Idle -> {
                                    if (!isFacebookGroupActive) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 16.dp, horizontal = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = OrangePrimary.copy(alpha = 0.15f),
                                                modifier = Modifier.size(56.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.Groups,
                                                        contentDescription = null,
                                                        tint = OrangePrimary,
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Text(
                                                text = "يرجى فتح مجموعة فيسبوك أولاً",
                                                color = TextWhite,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "الميزة مخصصة لمجموعات فيسبوك فقط (Facebook Groups). يتم تجاهل الصفحات والريلز وموجز الأخبار والماركت بليس.",
                                                color = TextMuted,
                                                fontSize = 15.sp,
                                                lineHeight = 22.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                            )
                                            Spacer(modifier = Modifier.height(18.dp))
                                            Button(
                                                onClick = { /* Disabled */ },
                                                enabled = false,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(60.dp)
                                                    .testTag("start_page_search_button_disabled"),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    disabledContainerColor = Color(0xFF282836),
                                                    disabledContentColor = TextMuted
                                                )
                                            ) {
                                                Text(
                                                    text = "ابدأ البحث في هذه الصفحة",
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                            OutlinedButton(
                                                onClick = { viewModel.toggleFacebookGroupSimulation() },
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                                            ) {
                                                Text(
                                                    text = "تجربة محاكاة مجموعة فيسبوك",
                                                    color = OrangePrimary,
                                                    fontSize = 14.sp
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 24.dp, horizontal = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = Color(0xFF1E1E2C),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                                                modifier = Modifier.padding(bottom = 16.dp)
                                            ) {
                                                Text(
                                                    text = "📍 $detectedGroupName • آخر 7 أيام",
                                                    color = OrangePrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }

                                            Text(
                                                text = "جاهز لقراءة منشورات المجموعة خلال آخر 7 أيام فوراً والبحث المباشر بها",
                                                color = TextMuted,
                                                fontSize = 16.sp,
                                                lineHeight = 24.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(bottom = 24.dp)
                                            )

                                            Button(
                                                onClick = { viewModel.startPageSearch() },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(64.dp)
                                                    .testTag("start_page_search_button"),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = OrangePrimary,
                                                    contentColor = TextWhite
                                                )
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Search,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(26.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Text(
                                                        text = "ابدأ البحث في هذه الصفحة",
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // ==========================================
                                // STEP 2: THE SCAN & LOAD
                                // Simple loading spinner for max 2-3 seconds
                                // ==========================================
                                is PageScanState.Loading -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 36.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .testTag("scan_loading_indicator"),
                                            color = OrangePrimary,
                                            strokeWidth = 4.dp
                                        )
                                        Spacer(modifier = Modifier.height(20.dp))
                                        Text(
                                            text = scanProgressText.ifBlank { "Snapshot 1/8 | Saved: 0" },
                                            color = TextWhite,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "فحص سريع متعدد اللقطات وحفظ فوري في قاعدة البيانات",
                                            color = TextMuted,
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                // ==========================================
                                // STEP 3: DISPLAY THE MAP
                                // STEP 4: INSTANT CLIENT-SIDE FILTERING
                                // Search Input Field at top, scrollable list below.
                                // ==========================================
                                is PageScanState.Loaded -> {
                                    // Fixed Group Name Header at the very top of the result list view with 7-day badge
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 10.dp)
                                            .testTag("group_name_fixed_header"),
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF1E1E28),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = "📍 ${if (detectedGroupName.isNotBlank()) detectedGroupName else "مجموعة فيسبوك"} • أرشيف محلي",
                                                    color = OrangePrimary,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = "${filteredPosts.size} منشور محفوظ",
                                                color = TextMuted,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }

                                    // STEP 4: Search Input Field
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.onSearchQueryChange(it) },
                                        placeholder = {
                                            Text(
                                                text = "ابحث في أرشيف المجموعة الكامل...",
                                                color = TextHint,
                                                fontSize = 17.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "بحث",
                                                tint = OrangePrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (searchQuery.isNotEmpty()) {
                                                    IconButton(
                                                        onClick = { viewModel.onSearchQueryChange("") },
                                                        modifier = Modifier.size(34.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Clear,
                                                            contentDescription = "مسح",
                                                            tint = TextMuted,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                IconButton(
                                                    onClick = { viewModel.startPageSearch() },
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .testTag("refresh_scan_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "↻ إعادة المسح",
                                                        tint = OrangePrimary,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("page_search_input_field"),
                                        textStyle = TextStyle(
                                            fontSize = 18.sp,
                                            color = TextWhite,
                                            textAlign = TextAlign.Start
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color(0xFF14141A),
                                            unfocusedContainerColor = Color(0xFF14141A),
                                            focusedBorderColor = OrangePrimary,
                                            unfocusedBorderColor = BorderStroke,
                                            cursorColor = OrangePrimary
                                        ),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { /* in-memory filtered */ })
                                    )

                                    // Results info bar
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp, bottom = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (searchQuery.isNotBlank()) {
                                                "النتائج المطابقة: ${filteredPosts.size} من ${rawPosts.size}"
                                            } else {
                                                "خريطة المنشورات (${filteredPosts.size} منشور)"
                                            },
                                            color = OrangePrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )

                                        Text(
                                            text = "مسح جديد ↻",
                                            color = TextMuted,
                                            fontSize = 14.sp,
                                            modifier = Modifier
                                                .clickable { viewModel.startPageSearch() }
                                                .padding(4.dp)
                                        )
                                    }

                                    // STEP 3: Scrollable list showing EVERY post found
                                    // Each item shows: Snippet Text + Author/Time (if detected).
                                    // No chips, no tags, no extra buttons.
                                    if (filteredPosts.isEmpty()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 180.dp)
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = statusMessage ?: "No new content found. Try scrolling manually first.",
                                                color = TextWhite,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "لم يتم العثور على محتوى جديد. حاول التمرير يدوياً أولاً",
                                                color = OrangePrimary,
                                                fontSize = 14.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 440.dp)
                                                .testTag("content_map_posts_list"),
                                            verticalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
                                        ) {
                                            items(
                                                items = filteredPosts,
                                                key = { "${it.id}_$scanSessionId" }
                                            ) { post ->
                                                AnimatedVisibility(
                                                    visible = true,
                                                    enter = fadeIn(animationSpec = tween(350)) +
                                                            slideInVertically(
                                                                animationSpec = tween(350),
                                                                initialOffsetY = { 30 }
                                                            )
                                                ) {
                                                    SimplePostItemCard(
                                                        post = post,
                                                        onPostClick = { viewModel.selectPostForDetail(post) },
                                                        onSaveToNotebook = {
                                                            viewModel.savePostToNotebook(post) {
                                                                Toast.makeText(context, "تم الحفظ للدفتر ✓", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        onCopy = {
                                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            clipboard.setPrimaryClip(ClipData.newPlainText("BoLite Post", post.fullText))
                                                            Toast.makeText(context, "تم النسخ ✓", Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Saved Notebook Sheet View (Secondary overlay if opened)
                        if (isSavedNotebookSheetOpen) {
                            SavedNotebookOverlayView(
                                savedCards = savedCards,
                                onClose = { viewModel.closeSavedNotebookSheet() },
                                onDelete = { viewModel.deleteSavedCard(it) }
                            )
                        }

                        // DETAILED POST VIEW ON TAP: Modal bottom sheet / dialog titled "تفاصيل المنشور"
                        if (selectedPostForDetail != null) {
                            PostDetailModalOverlay(
                                post = selectedPostForDetail!!,
                                onClose = { viewModel.closePostDetail() },
                                onSaveToNotebook = {
                                    viewModel.savePostToNotebook(selectedPostForDetail!!) {
                                        Toast.makeText(context, "تم الحفظ للدفتر ✓", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onCopy = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("BoLite Post Detail", selectedPostForDetail!!.fullText))
                                    Toast.makeText(context, "تم نسخ النص الكامل ✓", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // Watchlist Management Dialog (for notifications on keywords)
                        if (isWatchListSheetOpen) {
                            com.example.ui.WatchListDialog(
                                keywords = watchKeywords,
                                onAddKeyword = { viewModel.addWatchKeyword(it) },
                                onRemoveKeyword = { viewModel.removeWatchKeyword(it) },
                                onDismiss = { viewModel.closeWatchListSheet() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * STEP 3: Structured PostCard Composable
 * Replaces raw text dumps with structured Card components:
 * - Header Row: Circular avatar placeholder (40.dp), bold authorName (16.sp), #categoryTag in gray (12.sp) + timestamp
 * - Divider with clean vertical spacing (16.dp)
 * - Body Section: Readable content ONLY (post.postContent.take(300)), maxLines 4, lineHeight 24.sp
 * - Footer Actions: Copy button aligned right, Bookmark button aligned left
 * - RTL Support: Force CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)
 */
@Composable
fun PostCard(
    post: PostData,
    modifier: Modifier = Modifier,
    onPostClick: () -> Unit = {},
    onSaveToNotebook: () -> Unit = {},
    onCopy: () -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val confidenceBorder = when {
            post.ocrConfidence >= 0.90f -> Color(0xFF4CAF50).copy(alpha = 0.50f)
            post.ocrConfidence >= 0.70f -> OrangePrimary.copy(alpha = 0.50f)
            else -> BorderStroke
        }

        val resolvedTag = com.example.util.PostSanitizer.resolveCleanTag(post.postContent, post.categoryTag)

        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .testTag("post_card_${post.id}")
                .clickable { onPostClick() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
            border = androidx.compose.foundation.BorderStroke(1.dp, confidenceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // ==========================================
                // 1. HEADER ROW: Distinct visual separation
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular Avatar Placeholder (size = 40.dp)
                    Surface(
                        shape = CircleShape,
                        color = OrangePrimary.copy(alpha = 0.22f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OrangePrimary.copy(alpha = 0.45f)),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val initial = post.authorName.trim().firstOrNull()?.toString() ?: "👤"
                            Text(
                                text = initial,
                                color = OrangePrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = post.authorName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Hide tag completely if empty/null/default until a valid classification is made
                        if (resolvedTag != null || !post.timestampRaw.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (resolvedTag != null) {
                                    Text(
                                        text = resolvedTag,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!post.timestampRaw.isNullOrBlank()) {
                                        Text(text = "•", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }

                                if (!post.timestampRaw.isNullOrBlank()) {
                                    Text(
                                        text = post.timestampRaw!!,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (post.isMediaRich) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF2C2C3C)
                        ) {
                            Text(
                                text = "📸 صورة",
                                color = OrangePrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    color = BorderStroke.copy(alpha = 0.5f),
                    thickness = 0.8.dp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))

                // ==========================================
                // 2. BODY SECTION: Readable content ONLY or Fallback
                // ==========================================
                val isContentValid = com.example.util.PostSanitizer.isValidPostContent(post.postContent)

                if (!isContentValid) {
                    Text(
                        text = "Content could not be read clearly",
                        color = Color(0xFFB0B0B0),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                } else {
                    val cleanContent = post.postContent
                    val isLong = cleanContent.length > 180 || cleanContent.lines().size > 3
                    val previewText = if (isExpanded) cleanContent else cleanContent.take(280)

                    val annotatedText = FuzzySearchUtility.buildHighlightedAnnotatedString(
                        originalText = previewText,
                        matchedRanges = post.matchedRanges,
                        highlightColor = OrangePrimary
                    )

                    // Body Section: MaxHeight constrained showing only clean postContent; truncated after 3 lines
                    Text(
                        text = annotatedText,
                        color = TextWhite,
                        fontSize = 15.sp,
                        lineHeight = 24.sp,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isExpanded) 1000.dp else 95.dp)
                    )

                    if (isLong) {
                        Text(
                            text = if (isExpanded) "عرض أقل ▲" else "...عرض المزيد / Read More ▼",
                            color = OrangePrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { isExpanded = !isExpanded }
                                .padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ==========================================
                // 3. FOOTER ACTIONS: Minimalist icons
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Right / Start in RTL: Copy button icon
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("copy_post_icon_${post.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ النص",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Left / End in RTL: Bookmark button icon
                    IconButton(
                        onClick = onSaveToNotebook,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("save_to_notebook_icon_${post.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = "احفظ للدفتر",
                            tint = Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Overload of PostCard accepting ArchivedPost directly.
 */
@Composable
fun PostCard(
    post: ArchivedPost,
    modifier: Modifier = Modifier,
    onPostClick: () -> Unit = {},
    onSaveToNotebook: () -> Unit = {},
    onCopy: () -> Unit = {}
) {
    PostCard(
        post = post.toPostData(),
        modifier = modifier,
        onPostClick = onPostClick,
        onSaveToNotebook = onSaveToNotebook,
        onCopy = onCopy
    )
}

@Composable
private fun SimplePostItemCard(
    post: PostData,
    onPostClick: () -> Unit,
    onSaveToNotebook: () -> Unit,
    onCopy: () -> Unit
) {
    PostCard(
        post = post,
        onPostClick = onPostClick,
        onSaveToNotebook = onSaveToNotebook,
        onCopy = onCopy
    )
}

/**
 * Requirement 3: DETAILED POST VIEW ON TAP
 * Modal sheet titled "تفاصيل المنشور" displaying full text, comments, author, timestamp, and close button.
 */
@Composable
private fun PostDetailModalOverlay(
    post: PostData,
    onClose: () -> Unit,
    onSaveToNotebook: () -> Unit,
    onCopy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClose() }
            .testTag("post_detail_modal_backdrop"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* prevent dismissal on dialog click */ }
                .testTag("post_detail_modal_sheet"),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF14141E),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BorderStroke)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Title Bar with Close Button "✕"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = OrangePrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = OrangePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "تفاصيل المنشور",
                            color = TextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("close_post_detail_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "✕ إغلاق",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Post Author & Exact Timestamp Bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E2C),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "👤 ${post.author ?: "عضو في المجموعة"}",
                                color = OrangePrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = OrangePrimary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = post.category,
                                    color = OrangePrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🕒 ${post.timestamp ?: "حديث"} • آخر 7 أيام",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            if (post.groupName.isNotBlank()) {
                                Text(
                                    text = "  |  📍 ${post.groupName}",
                                    color = TextHint,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Full Post Text & Comments List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF191924),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "نص المنشور الكامل:",
                                    color = TextHint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = post.fullText,
                                    color = TextWhite,
                                    fontSize = 16.sp,
                                    lineHeight = 25.sp,
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }

                    // Comments Section
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = OrangePrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "التعليقات والردود (${post.comments.size})",
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (post.comments.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1A1A26),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "لا توجد ردود إضافية مرئية لهذا المنشور في الشاشة",
                                    color = TextMuted,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (comment in post.comments) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF1E1E2C),
                                        border = androidx.compose.foundation.BorderStroke(0.8.dp, BorderStroke),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "👤 ${comment.author}",
                                                    color = OrangePrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (!comment.timestamp.isNullOrBlank()) {
                                                    Text(
                                                        text = comment.timestamp,
                                                        color = TextHint,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = comment.text,
                                                color = TextWhite,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onSaveToNotebook,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OrangePrimary,
                            contentColor = TextWhite
                        ),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("احفظ للدفتر", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onCopy,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = TextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("نسخ النص", color = TextWhite, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedNotebookOverlayView(
    savedCards: List<SavedCard>,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("saved_notebook_overlay"),
        color = CardDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "دفتري المحفوظ (${savedCards.size})",
                    color = TextWhite,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = TextWhite,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (savedCards.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد منشورات محفوظة بعد",
                        color = TextMuted,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = savedCards, key = { it.id }) { card ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1B1B24),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = card.text,
                                    color = TextWhite,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = card.category,
                                        color = OrangePrimary,
                                        fontSize = 13.sp
                                    )
                                    Row {
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("BoLite Note", card.text))
                                                Toast.makeText(context, "تم النسخ ✓", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "نسخ",
                                                tint = TextMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDelete(card.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "حذف",
                                                tint = TextMuted,
                                                modifier = Modifier.size(16.dp)
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
