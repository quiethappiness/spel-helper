package io.github.quiethappiness.spelhelper.ref

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import io.github.quiethappiness.spelhelper.detect.SpelHosts
import io.github.quiethappiness.spelhelper.expr.SpelText
import io.github.quiethappiness.spelhelper.resolve.SpelResolver

/**
 * 给 SpEL 片段里的标识符一个**能解析的引用**。
 *
 * 为什么需要它:SpEL 的检查器(`SpringElInspection.checkVariableReference`)看的是
 * 变量元素上 `getReferences()` 能不能解析。而引用是由
 * `ReferenceProvidersRegistry.getReferencesFromProviders()` 产生的
 * (见 `SpringELPsiImplUtil.getReferences`)—— 所以第三方可以参与进来。
 *
 * 一旦 `#user` 能解析:黄线消失,而且 Ctrl+点击、查找引用都变成 IDE 原生行为。
 */
class SpelReferenceContributor : PsiReferenceContributor() {

	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		// 用高优先级:SpEL 自带的贡献者会为变量元素返回一个解析不了的引用,
		// 而引用注册表只取第一个返回非空的结果 —— 不抢先的话黄线去不掉
		registrar.registerReferenceProvider(
			PlatformPatterns.psiElement(),
			SpelReferenceProvider(),
			PsiReferenceRegistrar.HIGHER_PRIORITY
		)
	}
}

private class SpelReferenceProvider : PsiReferenceProvider() {

	override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
		val token = element.text
		if (token.isEmpty()) return PsiReference.EMPTY_ARRAY

		// 只管我们自己注入的、命中的注解属性
		val host = InjectedLanguageManager.getInstance(element.project)
			.getInjectionHost(element) as? PsiLiteralExpression
			?: return PsiReference.EMPTY_ARRAY
		if (!SpelHosts.isSpelHost(host)) return PsiReference.EMPTY_ARRAY

		val fileText = element.containingFile.text
		val start = element.textOffset
		if (start < 0 || start > fileText.length) return PsiReference.EMPTY_ARRAY

		// 元素前面的文本决定它是根、还是链上的一段
		val chain = SpelText.parseBefore(fileText.substring(0, start))

		val target = when (chain.expecting) {
			SpelText.Expecting.VARIABLE ->
				SpelResolver.variableDeclaration(token.removePrefix("#"), host)

			SpelText.Expecting.PROPERTY -> {
				val type = SpelResolver.resolveChain(chain.root, chain.segments, host)
					?: return PsiReference.EMPTY_ARRAY
				SpelResolver.declarationOfMember(type, token)
			}

			SpelText.Expecting.BEAN ->
				SpelResolver.beanDeclaration(token.removePrefix("@"), host.project)

			else -> null
		} ?: return PsiReference.EMPTY_ARRAY

		return arrayOf(PsiReferenceBase.createSelfReference(element, target))
	}
}
