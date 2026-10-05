package io.github.quiethappiness.spelhelper.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import io.github.quiethappiness.spelhelper.detect.SpelHosts
import io.github.quiethappiness.spelhelper.expr.SpelText
import io.github.quiethappiness.spelhelper.resolve.SpelResolver

/**
 * 为自定义注解里的 SpEL 提供补全。
 *
 * 注册给 **SpEL 语言** —— 光标落在注入片段里时,跑的是注入语言自己的贡献者,
 * 挂 `language="JAVA"` 是收不到的。
 *
 * 全程只在**注入后的文本**里工作:注入时已经给 `#{...}` 补过 `#`,
 * 所以片段文本本身就是可直接解析的表达式,不必回宿主文件换算偏移。
 */
class SpelCompletionContributor : CompletionContributor() {

	override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
		if (parameters.completionType != CompletionType.BASIC) return

		val position = parameters.position
		val literal = InjectedLanguageManager.getInstance(position.project)
			.getInjectionHost(position) as? PsiLiteralExpression ?: return

		// 只管我们命中的注解属性
		if (!SpelHosts.isSpelHost(literal)) return

		val fileText = position.containingFile.text
		val caret = caretOffset(parameters, position.textOffset, position.textLength, fileText.length)
		val chain = SpelText.parseBefore(fileText.substring(0, caret))
		val typed = result.withPrefixMatcher(chain.prefix)

		when (chain.expecting) {
			SpelText.Expecting.VARIABLE ->
				rootVariables(literal).forEach { typed.addElement(it) }

			SpelText.Expecting.BEAN ->
				beanCandidates(chain.prefix, literal.project).forEach { typed.addElement(it) }

			SpelText.Expecting.TYPE ->
				typeCandidates(chain.prefix, literal.project).forEach { typed.addElement(it) }

			SpelText.Expecting.PROPERTY -> {
				val type = SpelResolver.resolveChain(chain.root, chain.segments, literal) ?: return
				membersOf(type).forEach { typed.addElement(it) }
			}

			SpelText.Expecting.NOTHING -> Unit
		}
	}

	/** 光标在注入文件里的偏移。 */
	private fun caretOffset(
		parameters: CompletionParameters,
		positionStart: Int,
		positionLength: Int,
		fileLength: Int
	): Int {
		val byParameter = parameters.offset
		if (byParameter in 0..fileLength) return byParameter
		return (positionStart + positionLength).coerceIn(0, fileLength)
	}

	/** `#` 之后:外层方法参数 + 外层类字段 + #root / #this */
	private fun rootVariables(literal: PsiLiteralExpression): List<LookupElementBuilder> {
		val result = mutableListOf<LookupElementBuilder>()

		PsiTreeUtil.getParentOfType(literal, PsiMethod::class.java, false)
			?.parameterList?.parameters
			?.forEach { parameter ->
				result += LookupElementBuilder.create(parameter, parameter.name)
					.withPresentableText("#" + parameter.name)
					.withTypeText(parameter.type.presentableText, true)
			}

		PsiTreeUtil.getParentOfType(literal, PsiClass::class.java, false)
			?.allFields
			?.filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
			?.forEach { field ->
				result += LookupElementBuilder.create(field, field.name)
					.withPresentableText("#" + field.name)
					.withTypeText(field.type.presentableText, true)
			}

		for (implicit in ROOT_ALIASES) {
			result += LookupElementBuilder.create(implicit).withPresentableText("#$implicit")
		}

		return result
	}

	/** `.` 之后:属性 + 方法 */
	private fun membersOf(type: PsiType): List<LookupElementBuilder> {
		val result = mutableListOf<LookupElementBuilder>()

		for (member in SpelResolver.propertiesOf(type)) {
			var builder = LookupElementBuilder.create(member.declaration, member.name)
			member.type?.let { builder = builder.withTypeText(it.presentableText, true) }
			result += builder
		}

		for (member in SpelResolver.methodsOf(type)) {
			// getter 已经以属性名给出,这里不重复
			if (isGetter(member.name, member.hasParameters)) continue
			result += LookupElementBuilder.create(member.declaration, member.name)
				.withTailText(if (member.hasParameters) "(...)" else "()", true)
		}

		return result
	}

	private fun isGetter(name: String, hasParameters: Boolean): Boolean {
		if (hasParameters) return false
		return (name.startsWith("get") && name.length > 3 && name[3].isUpperCase()) ||
			(name.startsWith("is") && name.length > 2 && name[2].isUpperCase())
	}

	/** `@` 之后:按类名猜 bean 名(尽力而为) */
	private fun beanCandidates(prefix: String, project: Project): List<LookupElementBuilder> {
		if (prefix.length < 2) return emptyList()
		val simpleName = prefix.replaceFirstChar { it.uppercaseChar() }
		return PsiShortNamesCache.getInstance(project)
			.getClassesByName(simpleName, GlobalSearchScope.allScope(project))
			.take(MAX_CANDIDATES)
			.mapNotNull { it.name }
			.map { beanName ->
				LookupElementBuilder.create(beanName.replaceFirstChar { it.lowercaseChar() })
					.withPresentableText("@" + beanName.replaceFirstChar { it.lowercaseChar() })
			}
	}

	/** `T(` 之后:类全名 */
	private fun typeCandidates(partial: String, project: Project): List<LookupElementBuilder> {
		val simpleName = partial.substringAfterLast('.')
		if (simpleName.isEmpty()) return emptyList()
		return PsiShortNamesCache.getInstance(project)
			.getClassesByName(simpleName, GlobalSearchScope.allScope(project))
			.take(MAX_CANDIDATES)
			.mapNotNull { it.qualifiedName }
			.map { LookupElementBuilder.create(it) }
	}

	private companion object {
		const val MAX_CANDIDATES = 50
		val ROOT_ALIASES = listOf("root", "this")
	}
}
