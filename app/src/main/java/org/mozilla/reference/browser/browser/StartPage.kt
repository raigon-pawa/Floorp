/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.reference.browser.R

/** The native start page shown over the engine view: when it shows, and its site tiles. */
object StartPage {
    /**
     * What a start-page tab really holds: an empty dark document, which makes no request and stays out of the history
     * list. Not about:blank: a new tab's first about:blank (with any query or fragment) stays its initial document, and
     * a navigation away from that replaces it, so Back from a site opened on the start page would have nowhere to go.
     * Pages cannot navigate to data: URLs, and only this exact one counts.
     */
    const val URL = "data:text/html,%3Cmeta%20name=color-scheme%20content=dark%3E"

    private const val ABOUT_BLANK = "about:blank"

    /**
     * True for a tab that shows the start page: a normal tab at the start page's blank page or at plain about:blank,
     * unless a page opened it (window.open: the opener can write into it). Still true while such a tab starts loading a
     * site, so the page stays up with the toolbar's progress until the site replaces it. Private tabs have their own
     * page.
     */
    fun isStartTab(tab: TabSessionState): Boolean =
        !tab.content.private && tab.parentId == null && (tab.content.url == URL || tab.content.url == ABOUT_BLANK)

    /**
     * True when the start page covers the engine view: the selected tab is a start-page tab, or no tab is selected once
     * restore has finished (before that, a restored page would flash it) and no normal tab is left (otherwise one is
     * about to be selected, as when the last private tab closes).
     */
    fun isShown(state: BrowserState): Boolean =
        state.selectedTab?.let(::isStartTab) ?: (state.restoreComplete && state.normalTabs.isEmpty())

    internal class Tile(@param:StringRes val label: Int, @param:DrawableRes val icon: Int, val url: String)

    internal val tiles =
        listOf(
            Tile(R.string.start_page_tile_archwiki, R.drawable.archwave_tile_archwiki, "https://wiki.archlinux.org"),
            Tile(R.string.start_page_tile_wikipedia, R.drawable.archwave_tile_wikipedia, "https://www.wikipedia.org"),
            Tile(R.string.start_page_tile_github, R.drawable.archwave_tile_github, "https://github.com"),
            Tile(R.string.start_page_tile_duckduckgo, R.drawable.archwave_tile_duckduckgo, "https://duckduckgo.com"),
        )
}

private val ContentWidth = 480.dp

/** Black, the Archwave mark, a search box that hands over to the address bar, and the site tiles. */
@Composable
internal fun StartPageContent(onSearch: () -> Unit, onOpen: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(colorResource(R.color.aw_black))) {
        val columns = tileColumns(maxWidth)
        // Centred when it fits; scrolls in landscape or with a large font.
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                // Narrow sides, so "DuckDuckGo" still fits its quarter at a 1.3 font scale.
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The launcher foreground: the mark fills its middle 60%.
            Image(painterResource(R.drawable.ic_icon_foreground), contentDescription = null, Modifier.size(104.dp))
            Spacer(Modifier.height(8.dp))
            SearchBox(onSearch)
            Spacer(Modifier.height(24.dp))
            StartPage.tiles.chunked(columns).forEach { row ->
                Row(Modifier.widthIn(max = ContentWidth).fillMaxWidth()) {
                    row.forEach { tile -> Tile(tile, onOpen, Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val LabelSize = 12.sp

/** Four tiles in a row, or two rows of two when the widest label would not fit a quarter (large font sizes). */
@Composable
private fun tileColumns(maxWidth: Dp): Int {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.copy(fontSize = LabelSize)
    val labels = StartPage.tiles.map { stringResource(it.label) }
    val widest = with(LocalDensity.current) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() }
    // The column's side padding is 8dp each.
    return if (widest <= minOf(maxWidth - 16.dp, ContentWidth) / 4) 4 else 2
}

/** Looks like the address bar's box; a tap puts the address bar itself into edit mode. */
@Composable
private fun SearchBox(onSearch: () -> Unit) {
    val hint = colorResource(R.color.aw_text_hint)
    Row(
        Modifier.padding(horizontal = 8.dp)
            .widthIn(max = ContentWidth)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colorResource(R.color.toolbarUrlBoxColor))
            .clickable(role = Role.Button, onClick = onSearch)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconsR.drawable.mozac_ic_search_24), contentDescription = null, tint = hint)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.toolbar_hint), color = hint, fontSize = 15.sp)
    }
}

@Composable
private fun Tile(tile: StartPage.Tile, onOpen: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onOpen(tile.url) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.aw_surface_2))
                .border(1.dp, colorResource(R.color.aw_divider), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(tile.icon),
                contentDescription = null,
                tint = colorResource(R.color.aw_text_1),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // The label also names the tile for TalkBack: the tile merges what it holds.
        Text(
            stringResource(tile.label),
            color = colorResource(R.color.aw_text_2),
            fontSize = LabelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
