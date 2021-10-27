/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.configurable

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.layout.*
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.toolchain.ExternalLinter
import org.rust.cargo.util.CargoCommandCompletionProvider
import org.rust.cargo.util.RsCommandLineEditor

class CargoConfigurable(project: Project) : RsConfigurableBase(project, "Cargo") {
    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox(
                "Automatically show first error in editor after a build failure",
                state::autoShowErrorsInEditor
            )
        }
        row {
            checkBox(
                "Update project automatically if Cargo.toml changes",
                state::autoUpdateEnabled
            )
        }
        row {
            checkBox(
                "Compile all project targets if possible",
                state::compileAllTargets,
                comment = "Pass <b>--target-all</b> option to Сargo <b>build</b>/<b>check</b> command"
            )
        }
        row {
            checkBox(
                "Offline mode",
                state::useOffline,
                comment = "Pass <b>--offline</b> option to Cargo not to perform network requests"
            )
        }

        titledRow("External Linter") {
            subRowIndent = 0
            row("External tool:") {
                comboBox(
                    EnumComboBoxModel(ExternalLinter::class.java),
                    state::externalLinter,
                ).comment("External tool to use for code analysis")
            }

            row("Additional arguments:") {
                RsCommandLineEditor(project, CargoCommandCompletionProvider(project.cargoProjects, "check ") { null })(CCFlags.growX)
                    .comment("Additional arguments to pass to <b>cargo check</b> or <b>cargo clippy</b> command")
                    .withBinding(
                        componentGet = { it.text },
                        componentSet = { component, value -> component.text = value },
                        modelBinding = state::externalLinterArguments.toBinding()
                    )
            }

            lateinit var runExternalLinterOnTheFly: CellBuilder<JBCheckBox>
            row {
                runExternalLinterOnTheFly = checkBox(
                    "Run external linter to analyze code on the fly",
                    state::runExternalLinterOnTheFly,
                    comment = """
                        Enable external linter to add code highlighting based on the used linter result.
                        Can be CPU-consuming
                    """.trimIndent()
                )
            }

            row {
                cell {
                    intTextField(state::externalLinterOnTheFlyMaxDuration, columns = 4, range = 1..9999)
                        .enableIf(runExternalLinterOnTheFly.selected)
                    label("Max duration of external linter run (ms)")
                        .comment("Show notification warning if the external linter is running longer than the set value")
                }
            }
        }
    }
}
