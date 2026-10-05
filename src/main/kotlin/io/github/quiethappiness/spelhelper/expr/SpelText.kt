package io.github.quiethappiness.spelhelper.expr

import com.intellij.openapi.util.TextRange

/**
 * 表达式文本层面的解析。**纯逻辑,不碰 PSI**,便于单测。
 *
 * 只做一件事:给定「光标之前的文本」,求出
 *  - 根引用(`#user` / `T(...)` / `@bean`)
 *  - 根之后的链(`.name` / `.method()` / `['k']`)
 *  - 光标处正在输入的标识符(prefix)
 *  - 光标处期待什么
 *
 * **prefix 一律不含 `#`/`@` 前缀** —— 它是文档里紧随光标、会被补全替换掉的那段文本。
 * 补全项的 lookupString 也用裸名字,插入后 `#` 原样保留。
 */
object SpelText {

	sealed interface RootRef {
		data class Variable(val name: String) : RootRef
		data class TypeRef(val fqn: String) : RootRef
		data class Bean(val name: String) : RootRef
	}

	sealed interface Segment {
		data class Property(val name: String) : Segment
		data class Method(val name: String) : Segment
		data class Index(val text: String) : Segment
	}

	enum class Expecting { VARIABLE, TYPE, BEAN, PROPERTY, NOTHING }

	data class Chain(
		val root: RootRef?,
		val segments: List<Segment>,
		val prefix: String,
		val expecting: Expecting
	)

	/** 字符串字面量内容(不含引号)的起始偏移;不是普通/文本块字符串则 null。 */
	fun contentStartOffset(literalText: String): Int? = when {
		literalText.length >= 6 && literalText.startsWith(TRIPLE_QUOTE) && literalText.endsWith(TRIPLE_QUOTE) -> 3
		literalText.length >= 2 && literalText.startsWith(QUOTE) && literalText.endsWith(QUOTE) -> 1
		else -> null
	}

	/** 承载表达式的片段(相对字符串内容)。与 SpelAnnotationInjector 保持一致。 */
	fun fragmentsOf(content: String): List<TextRange> {
		val wrapped = mutableListOf<TextRange>()
		var i = 0
		while (i < content.length - 1) {
			if (content[i] == '#' && content[i + 1] == '{') {
				val close = content.indexOf('}', i + 2)
				if (close > i + 2) {
					wrapped += TextRange(i + 2, close)
					i = close + 1
					continue
				}
			}
			i++
		}
		if (wrapped.isNotEmpty()) return wrapped
		return if (content.startsWith(HASH)) listOf(TextRange(0, content.length)) else emptyList()
	}

	/** 内容中的某个偏移落在哪个片段里(含端点,便于在片段末尾补全)。 */
	fun fragmentAt(content: String, offsetInContent: Int): TextRange? =
		fragmentsOf(content).firstOrNull {
			offsetInContent >= it.startOffset && offsetInContent <= it.endOffset
		}

	/** 解析光标之前的文本。 */
	fun parseBefore(text: String): Chain {
		var prefix = trailingIdentifier(text)
		val head = text.substring(0, text.length - prefix.length)

		// T( ... ) 内部 → 补类名。前缀要带上已输入的点号(如 java.lang.M)
		val typeOpen = unmatchedTypeReferenceOpen(head)
		if (typeOpen >= 0) {
			prefix = head.substring(typeOpen + 1) + prefix
			return Chain(null, emptyList(), prefix, Expecting.TYPE)
		}

		var i = head.length - 1
		while (i >= 0 && head[i].isWhitespace()) i--
		if (i < 0) return Chain(null, emptyList(), prefix, Expecting.VARIABLE)

		return when (head[i]) {
			HASH -> Chain(null, emptyList(), prefix, Expecting.VARIABLE)
			AT -> Chain(null, emptyList(), prefix, Expecting.BEAN)
			'.' -> {
				// 支持 ?. 安全访问
				val beforeDot = head.substring(0, i).trimEnd().removeSuffix("?")
				val (root, segments) = parseHead(beforeDot)
				Chain(root, segments, prefix, Expecting.PROPERTY)
			}
			else -> Chain(null, emptyList(), prefix, Expecting.VARIABLE)
		}
	}

	// ---------- 内部 ----------

	/** 正向扫描一遍,得到根与链。 */
	private fun parseHead(text: String): Pair<RootRef?, List<Segment>> {
		var root: RootRef? = null
		val segments = mutableListOf<Segment>()
		var i = 0

		while (i < text.length) {
			val c = text[i]
			when {
				c.isWhitespace() -> i++

				c == HASH -> {
					val name = readIdentifier(text, i + 1)
					root = if (name.isEmpty()) null else RootRef.Variable(name)
					segments.clear()
					i += 1 + name.length
				}

				c == AT -> {
					val name = readIdentifier(text, i + 1)
					root = if (name.isEmpty()) null else RootRef.Bean(name)
					segments.clear()
					i += 1 + name.length
				}

				c == '[' -> {
					val close = matchingBracket(text, i, '[', ']')
					val raw = if (close < 0) text.substring(i + 1) else text.substring(i + 1, close)
					segments += Segment.Index(raw.trim())
					i = if (close < 0) text.length else close + 1
				}

				c == '(' -> {
					val close = matchingBracket(text, i, '(', ')')
					i = if (close < 0) text.length else close + 1
				}

				c == ',' -> {
					root = null
					segments.clear()
					i++
				}

				c == '.' || c == '?' -> i++

				isIdentifierStart(c) -> {
					val name = readIdentifier(text, i)
					val next = i + name.length

					if (name == TYPE_REFERENCE && next < text.length && text[next] == '(') {
						val close = matchingBracket(text, next, '(', ')')
						root = RootRef.TypeRef(if (close < 0) "" else text.substring(next + 1, close).trim())
						segments.clear()
						i = if (close < 0) text.length else close + 1
					} else {
						// 根位置上的裸标识符在 SpEL 里不是变量引用,不可解析
						if (root != null) {
							segments += if (next < text.length && text[next] == '(') {
								Segment.Method(name)
							} else {
								Segment.Property(name)
							}
						}
						i = next
					}
				}

				else -> i++
			}
		}
		return root to segments
	}

	/** head 末尾是否停在一个未闭合的 `T(` 里;返回那个 `(` 的下标,没有则 -1。 */
	private fun unmatchedTypeReferenceOpen(head: String): Int {
		var depth = 0
		for (i in head.indices.reversed()) {
			when (head[i]) {
				')' -> depth++
				'(' -> {
					if (depth == 0) {
						return if (i > 0 && head[i - 1] == 'T' && (i - 2 < 0 || !isIdentifierPart(head[i - 2]))) i else -1
					}
					depth--
				}
			}
		}
		return -1
	}

	private fun trailingIdentifier(text: String): String {
		var i = text.length
		while (i > 0 && isIdentifierPart(text[i - 1])) i--
		return text.substring(i)
	}

	private fun readIdentifier(text: String, from: Int): String {
		var i = from
		while (i < text.length && isIdentifierPart(text[i])) i++
		return text.substring(from, i)
	}

	/** 返回与 openIndex 处括号配对的闭合括号下标;没有则 -1。 */
	private fun matchingBracket(text: String, openIndex: Int, open: Char, close: Char): Int {
		var depth = 0
		var i = openIndex
		while (i < text.length) {
			when (text[i]) {
				open -> depth++
				close -> {
					depth--
					if (depth == 0) return i
				}
			}
			i++
		}
		return -1
	}

	private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

	private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

	private const val HASH = '#'
	private const val AT = '@'
	private const val QUOTE = "\""
	private const val TRIPLE_QUOTE = "\"\"\""
	private const val TYPE_REFERENCE = "T"
}
