/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.wm.WindowManager
import org.rust.cargo.project.settings.rustSettings
import org.rust.ide.notifications.showBalloon
import org.rust.ide.status.RsExternalLinterWidget
import java.util.*

interface RsLongExternalLinterRunNotifier {
    fun reportDuration(duration: Long)

    companion object {
        const val DEFAULT_MAX_DURATION: Int = 3000
    }
}

class RsLongExternalLinterRunNotifierImpl(val project: Project) : RsLongExternalLinterRunNotifier {
    private val maxDuration: Int get() = project.rustSettings.externalLinterOnTheFlyMaxDuration
    private val prevDurations: Queue<Long> = ArrayDeque()

    override fun reportDuration(duration: Long) {
        prevDurations.add(duration)
        while (prevDurations.size > MAX_QUEUE_SIZE) {
            prevDurations.remove()
        }

        val minPrevDuration = prevDurations.minOrNull() ?: 0
        if (prevDurations.size == MAX_QUEUE_SIZE && minPrevDuration > maxDuration) {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
            val widget = statusBar.getWidget(RsExternalLinterWidget.ID) as? RsExternalLinterWidget ?: return
            widget.showBalloon("Low performance due to Rust external linter", MessageType.WARNING, project)
        }
    }

    companion object {
        private const val MAX_QUEUE_SIZE: Int = 5
    }
}
