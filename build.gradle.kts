plugins {
    application
}

repositories {
    mavenCentral()
}



application {
    mainClass.set("server.Server")
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src"))
        }
    }
    test {
        java {
            setSrcDirs(listOf("tests"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

// Custom task to compile and run all Java main tests sequentially
tasks.register("runAllTests") {
    dependsOn(tasks.compileTestJava)
    doLast {
        val testClasses = listOf(
            "RateLimiterTest",
            "WebSocketHandshakeTest",
            "HeartbeatTest",
            "WhisperTest",
            "SpectatorTest",
            "MatchmakingSkillTest",
            "LedgerReplayTest",
            "SystemIntegrationTest"
        )

        testClasses.forEach { className ->
            println("\n======================================================")
            println("  RUNNING GRADLE TEST: $className")
            println("======================================================")
            try {
                javaexec {
                    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath + 
                                project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
                    mainClass.set(className)
                    systemProperty("aegiscore.metrics.enabled", "false")
                }
                println("Test $className -> PASSED")
            } catch (e: Exception) {
                throw GradleException("Gradle build error: Test $className FAILED! " + e.message)
            }
        }
    }
}

// Map standard test task to depend on our custom runner
tasks.test {
    dependsOn("runAllTests")
}
