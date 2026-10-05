package io.github.quiethappiness.spelhelper.detect

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.util.PsiTreeUtil
import io.github.quiethappiness.spelhelper.settings.SpelHelperSettings

/**
 * 判断一个字符串字面量是不是「我们要处理的 SpEL 宿主」。
 *
 * 这里是唯一的判定入口,注入器只走它,避免逻辑分散。
 */
object SpelHosts {

	/** 该字面量所在的注解属性。 */
	class AnnotationContext(val annotationFqn: String, val attributeName: String) {
		companion object {
			fun of(literal: PsiLiteralExpression): AnnotationContext? {
				val parent = literal.parent ?: return null
				// @Foo({"a", "b"}) 时,字面量与 PsiNameValuePair 之间还隔着一层数组
				val holder = if (parent is PsiArrayInitializerMemberValue) parent.parent else parent
				val pair = holder as? PsiNameValuePair ?: return null
				val annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false)
					?: return null
				val fqn = annotation.qualifiedName ?: return null
				// @Foo("#x") 等价于 @Foo(value = "#x"),此时 pair.name 为 null
				return AnnotationContext(fqn, pair.name ?: DEFAULT_ATTRIBUTE_NAME)
			}
		}
	}

	/**
	 * 是否命中:注解+属性配在白名单里,或启发式开启且内容以 `#` 开头。
	 *
	 * 前提是**确实位于注解属性中** —— 普通字符串一律返回 false。
	 */
	fun isSpelHost(literal: PsiLiteralExpression): Boolean {
		val content = literal.value as? String ?: return false
		val context = AnnotationContext.of(literal) ?: return false

		val settings = SpelHelperSettings.getInstance()
		if (settings.isConfigured(context.annotationFqn, context.attributeName)) return true
		return settings.heuristicEnabled && content.startsWith(HEURISTIC_PREFIX)
	}

	const val HEURISTIC_PREFIX = "#"
	const val DEFAULT_ATTRIBUTE_NAME = "value"
}
