package com.denizetkar.walkietalkieapp

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider

fun SemanticsNodeInteractionsProvider.onNodeWithStringId(
    @StringRes id: Int,
    vararg formatArgs: Any,
    substring: Boolean = false,
    ignoreCase: Boolean = false,
    useUnmergedTree: Boolean = false
): SemanticsNodeInteraction {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val text = if (formatArgs.isNotEmpty()) {
        context.getString(id, *formatArgs)
    } else {
        context.getString(id)
    }
    return onNodeWithText(text, substring, ignoreCase, useUnmergedTree)
}

fun SemanticsNodeInteractionsProvider.onAllNodesWithStringId(
    @StringRes id: Int,
    vararg formatArgs: Any,
    substring: Boolean = false,
    ignoreCase: Boolean = false,
    useUnmergedTree: Boolean = false
): SemanticsNodeInteractionCollection {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val text = if (formatArgs.isNotEmpty()) {
        context.getString(id, *formatArgs)
    } else {
        context.getString(id)
    }
    return onAllNodesWithText(text, substring, ignoreCase, useUnmergedTree)
}
