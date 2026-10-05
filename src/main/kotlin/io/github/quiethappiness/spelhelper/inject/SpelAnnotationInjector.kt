package io.github.quiethappiness.spelhelper.inject

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiLiteralExpression
import io.github.quiethappiness.spelhelper.detect.SpelHosts
import io.github.quiethappiness.spelhelper.expr.SpelText

/**
 * 把 SpEL 语言注入到注解属性里的字符串字面量中。
 *
 * 支持两种写法:
 *  - 裸写 `#user.code`    —— 整个内容就是表达式,原样注入
 *  - 包装 `#{user.code}`  —— 只注入花括号内部,并补上 `#` 前缀
 *
 * 包装写法为什么要补 `#`:本工具包的约定是 `#{user.code}` ≡ `#user.code`,
 * 即花括号内**裸写变量名**。而 SpEL 里裸标识符不是变量引用(`#user` 才是),
 * 补上前缀后 SpEL 才能把它解析成方法参数。
 */
class SpelAnnotationInjector : MultiHostInjector {

	/** 待注入的一段表达式。 */
	private class Fragment(val rangeInContent: TextRange, val prependHash: Boolean)

	override fun elementsToInjectIn(): List<Class<out PsiElement>> =
		listOf(PsiLiteralExpression::class.java)

	override fun getLanguagesToInject(registrar: MultiHostRegistrar, element: PsiElement) {
		val literal = element as? PsiLiteralExpression ?: return
		val content = literal.value as? String ?: return
		val host = literal as? PsiLanguageInjectionHost ?: return

		// 必须位于命中的注解属性中,否则一律不管 —— 普通字符串不受影响
		if (!SpelHosts.isSpelHost(literal)) return

		val fragments = fragmentsOf(content)
		if (fragments.isEmpty()) return

		// 字面量内容(不含引号)在元素内的区间;含转义序列时放弃
		val contentRange = contentRangeInElement(literal) ?: return

		val language = Language.findLanguageByID(SPEL_LANGUAGE_ID) ?: return

		registrar.makeInspectionsLenient(true)
		registrar.startInjecting(language)
		fragments.forEachIndexed { index, fragment ->
			val prefix = buildString {
				// 同一字符串里有多个片段时,用空格把它们拼成一份可解析的文档
				if (index > 0) append(FRAGMENT_SEPARATOR)
				if (fragment.prependHash) append(HASH)
			}
			registrar.addPlace(prefix, "", host, fragment.rangeInContent.shiftRight(contentRange.startOffset))
		}
		registrar.doneInjecting()
	}

	/** 拆出要注入的片段;区间相对「字符串内容」的起点。 */
	private fun fragmentsOf(content: String): List<Fragment> {
		val wrapped = mutableListOf<Fragment>()
		var i = 0
		while (i < content.length - 1) {
			if (content[i] == HASH && content[i + 1] == '{') {
				val close = content.indexOf('}', i + 2)
				if (close > i + 2) {
					val inner = content.substring(i + 2, close)
					wrapped += Fragment(TextRange(i + 2, close), needsVariablePrefix(inner))
					i = close + 1
					continue
				}
			}
			i++
		}
		if (wrapped.isNotEmpty()) return wrapped

		// 没有任何包装时,整个内容算一段(前提是以 # 开头)
		return if (content.startsWith(HASH)) {
			listOf(Fragment(TextRange(0, content.length), prependHash = false))
		} else {
			emptyList()
		}
	}

	/**
	 * 花括号内部的裸变量引用是否要补 `#`。
	 *
	 * 只在「看起来是裸标识符开头的属性链」时补,例如 `user.code`。
	 * 已经带 `#`/`@` 的、类型引用 `T(...)`、以及数字/引号/括号开头的一律不动。
	 */
	private fun needsVariablePrefix(inner: String): Boolean {
		if (inner.isEmpty()) return false
		val first = inner[0]
		if (!(first.isLetter() || first == '_' || first == '$')) return false
		if (inner.startsWith(TYPE_REFERENCE_PREFIX)) return false
		return true
	}

	/**
	 * 字面量内容(不含引号)在元素内的区间。
	 *
	 * 含转义序列时返回 null —— 那种情况下源码偏移和 `getValue()` 对不上,
	 * 与其注入错位不如不注入。
	 */
	private fun contentRangeInElement(literal: PsiLiteralExpression): TextRange? {
		val text = literal.text
		val start = SpelText.contentStartOffset(text) ?: return null
		val end = text.length - start
		if (end <= start) return null
		if (text.substring(start, end).contains(ESCAPE)) return null
		return TextRange(start, end)
	}

	private companion object {
		const val SPEL_LANGUAGE_ID = "SpEL"
		const val FRAGMENT_SEPARATOR = " "
		const val HASH = '#'
		const val ESCAPE = '\\'
		const val TYPE_REFERENCE_PREFIX = "T("
	}
}
