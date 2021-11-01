/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import org.rust.cargo.project.settings.rustSettings
import org.rust.ide.actions.CargoEditSettingsAction
import org.rust.ide.notifications.showBalloon
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
            project.showBalloon(
                "Low Performance due External Linter",
                "The IDE is running external linter on the fly and this might affect performance. " +
                    "Please consider disabling or increasing the maximum duration of the external linter run.",
                NotificationType.WARNING,
                CargoEditSettingsAction("Configure")
            )
        }
    }

    companion object {
        private const val MAX_QUEUE_SIZE: Int = 5
    }
}
