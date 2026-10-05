package io.github.quiethappiness.spelhelper.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 应用级设置:记录哪些注解属性里写的是 SpEL 表达式。
 *
 * 一条规则可以是:
 *  - `com.foo.MySpel`          —— 该注解的**所有** String 属性
 *  - `com.foo.MySpel#value`    —— 仅 `value` 属性
 */
@Service(Service.Level.APP)
@State(name = "SpelHelperSettings", storages = [Storage("spel-helper.xml")])
class SpelHelperSettings : PersistentStateComponent<SpelHelperSettings.State> {

	class State {
		var rules: MutableList<String> = mutableListOf()
		var heuristicEnabled: Boolean = true
	}

	private var myState = State()

	override fun getState(): State = myState

	override fun loadState(state: State) {
		myState = state
	}

	/** 未配置的注解里,以 `#` 开头的字符串字面量也自动识别为 SpEL。 */
	var heuristicEnabled: Boolean
		get() = myState.heuristicEnabled
		set(value) {
			myState.heuristicEnabled = value
		}

	fun getRules(): List<String> = myState.rules.toList()

	fun setRules(rules: List<String>) {
		myState.rules = rules.toMutableList()
	}

	/** 该注解属性是否被显式配置为 SpEL 宿主。 */
	fun isConfigured(annotationFqn: String, attributeName: String): Boolean =
		myState.rules.any { rule ->
			val hash = rule.indexOf('#')
			val annotation = if (hash >= 0) rule.substring(0, hash).trim() else rule.trim()
			val attribute = if (hash >= 0) rule.substring(hash + 1).trim() else ""
			annotation == annotationFqn && (attribute.isEmpty() || attribute == attributeName)
		}

	companion object {
		fun getInstance(): SpelHelperSettings =
			ApplicationManager.getApplication().getService(SpelHelperSettings::class.java)
	}
}
