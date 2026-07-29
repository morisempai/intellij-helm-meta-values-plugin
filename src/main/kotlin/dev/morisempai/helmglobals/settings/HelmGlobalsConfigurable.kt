package dev.morisempai.helmglobals.settings

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import dev.morisempai.helmglobals.HelmGlobalsBundle
import dev.morisempai.helmglobals.meta.MetaValuesService
import javax.swing.JLabel

class HelmGlobalsConfigurable(private val project: Project) : BoundSearchableConfigurable(
    HelmGlobalsBundle.message("settings.display.name"),
    "dev.morisempai.helmglobals.settings",
) {

    private val settings = HelmGlobalsSettings.getInstance(project)

    private lateinit var detectedLabel: JLabel

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox(HelmGlobalsBundle.message("settings.enabled"))
                .bindSelected(settings.state::enabled)
        }

        group(HelmGlobalsBundle.message("settings.meta.files.group")) {
            row {
                textArea()
                    .rows(4)
                    .align(Align.FILL)
                    .bindText(
                        { settings.metaFilePaths.joinToString("\n") },
                        { text -> settings.replaceMetaFilePaths(text.toPathList()) },
                    )
                    .comment(HelmGlobalsBundle.message("settings.meta.files.comment"))
            }.resizableRow()
            row(HelmGlobalsBundle.message("settings.detected.header")) {
                detectedLabel = label("").component
            }
        }

        group(HelmGlobalsBundle.message("settings.values.globs.group")) {
            row {
                textArea()
                    .rows(4)
                    .align(Align.FILL)
                    .bindText(
                        { settings.state.valuesFileGlobs.joinToString("\n") },
                        { text -> settings.replaceValuesFileGlobs(text.toPathList()) },
                    )
                    .comment(HelmGlobalsBundle.message("settings.values.globs.comment"))
            }.resizableRow()
        }

        row(HelmGlobalsBundle.message("settings.root.label")) {
            textField()
                .bindText(
                    { settings.variableRoot },
                    { value -> settings.state.variableRoot = value.trim().trimStart('.').ifEmpty { DEFAULT_VARIABLE_ROOT } },
                )
                .comment(HelmGlobalsBundle.message("settings.root.comment"))
        }

        row {
            checkBox(HelmGlobalsBundle.message("settings.inlay.hints"))
                .bindSelected(settings.state::showInlayValues)
        }
    }

    override fun reset() {
        super.reset()
        refreshDetectedLabel()
    }

    override fun apply() {
        super.apply()
        // Every cached value in the plugin is keyed on the settings state's modification count.
        settings.state.bumpModificationCount()
        refreshDetectedLabel()
    }

    private fun refreshDetectedLabel() {
        if (!::detectedLabel.isInitialized) return
        val files = runReadAction { MetaValuesService.getInstance(project).metaVirtualFiles() }
        detectedLabel.text = if (files.isEmpty()) {
            HelmGlobalsBundle.message("settings.detected.none")
        } else {
            files.joinToString(", ") { it.name }
        }
    }

    private fun String.toPathList(): List<String> =
        lines().map { it.trim() }.filter { it.isNotEmpty() }
}
