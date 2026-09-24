package io.github.originalrecipe1.unfurlit.ui.home

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.originalrecipe1.unfurlit.R
import io.github.originalrecipe1.unfurlit.ui.components.UnfurlitTopAppBar
import io.github.originalrecipe1.unfurlit.util.UrlTextParser

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onShowHistory: () -> Unit,
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    var showSupportedMedia by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        val url = UrlTextParser.firstSupportedUrl(input)
        if (url == null) {
            error = context.getString(R.string.home_invalid_url)
        } else {
            error = null
            onOpen(url)
        }
    }

    if (showSupportedMedia) {
        AlertDialog(
            onDismissRequest = { showSupportedMedia = false },
            title = { Text(stringResource(R.string.supported_media_title)) },
            text = {
                Text(
                    text = stringResource(R.string.supported_media_description),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showSupportedMedia = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            UnfurlitTopAppBar(onShowHistory = onShowHistory)
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = scrollState.maxValue > 0)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.8.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1).sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.home_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.take(MAX_INPUT_LENGTH)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.home_url_label)) },
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.home_url_placeholder)) },
                    supportingText = error?.let { message ->
                        {
                            Text(
                                text = message,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    minLines = 1,
                    maxLines = 3,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = ::submit,
                        enabled = input.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_open))
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            val clipboardText = clipboard.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            val url = UrlTextParser.firstSupportedUrl(clipboardText)
                            if (url == null) {
                                error = context.getString(R.string.home_clipboard_no_url)
                            } else {
                                input = url
                                error = null
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_paste))
                    }
                }
                Spacer(Modifier.height(32.dp))
                TextButton(onClick = { showSupportedMedia = true }) {
                    Text(stringResource(R.string.supported_media_title))
                }
            }
        }
    }
}

private const val MAX_INPUT_LENGTH = 8 * 1024
