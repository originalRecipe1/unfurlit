package io.github.originalrecipe1.unfurlit.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.originalrecipe1.unfurlit.domain.model.ExtractionError
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.domain.model.canRetry
import io.github.originalrecipe1.unfurlit.ui.components.PredictiveBackSurface
import io.github.originalrecipe1.unfurlit.ui.components.UnfurlitTopAppBar

@Composable
fun ViewerRoute(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onShowHistory: () -> Unit,
    backPreview: @Composable () -> Unit = {},
    backEnabled: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val sourceUrl = (state as? ViewerState.Ready)?.extraction?.sourceUrl
    LaunchedEffect(sourceUrl) {
        fullscreen = false
    }
    PredictiveBackSurface(
        dismissOnBack = !fullscreen,
        enabled = backEnabled,
        onBack = {
            if (fullscreen) fullscreen = false else onBack()
        },
        // Fullscreen Back stays in the viewer; it must not preview Home.
        preview = { if (!fullscreen) backPreview() },
    ) {
        ViewerScreen(
            state = state,
            onRetry = viewModel::retry,
            onViewed = viewModel::recordView,
            onBack = onBack,
            onShowHistory = onShowHistory,
            fullscreen = fullscreen,
            onFullscreenChange = { fullscreen = it },
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ViewerScreen(
    state: ViewerState,
    onRetry: () -> Unit,
    onViewed: (io.github.originalrecipe1.unfurlit.domain.model.ExtractionResult) -> Unit,
    onBack: () -> Unit,
    onShowHistory: () -> Unit,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    FullscreenSystemBarsEffect(fullscreen)
    Scaffold(
        topBar = {
            if (!fullscreen) {
                UnfurlitTopAppBar(onShowHistory = onShowHistory)
            }
        },
    ) { contentPadding ->
        when (state) {
            ViewerState.Idle -> IdleContent(
                onBack = onBack,
                modifier = Modifier.padding(contentPadding),
            )

            is ViewerState.Loading -> LoadingContent(
                modifier = Modifier.padding(contentPadding),
            )

            is ViewerState.Failed -> FailureContent(
                error = state.error,
                onTryAnother = onBack,
                onRetry = onRetry,
                onOpenOriginal = {
                    context.openOriginal(state.sourceUrl)
                },
                modifier = Modifier.padding(contentPadding),
            )

            is ViewerState.Ready -> Column(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
                    .then(
                        if (fullscreen) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(rememberScrollState())
                        },
                    ),
            ) {
                MediaViewer(
                    extraction = state.extraction,
                    onRetry = onRetry,
                    onViewed = { onViewed(state.extraction) },
                    modifier = if (fullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    fullscreen = fullscreen,
                    onFullscreenChange = onFullscreenChange,
                )
                if (!fullscreen) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.extraction.platform ?: stringResource(R.string.media_generic),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = state.extraction.title ?: stringResource(R.string.media_untitled),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        state.extraction.author?.let { author ->
                            Text(
                                text = author,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.extraction.description?.let { description ->
                            Text(
                                text = description,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = {
                                context.openOriginal(state.sourceUrl)
                            },
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.action_open_link))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenSystemBarsEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, fullscreen) {
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Unfurlit handles these links itself, so a browser is selected explicitly. The selector
// only picks the browser app; the VIEW intent it receives still carries the URL.
private fun Context.openOriginal(url: String) {
    val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    }
    try {
        startActivity(browserIntent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.open_link_failed, Toast.LENGTH_SHORT).show()
    }
}

@get:StringRes
private val ExtractionError.titleRes: Int
    get() = when (this) {
        ExtractionError.UnsupportedUrl -> R.string.error_unsupported_url
        ExtractionError.MediaUnavailable -> R.string.error_media_unavailable
        ExtractionError.AuthenticationRequired -> R.string.error_authentication_required
        ExtractionError.NetworkFailure -> R.string.error_network_failure
        ExtractionError.Timeout -> R.string.error_timeout
        ExtractionError.ExtractionFailed -> R.string.error_extraction_failed
    }

@get:StringRes
private val ExtractionError.recoveryRes: Int
    get() = when (this) {
        ExtractionError.UnsupportedUrl -> R.string.error_unsupported_url_recovery
        ExtractionError.MediaUnavailable -> R.string.error_media_unavailable_recovery
        ExtractionError.AuthenticationRequired -> R.string.error_authentication_required_recovery
        ExtractionError.NetworkFailure -> R.string.error_network_failure_recovery
        ExtractionError.Timeout -> R.string.error_timeout_recovery
        ExtractionError.ExtractionFailed -> R.string.error_extraction_failed_recovery
    }

@Composable
private fun IdleContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.viewer_idle),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.action_go_home))
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.viewer_loading),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.viewer_loading_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun FailureContent(
    error: ExtractionError,
    onRetry: () -> Unit,
    onOpenOriginal: () -> Unit,
    onTryAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(error.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(error.recoveryRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = if (error.canRetry) onRetry else onOpenOriginal,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(if (error.canRetry) R.string.action_try_again else R.string.action_open_link))
            }
            if (error.canRetry) {
                OutlinedButton(
                    onClick = onOpenOriginal,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.action_open_link))
                }
            }
            TextButton(
                onClick = onTryAnother,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.action_try_another_link))
            }
        }
    }
}
