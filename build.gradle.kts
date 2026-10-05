plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm") version "2.1.20"
	id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "io.github.quiethappiness"
version = "1.1-SNAPSHOT"

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
            Initial version
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