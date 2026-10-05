package io.github.quiethappiness.spelhelper.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Other Settings → SpEL Helper
 *
 * 一张两列表格:注解全类名 + 属性名(留空表示该注解的所有 String 属性)。
 */
class SpelHelperConfigurable : Configurable {

	/** 表格里的一行。 */
	class RuleRow(var annotation: String = "", var attribute: String = "") {

		fun toEntry(): String = if (attribute.isBlank()) annotation else "$annotation#$attribute"

		companion object {
			fun of(entry: String): RuleRow {
				val hash = entry.indexOf('#')
				return if (hash >= 0) RuleRow(entry.substring(0, hash), entry.substring(hash + 1))
				else RuleRow(entry, "")
			}
		}
	}

	private val tableModel = ListTableModel<RuleRow>(
		object : ColumnInfo<RuleRow, String>("注解全类名") {
			override fun valueOf(item: RuleRow): String = item.annotation
			override fun isCellEditable(item: RuleRow): Boolean = true
			override fun setValue(item: RuleRow, value: String) {
				item.annotation = value.trim()
			}
		},
		object : ColumnInfo<RuleRow, String>("属性名(留空 = 全部)") {
			override fun valueOf(item: RuleRow): String = item.attribute
			override fun isCellEditable(item: RuleRow): Boolean = true
			override fun setValue(item: RuleRow, value: String) {
				item.attribute = value.trim()
			}
		}
	)

	private val table = TableView<RuleRow>(tableModel)

	private val heuristicBox = JBCheckBox("注解里以 # 开头的字符串自动识别为 SpEL(无需逐条配置)")

	private val panel = JPanel(BorderLayout()).apply {
		add(heuristicBox, BorderLayout.NORTH)
		add(
			ToolbarDecorator.createDecorator(table)
				.setAddAction { tableModel.addRow(RuleRow()) }
				.setRemoveAction {
					table.selectedRows.sortedDescending().forEach { tableModel.removeRow(it) }
				}
				.createPanel(),
			BorderLayout.CENTER
		)
	}

	override fun getDisplayName(): String = "SpEL Helper"

	override fun createComponent(): JComponent {
		reset()
		return panel
	}

	override fun isModified(): Boolean {
		val settings = SpelHelperSettings.getInstance()
		return heuristicBox.isSelected != settings.heuristicEnabled || currentEntries() != settings.getRules()
	}

	override fun apply() {
		val settings = SpelHelperSettings.getInstance()
		settings.heuristicEnabled = heuristicBox.isSelected
		settings.setRules(currentEntries())
	}

	override fun reset() {
		val settings = SpelHelperSettings.getInstance()
		heuristicBox.isSelected = settings.heuristicEnabled
		tableModel.items = settings.getRules().map { RuleRow.of(it) }
	}

	override fun disposeUIResources() {
		// 无需释放
	}

	private fun currentEntries(): List<String> =
		tableModel.items.map { it.toEntry() }.filter { it.isNotBlank() }
}
