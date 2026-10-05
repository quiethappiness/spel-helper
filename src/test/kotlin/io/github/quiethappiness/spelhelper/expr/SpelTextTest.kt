package io.github.quiethappiness.spelhelper.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SpelText] 是纯逻辑,不需要 IDE 环境就能跑。
 *
 * 覆盖的都是补全/跳转真正会走到的形态:属性链、包装写法、T() 内部、运算符边界。
 */
class SpelTextTest {

	// ---------- 片段定位 ----------

	@Test
	fun `裸写法整段都是片段`() {
		assertEquals(listOf(0 to 10), ranges("#user.code"))
	}

	@Test
	fun `包装写法只取花括号内部`() {
		assertEquals(listOf(2 to 11), ranges("#{user.code}"))
	}

	@Test
	fun `同一字符串里多个包装片段`() {
		assertEquals(listOf(2 to 3, 6 to 7), ranges("#{a}#{b}"))
	}

	@Test
	fun `不以井号开头就不是片段`() {
		assertEquals(emptyList<Pair<Int, Int>>(), ranges("hello"))
	}

	@Test
	fun `光标落在片段内的定位`() {
		assertEquals(2 to 11, SpelText.fragmentAt("#{user.code}", 5)!!.let { it.startOffset to it.endOffset })
		assertNull(SpelText.fragmentAt("#{user.code}", 1))
	}

	// ---------- 根位置 ----------

	@Test
	fun `空文本期待变量`() {
		val chain = SpelText.parseBefore("")
		assertEquals(SpelText.Expecting.VARIABLE, chain.expecting)
		assertEquals("", chain.prefix)
		assertNull(chain.root)
	}

	@Test
	fun `刚写完井号`() {
		val chain = SpelText.parseBefore("#")
		assertEquals(SpelText.Expecting.VARIABLE, chain.expecting)
		assertEquals("", chain.prefix)
	}

	@Test
	fun `井号后带部分名字`() {
		val chain = SpelText.parseBefore("#us")
		assertEquals(SpelText.Expecting.VARIABLE, chain.expecting)
		assertEquals("us", chain.prefix)
	}

	@Test
	fun `运算符之后回到根位置`() {
		val chain = SpelText.parseBefore("#user.name == ")
		assertEquals(SpelText.Expecting.VARIABLE, chain.expecting)
		assertNull(chain.root)
	}

	@Test
	fun `艾特之后期待 bean`() {
		val chain = SpelText.parseBefore("@my")
		assertEquals(SpelText.Expecting.BEAN, chain.expecting)
		assertEquals("my", chain.prefix)
	}

	// ---------- 属性链 ----------

	@Test
	fun `点号之后期待属性`() {
		val chain = SpelText.parseBefore("#user.")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals("", chain.prefix)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
		assertEquals(emptyList<SpelText.Segment>(), chain.segments)
	}

	@Test
	fun `点号之后带部分属性名`() {
		val chain = SpelText.parseBefore("#user.ad")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals("ad", chain.prefix)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
	}

	@Test
	fun `多级属性`() {
		val chain = SpelText.parseBefore("#user.address.c")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals("c", chain.prefix)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
		assertEquals(listOf(SpelText.Segment.Property("address")), chain.segments)
	}

	@Test
	fun `方法调用作为一段`() {
		val chain = SpelText.parseBefore("#user.getName().")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
		assertEquals(listOf(SpelText.Segment.Method("getName")), chain.segments)
	}

	@Test
	fun `方括号索引作为一段`() {
		val chain = SpelText.parseBefore("#user['key'].")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
		assertEquals(listOf(SpelText.Segment.Index("'key'")), chain.segments)
	}

	@Test
	fun `安全访问问号点`() {
		val chain = SpelText.parseBefore("#user?.addr")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals("addr", chain.prefix)
		assertEquals(SpelText.RootRef.Variable("user"), chain.root)
	}

	@Test
	fun `表达式里最后一个操作数才是当前链`() {
		val chain = SpelText.parseBefore("#a + #b.")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals(SpelText.RootRef.Variable("b"), chain.root)
		assertEquals(emptyList<SpelText.Segment>(), chain.segments)
	}

	@Test
	fun `bean 上的属性链`() {
		val chain = SpelText.parseBefore("@myBean.")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals(SpelText.RootRef.Bean("myBean"), chain.root)
	}

	// ---------- T() ----------

	@Test
	fun `T 内部期待类名且前缀含点号`() {
		val chain = SpelText.parseBefore("T(java.lang.M")
		assertEquals(SpelText.Expecting.TYPE, chain.expecting)
		assertEquals("java.lang.M", chain.prefix)
	}

	@Test
	fun `闭合的 T 之后是属性链`() {
		val chain = SpelText.parseBefore("T(java.lang.Math).")
		assertEquals(SpelText.Expecting.PROPERTY, chain.expecting)
		assertEquals(SpelText.RootRef.TypeRef("java.lang.Math"), chain.root)
	}

	// ---------- 内容区间 ----------

	@Test
	fun `普通字符串内容从引号后开始`() {
		assertEquals(1, SpelText.contentStartOffset("\"#user\""))
	}

	@Test
	fun `文本块内容跳三个引号`() {
		assertEquals(3, SpelText.contentStartOffset("\"\"\"#user\"\"\""))
	}

	@Test
	fun `非字符串返回 null`() {
		assertNull(SpelText.contentStartOffset("123"))
	}

	private fun ranges(content: String): List<Pair<Int, Int>> =
		SpelText.fragmentsOf(content).map { it.startOffset to it.endOffset }
}
