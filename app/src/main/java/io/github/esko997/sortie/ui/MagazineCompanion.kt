package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.data.magazine.MagazineIssue
import io.github.esko997.sortie.data.magazine.ReaderSection

/** No Activity, callback or list state crosses displays. The owner rejects taps left over from a screen swap. */
internal data class MagazineCompanionState(
    val owner: Any,
    val issue: MagazineIssue,
    val articleId: String?,
    val sections: List<ReaderSection> = emptyList(),
    val activeSection: Int = 0
)

internal sealed interface MagazineAction {
    val owner: Any
    val issueId: String

    data class OpenArticle(override val owner: Any, override val issueId: String, val articleId: String) : MagazineAction

    data class Jump(override val owner: Any, override val issueId: String, val articleId: String, val itemIndex: Int) : MagazineAction
}

/**
 * Shows [owner]'s reader beside it on the other screen while its window is started ([Selection.publishMagazine]). Lifecycle
 * events, not recomposition, publish and withdraw: a stopped window draws no frames, so a composition-only side effect would
 * leave a hidden reader on the companion. The owning MagazineScreen withdraws when it leaves composition.
 */
@Composable
internal fun PublishMagazineCompanion(
    owner: Any,
    issue: MagazineIssue,
    articleId: String?,
    sections: List<ReaderSection> = emptyList(),
    activeSection: Int = 0
) {
    val panel = remember(owner, issue, articleId, sections, activeSection) {
        MagazineCompanionState(owner, issue, articleId, sections, activeSection)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(panel)
    DisposableEffect(owner, lifecycle) {
        // added while started, the observer receives ON_START at once
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> Selection.publishMagazine(latest)
                Lifecycle.Event.ON_STOP -> Selection.withdrawMagazine(owner)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Selection.publishMagazine(panel) }
}

/**
 * A quiet contents desk beside the article; touch returns the controller to the reading screen through CompanionHost. [leading]
 * sits bottom-left in its own footer slot, ahead of Previous/Next, so it never covers them or the list.
 */
@Composable
internal fun MagazineCompanion(panel: MagazineCompanionState, leading: @Composable () -> Unit) {
    val issue = panel.issue
    val article = issue.article(panel.articleId)
    val articleIndex = issue.articles.indexOfFirst { it.id == panel.articleId }
    var articles by rememberSaveable(issue.id, panel.articleId) { mutableStateOf(article == null) }
    val sectionsState = rememberLazyListState()
    val articlesState = rememberLazyListState()
    var followedSection by rememberSaveable(issue.id, panel.articleId) { mutableIntStateOf(-1) }
    var followedArticles by rememberSaveable(issue.id, panel.articleId) { mutableStateOf(articles) }
    fun open(id: String) {
        Selection.magazineActions.tryEmit(MagazineAction.OpenArticle(panel.owner, issue.id, id))
    }
    LaunchedEffect(panel.owner, panel.articleId, panel.activeSection, articles) {
        // Returning from the app picker restores the user's list position; only a changed reading target or tab recentres it.
        if (followedSection == panel.activeSection && followedArticles == articles) return@LaunchedEffect
        val state = if (articles) articlesState else sectionsState
        val index = if (articles) articleIndex.coerceAtLeast(0) else panel.activeSection
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
        followedSection = panel.activeSection
        followedArticles = articles
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            issue.title,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            color = Amber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            article?.title ?: issue.subtitle.ifEmpty { "Choose an article" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (article != null) ContentsTab("Sections", !articles, Modifier.weight(1f)) { articles = false }
            ContentsTab("Articles", articles, Modifier.weight(1f)) { articles = true }
        }
        if (articles) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = articlesState, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(issue.articles, key = { _, item -> item.id }) { index, item ->
                    ContentsLink("${index + 1}. ${item.title}", item.id == panel.articleId) { open(item.id) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = sectionsState, contentPadding = PaddingValues(vertical = 8.dp)) {
                if (panel.sections.isEmpty()) item { Text("Opening article…", modifier = Modifier.padding(12.dp)) }
                itemsIndexed(panel.sections, key = { _, section -> section.itemIndex }) { index, section ->
                    ContentsLink(section.title, index == panel.activeSection, indent = if (section.level >= 3) 12 else 0) {
                        Selection.magazineActions.tryEmit(MagazineAction.Jump(panel.owner, issue.id, panel.articleId!!, section.itemIndex))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            if (article != null) {
                val previous = issue.articles.getOrNull(articleIndex - 1)
                val next = issue.articles.getOrNull(articleIndex + 1)
                Box(
                    Modifier.weight(0.32f).heightIn(min = 68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(interactionSource = null, indication = PressVeil, enabled = previous != null, role = Role.Button) {
                            previous?.let { open(it.id) }
                        }
                        .semantics { contentDescription = "Previous article" }
                        .padding(horizontal = 6.dp, vertical = 18.dp)
                ) {
                    Text("Previous", color = if (previous == null) Spent else Amber, style = MaterialTheme.typography.labelLarge)
                }
                Box(
                    Modifier.weight(0.68f).heightIn(min = 68.dp)
                        .drawWithCache {
                            val fold = 18.dp.toPx()
                            val page = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(size.width, 0f)
                                lineTo(size.width, size.height - fold)
                                lineTo(size.width - fold, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            val corner = Path().apply {
                                moveTo(size.width - fold, size.height)
                                lineTo(size.width - fold, size.height - fold)
                                lineTo(size.width, size.height - fold)
                                close()
                            }
                            onDrawBehind {
                                drawPath(page, Slate)
                                drawPath(corner, if (next == null) Hairline else Amber)
                            }
                        }
                        .clickable(interactionSource = null, indication = PressVeil, enabled = next != null, role = Role.Button) { next?.let { open(it.id) } }
                        .semantics { contentDescription = if (next == null) "End of issue" else "Next article: ${next.title}" }
                        .padding(start = 14.dp, top = 12.dp, end = 30.dp, bottom = 14.dp)
                ) {
                    Column {
                        Text(
                            if (next == null) "End of issue" else "Next article",
                            color = if (next == null) Spent else Amber,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (next != null) {
                            Text(
                                next.gameTitle.ifEmpty { next.title },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A finished control: no previous article, the end of the issue. */
private val Spent = Color(0xFF5C6570)

/** One half of the Sections/Articles switch: the active half is lifted onto Slate with an amber edge. */
@Composable
private fun ContentsTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        label,
        color = if (active) Amber else Color(0xFF9AA3AD),
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(shape)
            .background(if (active) Slate else Color.Transparent)
            .border(1.dp, if (active) Amber.copy(alpha = 0.45f) else Hairline, shape)
            .clickable(interactionSource = null, indication = PressVeil, role = Role.Tab, onClick = onClick)
            .semantics { selected = active }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

/** A section or article; the one being read sits on Slate behind an amber margin rule, like a highlighted passage. */
@Composable
private fun ContentsLink(title: String, active: Boolean, indent: Int = 0, onClick: () -> Unit) {
    Text(
        title,
        color = if (active) Amber else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (active) {
                    Modifier.background(Slate).drawBehind { drawRect(Amber, size = Size(3.dp.toPx(), size.height)) }
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = null, indication = PressVeil, role = Role.Button, onClick = onClick)
            .semantics { selected = active }
            .padding(start = (12 + indent).dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
    )
}
