/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.TaskInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClickListener
import com.intellij.util.ui.UIUtil
import org.rust.cargo.project.configurable.RsExternalLinterConfigurable
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.runconfig.hasCargoProject
import java.awt.event.MouseEvent
import javax.swing.JComponent


class RsExternalLinterWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = RsExternalLinterWidget.ID
    override fun getDisplayName(): String = "Rust External Linter"
    override fun isAvailable(project: Project): Boolean = project.hasCargoProject
    override fun createWidget(project: Project): StatusBarWidget = RsExternalLinterWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class RsExternalLinterWidget(private val project: Project) : TextPanel.WithIconAndArrows(), CustomStatusBarWidget {
    private var statusBar: StatusBar? = null

    private var currentIndicator: ProgressIndicator? = null
    private var currentInfo: TaskInfo? = null

    init {
        setTextAlignment(CENTER_ALIGNMENT)
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, RsExternalLinterConfigurable::class.java, null)
                return true
            }
        }.installOn(this, true)
        border = StatusBarWidget.WidgetBorder.WIDE

//        project.messageBus.connect(this).subscribe(null, this::updateStatus)
        updateStatus()

        project.service<RsExternalLinterTooltipService>().showTooltip(this)
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        updateStatus()
        statusBar.updateWidget(ID())
    }

    override fun dispose() {
        currentIndicator = null
        currentInfo = null
        statusBar = null
    }

    override fun getComponent(): JComponent = this

    fun setProgress(indicator: ProgressIndicator?, info: TaskInfo?) {
        currentIndicator = indicator
        currentInfo = info
        updateStatus()
    }

    private fun updateStatus() {
        text = project.rustSettings.externalLinter.title
        if (currentIndicator?.isRunning == true) {
            toolTipText = currentInfo?.title
            icon = AnimatedIcon.Default.INSTANCE
        } else {
            toolTipText = null
            icon = AllIcons.Process.Step_passive
        }
        UIUtil.invokeLaterIfNeeded(this::repaint)
    }

    companion object {
        const val ID: String = "rustExternalLinterWidget"
    }
}
