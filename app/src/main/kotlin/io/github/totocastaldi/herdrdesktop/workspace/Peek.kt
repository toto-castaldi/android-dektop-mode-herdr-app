package io.github.totocastaldi.herdrdesktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.totocastaldi.herdrdesktop.R
import io.github.totocastaldi.herdrdesktop.agents.PeekRequest
import io.github.totocastaldi.herdrdesktop.terminal.TerminalView
import kotlinx.coroutines.launch

/** Space left around the peek, so that the tab under it stays recognizable. */
private val PEEK_MARGIN = 24.dp

/**
 * An agent without a pane in peek over the current tab (REQ-AGT-002): an interactive terminal attached to
 * [request]'s address. Closing it only detaches this viewer, the terminal keeps running on the core and the tab
 * under it is untouched; [onKeep] (none without a space) keeps it as a new tab of the current space.
 */
@Composable
internal fun Peek(request: PeekRequest, host: PaneHost?, onKeep: (() -> Unit)?, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .padding(PEEK_MARGIN)
    ) {
        Surface(
            Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.primary).testTag("peek"),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                PeekHeader(request, onKeep, onClose)
                HorizontalDivider()
                if (host == null) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                } else {
                    PeekTerminal(request, host, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PeekHeader(request: PeekRequest, onKeep: (() -> Unit)?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.peek_title, request.name, request.address),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { onKeep?.invoke() },
            enabled = onKeep != null,
            modifier = Modifier.testTag("peek-keep")
        ) { Text(stringResource(R.string.peek_keep)) }
        TextButton(onClick = onClose, modifier = Modifier.testTag("peek-close")) {
            Text(stringResource(R.string.peek_close))
        }
    }
}

@Composable
private fun PeekTerminal(request: PeekRequest, host: PaneHost, modifier: Modifier) {
    // One viewer per request: selecting the agent again attaches again.
    val terminal = remember(request, host) { PaneTerminal(host.rpc, request.address, host.scope) }
    DisposableEffect(terminal) { onDispose { host.scope.launch { terminal.close() } } }
    val status by terminal.state.collectAsState()
    val requester = remember { FocusRequester() }
    LaunchedEffect(terminal) { runCatching { requester.requestFocus() } }
    var menu by remember(terminal) { mutableStateOf<Offset?>(null) }
    Box(modifier) {
        TerminalView(
            terminal.session,
            Modifier.fillMaxSize().testTag("peek-terminal"),
            focusRequester = requester,
            onFocusChanged = { terminal.focus(it) },
            onContextMenu = { menu = it },
            frameObserver = host.frameObserver
        )
        TerminalContextMenu(terminal.session, menu, { menu = null })
        terminalMessage(status)?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp)
            )
        }
    }
}

private const val SCRIM_ALPHA = 0.4f
