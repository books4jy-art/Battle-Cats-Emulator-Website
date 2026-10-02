// Builds the browser version of BCU: BCU's core (bcu-core submodule, unmodified) + the browser
// platform code (platform/) -> JavaScript with TeaVM, written into site/teavm/.
plugins {
    java
    id("org.teavm") version "0.15.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // libraries BCU's core compiles against (only the parts it actually uses end up in the browser build)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20240303")
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("com.google.api-client:google-api-client:2.4.0")
    implementation("com.google.http-client:google-http-client:1.44.1")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // TeaVM build plugins in platform/ (reflection list, Field shortcut rewrite) need TeaVM's API
    compileOnly("org.teavm:teavm-core:0.15.0")
    implementation(teavm.libs.jsoApis)
}

// BCU's core is copied out of the submodule (which stays untouched) and the patches in patches/
// are applied to the copy. Updating BCU = moving the submodule to a newer commit.
val coreSrc = layout.buildDirectory.dir("core-src")
val prepareCore by tasks.registering(Sync::class) {
    from("bcu-core") {
        include("**/*.java")
        into("common")
    }
    into(coreSrc)
    val patchDir = file("patches")
    inputs.dir(patchDir).optional()
    doLast {
        val target = coreSrc.get().dir("common").asFile
        patchDir.listFiles { f -> f.name.endsWith(".patch") }?.sortedBy { it.name }?.forEach { p ->
            val proc = ProcessBuilder("patch", "-p1", "--forward", "-d", target.path, "-i", p.path)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("patch ${p.name} failed:\n$out")
            logger.lifecycle("applied ${p.name}")
        }
    }
}

sourceSets {
    main {
        java.srcDirs(coreSrc, "platform/src/main/java")
        resources.srcDirs("platform/src/main/resources")
    }
}

tasks.compileJava {
    dependsOn(prepareCore)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

teavm {
    all {
        mainClass.set("bcuweb.Main")
    }
    js {
        obfuscated.set(providers.gradleProperty("dev").map { false }.orElse(true))
        // Java-style bounds/null/cast checks: mistakes surface as errors instead of silent NaN/undefined
        strict.set(true)
        outputDir.set(file("site/teavm"))
        targetFileName.set("bcu.js")
    }
}

// JVM test harness (platform/src/jvm): the same battle code on a normal Java VM, to compare with the
// browser build. Needs tools/dev_server.py running. Example: ./gradlew runJvmBattle --args="000003 9 0 60"
val jvm by sourceSets.creating {
    java.srcDir("platform/src/jvm/java")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
tasks.named<JavaCompile>("compileJvmJava") {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}
tasks.register<JavaExec>("runJvmBattle") {
    group = "verification"
    description = "Runs a battle with BCU's core on the JVM (for comparison with the browser build)"
    classpath = jvm.runtimeClasspath
    mainClass.set("bcuweb.jvm.JvmBattle")
    workingDir = projectDir
}
