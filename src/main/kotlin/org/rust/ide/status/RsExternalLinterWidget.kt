/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil.showBalloonForComponent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ClickListener
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.rustSettings
import java.awt.event.MouseEvent
import javax.swing.JComponent

private const val rustExternalLinter: String = "rustExternalLinterWidget"

class RsExternalLinterWidgetFactory: StatusBarWidgetFactory {

    override fun getId(): String = rustExternalLinter

    override fun getDisplayName(): String = "Rust External Linter"

    override fun isAvailable(project: Project): Boolean {
        val cargoProjects = project.cargoProjects
        return cargoProjects.hasAtLeastOneValidProject && cargoProjects.suggestManifests().any()
    }

    override fun createWidget(project: Project): StatusBarWidget = RsExternalLinterWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class RsExternalLinterWidget(private val project: Project) : CustomStatusBarWidget {
    private var statusBar: StatusBar? = null

    private val linterName: String
        get() = project.rustSettings.externalLinter.title

    override fun ID() = rustExternalLinter

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        statusBar.updateWidget(ID())
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getComponent(): JComponent = MyPanel()

    private inner class MyPanel : TextPanel.WithIconAndArrows() {

        init {
            toolTipText = "Analyzing project with $linterName"
            text = linterName
            icon = AnimatedIcon.Default.INSTANCE
            isVisible = true
            setTextAlignment(CENTER_ALIGNMENT)
            border = StatusBarWidget.WidgetBorder.WIDE
            object : ClickListener() {
                override fun onClick(e: MouseEvent, clickCount: Int): Boolean {
                    showBalloonForComponent(
                        this@MyPanel,
                        "$linterName is used on the fly",
                        MessageType.INFO,
                        true,
                        null
                    )

                    return true
                }
            }.installOn(this, true)
        }
    }
}
