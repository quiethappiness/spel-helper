plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm") version "2.1.20"
	id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "io.github.quiethappiness"
version = "1.0.0"

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
	intellijPlatform {
		intellijIdea("2025.2.4")
		testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
		
		
		// Java PSI：PsiLiteralExpression、PsiAnnotation、PsiNameValuePair 等
		bundledPlugin("com.intellij.java")

		// SpEL 语言由 Ultimate 自带的 Spring 插件提供，
		// plugin.xml 里的 <depends>com.intellij.spring</depends> 必须在这里也声明，否则 verifyPluginProjectConfiguration 会报错
		bundledPlugin("com.intellij.spring")
	}

	// SpelText 是纯文本解析，不需要 IDE 环境，用普通 JUnit 4 单测即可
	testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
	pluginConfiguration {
		ideaVersion {
			sinceBuild = "252.25557"
		}
		
		changeNotes = """
            <b>1.0.0</b>
            <ul>
              <li>为自定义注解中的 SpEL 表达式提供语法高亮</li>
              <li><code>#</code> 变量补全：方法参数、外层类字段、#root / #this</li>
              <li>属性与方法补全：<code>#user.</code> 之后列出目标类的成员</li>
              <li>Ctrl+点击跳转，并支持查找引用与重命名</li>
              <li>支持 <code>T(...)</code> 类型引用补全</li>
              <li>支持裸写 <code>#user.code</code> 与包装写法 <code>#{user.code}</code></li>
            </ul>
        """.trimIndent()
	}
}

tasks {
	// Set the JVM compatibility versions
	withType<JavaCompile> {
		sourceCompatibility = "21"
		targetCompatibility = "21"
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}