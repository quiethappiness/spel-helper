package io.github.quiethappiness.spelhelper.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import io.github.quiethappiness.spelhelper.detect.SpelHosts
import io.github.quiethappiness.spelhelper.expr.SpelText
import io.github.quiethappiness.spelhelper.resolve.SpelResolver

/**
 * Ctrl+点击跳转:把表达式里的标识符跳到它真正的声明
 * (`#user` → 方法参数 `user`,`.code` → getter/字段,`.getName()` → 方法)。
 *
 * 和补全一样,只在**注入后的文本**里定位,不回宿主文件换算偏移。
 */
class SpelGotoDeclarationHandler : GotoDeclarationHandler {

	override fun getGotoDeclarationTargets(
		sourceElement: PsiElement?,
		offset: Int,
		editor: Editor
	): Array<PsiElement>? {
		val element = sourceElement ?: return null
		val literal = InjectedLanguageManager.getInstance(element.project)
			.getInjectionHost(element) as? PsiLiteralExpression ?: return null

		if (!SpelHosts.isSpelHost(literal)) return null

		val text = element.containingFile.text

		// sourceElement 可能从 `#` 起头,向后找真正的标识符
		val tokenRange = identifierFrom(text, element.textOffset) ?: return null
		val token = text.substring(tokenRange.first, tokenRange.second)
		val chain = SpelText.parseBefore(text.substring(0, tokenRange.first))

		val target = when (chain.expecting) {
			SpelText.Expecting.VARIABLE -> SpelResolver.variableDeclaration(token, literal)

			SpelText.Expecting.PROPERTY -> {
				val type = SpelResolver.resolveChain(chain.root, chain.segments, literal) ?: return null
				SpelResolver.declarationOfMember(type, token)
			}

			else -> null
		}

		return target?.let { arrayOf(it) }
	}

	/** 从 [from] 起找到第一段标识符。 */
	private fun identifierFrom(text: String, from: Int): Pair<Int, Int>? {
		if (from < 0 || from > text.length) return null

		var start = from
		while (start < text.length && !isIdentifierPart(text[start])) start++
		if (start >= text.length) return null

		var end = start
		while (end < text.length && isIdentifierPart(text[end])) end++
		return start to end
	}

	private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}
