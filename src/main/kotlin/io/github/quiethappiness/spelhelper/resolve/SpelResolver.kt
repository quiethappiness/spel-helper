package io.github.quiethappiness.spelhelper.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import io.github.quiethappiness.spelhelper.expr.SpelText

/**
 * 把 [SpelText] 解析出的名字落到 Java PSI 上。
 *
 * 这一层是平台给不了我们的部分:SpEL 语言自己的上下文只认 Spring 内置注解,
 * 自定义注解里的 `#参数名` 只能自己解析。
 */
object SpelResolver {

	/** 一个可补全的成员。 */
	class Member(
		val name: String,
		val type: PsiType?,
		val declaration: PsiElement,
		val isMethod: Boolean,
		val hasParameters: Boolean
	)

	// ---------- 根 ----------

	fun resolveRoot(root: SpelText.RootRef, context: PsiElement): PsiType? = when (root) {
		is SpelText.RootRef.Variable -> resolveVariable(root.name, context)
		is SpelText.RootRef.TypeRef -> classOfFqn(root.fqn, context.project)
		is SpelText.RootRef.Bean -> resolveBean(root.name, context.project)
	}

	/** `#name` → 外层方法参数,其次方法内局部变量,最后外层类字段。 */
	private fun resolveVariable(name: String, context: PsiElement): PsiType? {
		val enclosingClass = PsiTreeUtil.getParentOfType(context, PsiClass::class.java, false)
		if (name == ROOT_ALIAS || name == THIS_ALIAS) return enclosingClass?.let { typeOf(it) }

		val method = PsiTreeUtil.getParentOfType(context, PsiMethod::class.java, false)
		method?.parameterList?.parameters?.firstOrNull { it.name == name }?.let { return it.type }

		method?.let { enclosing ->
			PsiTreeUtil.findChildrenOfType(enclosing.body, PsiLocalVariable::class.java)
				.firstOrNull { it.name == name }
				?.let { return it.type }
		}

		return enclosingClass?.findFieldByName(name, true)?.type
	}

	// ---------- 链 ----------

	/** 沿链逐段解析,任一段解析不了就返回 null。 */
	fun resolveChain(root: SpelText.RootRef?, segments: List<SpelText.Segment>, context: PsiElement): PsiType? {
		val start = root?.let { resolveRoot(it, context) } ?: return null
		var current: PsiType = start
		for (segment in segments) {
			current = when (segment) {
				is SpelText.Segment.Property -> propertyType(current, segment.name) ?: return null
				is SpelText.Segment.Method -> methodReturnType(current, segment.name) ?: return null
				is SpelText.Segment.Index -> indexType(current, segment.text) ?: return null
			}
		}
		return current
	}

	// ---------- 成员 ----------

	/** 某类型上可用的属性(record 访问器、getter、public 字段)。 */
	fun propertiesOf(type: PsiType): List<Member> {
		val psiClass = classOf(type) ?: return emptyList()
		val result = LinkedHashMap<String, Member>()

		// record 的访问器名字与组件同名(不是 getXxx),单独处理
		for (component in psiClass.recordComponents) {
			val name = component.name
			val accessor = psiClass.findMethodsByName(name, true)
				.firstOrNull { it.parameterList.parametersCount == 0 }
				?: continue
			result[name] = Member(name, accessor.returnType, accessor, false, false)
		}

		// getter:getXxx / isXxx
		for (method in psiClass.allMethods) {
			if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
			if (method.hasModifierProperty(PsiModifier.STATIC)) continue
			if (method.parameterList.parametersCount != 0) continue
			val returnType = method.returnType ?: continue
			if (returnType == PsiTypes.voidType()) continue
			val propertyName = propertyNameOf(method.name, returnType) ?: continue
			if (result.containsKey(propertyName)) continue
			result[propertyName] = Member(propertyName, returnType, method, false, false)
		}

		// public 字段(含枚举常量)
		for (field in psiClass.allFields) {
			if (!field.hasModifierProperty(PsiModifier.PUBLIC)) continue
			result.putIfAbsent(field.name, Member(field.name, field.type, field, false, false))
		}

		return result.values.toList()
	}

	/** 某类型上可用的方法。 */
	fun methodsOf(type: PsiType): List<Member> {
		val psiClass = classOf(type) ?: return emptyList()
		return psiClass.allMethods
			.filter { it.hasModifierProperty(PsiModifier.PUBLIC) && !it.hasModifierProperty(PsiModifier.STATIC) }
			.filter { it.name != "getClass" }
			.map {
				Member(
					it.name,
					it.returnType,
					it,
					isMethod = true,
					hasParameters = it.parameterList.parametersCount > 0
				)
			}
	}

	private fun propertyType(type: PsiType, name: String): PsiType? {
		val psiClass = classOf(type) ?: return null

		// record 访问器
		psiClass.findMethodsByName(name, true)
			.firstOrNull { it.parameterList.parametersCount == 0 }
			?.let { return it.returnType }

		for (prefix in GETTER_PREFIXES) {
			val method = psiClass.findMethodsByName(prefix + name.replaceFirstChar { it.uppercaseChar() }, true)
				.firstOrNull { it.parameterList.parametersCount == 0 }
			if (method != null) return method.returnType
		}

		psiClass.findFieldByName(name, true)?.let { return it.type }

		return null
	}

	private fun methodReturnType(type: PsiType, name: String): PsiType? {
		val psiClass = classOf(type) ?: return null
		return psiClass.findMethodsByName(name, true).firstOrNull()?.returnType
	}

	private fun indexType(type: PsiType, text: String): PsiType? {
		if (type is PsiArrayType) return type.componentType
		val quoted = text.startsWith('\'') || text.startsWith('"')
		return if (quoted) {
			mapValueType(type) ?: iterableElementType(type)
		} else {
			iterableElementType(type) ?: mapValueType(type)
		}
	}

	private fun iterableElementType(type: PsiType): PsiType? =
		PsiUtil.extractIterableTypeParameter(type, false)

	private fun mapValueType(type: PsiType): PsiType? =
		PsiUtil.substituteTypeParameter(type, "java.util.Map", 1, false)

	private fun propertyNameOf(methodName: String, returnType: PsiType): String? = when {
		methodName.startsWith("get") && methodName.length > 3 && methodName[3].isUpperCase() ->
			methodName.substring(3).replaceFirstChar { it.lowercaseChar() }

		methodName.startsWith("is") && methodName.length > 2 && methodName[2].isUpperCase() &&
			(returnType.equalsToText("boolean") || returnType.equalsToText("java.lang.Boolean")) ->
			methodName.substring(2).replaceFirstChar { it.lowercaseChar() }

		else -> null
	}

	// ---------- 声明(跳转用) ----------

	/** `#name` 对应的声明:方法参数,其次局部变量,最后外层类字段;#root/#this 指向外层类。 */
	fun variableDeclaration(name: String, context: PsiElement): PsiElement? {
		val enclosingClass = PsiTreeUtil.getParentOfType(context, PsiClass::class.java, false)
		if (name == ROOT_ALIAS || name == THIS_ALIAS) return enclosingClass

		val method = PsiTreeUtil.getParentOfType(context, PsiMethod::class.java, false)
		method?.parameterList?.parameters?.firstOrNull { it.name == name }?.let { return it }

		method?.let { enclosing ->
			PsiTreeUtil.findChildrenOfType(enclosing.body, PsiLocalVariable::class.java)
				.firstOrNull { it.name == name }
				?.let { return it }
		}

		return enclosingClass?.findFieldByName(name, true)
	}

	/** 某类型上名为 [name] 的属性/方法对应的声明。 */
	fun declarationOfMember(type: PsiType, name: String): PsiElement? {
		propertiesOf(type).firstOrNull { it.name == name }?.let { return it.declaration }
		val psiClass = classOf(type) ?: return null
		return psiClass.findMethodsByName(name, true).firstOrNull()
	}

	/** `@beanName` 尽力猜到的那个类本身。 */
	fun beanDeclaration(name: String, project: Project): PsiElement? {
		if (name.isEmpty()) return null
		val simpleName = name.replaceFirstChar { it.uppercaseChar() }
		return PsiShortNamesCache.getInstance(project)
			.getClassesByName(simpleName, GlobalSearchScope.allScope(project))
			.firstOrNull()
	}

	// ---------- 基础 ----------

	/** PsiType → PsiClass。只有 PsiClassType 才可解析,数组/基本类型返回 null。 */
	private fun classOf(type: PsiType): PsiClass? = (type as? PsiClassType)?.resolve()

	private fun typeOf(psiClass: PsiClass): PsiType =
		JavaPsiFacade.getElementFactory(psiClass.project).createType(psiClass)

	private fun classOfFqn(fqn: String, project: Project): PsiType? {
		if (fqn.isEmpty()) return null
		val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
			?: return null
		return typeOf(psiClass)
	}

	/** `@beanName` → 尽力猜测:按类名(首字母大写)找同名类。 */
	private fun resolveBean(name: String, project: Project): PsiType? {
		if (name.isEmpty()) return null
		val simpleName = name.replaceFirstChar { it.uppercaseChar() }
		val classes = PsiShortNamesCache.getInstance(project)
			.getClassesByName(simpleName, GlobalSearchScope.allScope(project))
		val target = classes.firstOrNull() ?: return null
		return typeOf(target)
	}

	private const val ROOT_ALIAS = "root"
	private const val THIS_ALIAS = "this"
	private val GETTER_PREFIXES = listOf("get", "is")
}
