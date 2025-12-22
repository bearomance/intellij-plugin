package com.github.bearomance.intellijplugin.action

import com.github.bearomance.intellijplugin.model.ApiEndpoint
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

class ApiPopupCellRenderer : ListCellRenderer<ApiEndpoint> {
    private val label = JLabel()

    override fun getListCellRendererComponent(
        list: JList<out ApiEndpoint>,
        value: ApiEndpoint?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value != null) {
            val methodColor = when (value.method) {
                "GET" -> "#61AFEF"
                "POST" -> "#98C379"
                "PUT" -> "#E5C07B"
                "DELETE" -> "#E06C75"
                "PATCH" -> "#C678DD"
                else -> "#ABB2BF"
            }
            label.text = """
                <html>
                <span style="color:$methodColor"><b>${value.method.padEnd(6)}</b></span>
                <span style="color:#ABB2BF">${value.path}</span>
                <span style="color:#5C6370"> → ${value.className}.${value.methodName}()</span>
                <span style="color:#98C379"> [${value.moduleName}]</span>
                </html>
            """.trimIndent().replace("\n", "")
        }
        label.isOpaque = true
        label.font = label.font.deriveFont(13f)
        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
        } else {
            label.background = list.background
            label.foreground = list.foreground
        }
        label.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        return label
    }
}

