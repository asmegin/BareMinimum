plugins {
    java
}

group = "com.bareminimum"
version = "1.0.0"

repositories {
    mavenCentral()
}

val montoyaApiVersion = "2026.7"

dependencies {
    // Provided by Burp Suite at runtime, so it is not bundled into the extension JAR.
    compileOnly("net.portswigger.burp.extensions:montoya-api:$montoyaApiVersion")

    testImplementation("net.portswigger.burp.extensions:montoya-api:$montoyaApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Build with any JDK 17 or newer; the bytecode targets Java 17 so it loads in every
    // supported Burp Suite release.
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.jar {
    archiveBaseName.set("BareMinimum")
    archiveVersion.set(project.version.toString())
    manifest {
        attributes(
            "Implementation-Title" to "BareMinimum",
            "Implementation-Version" to project.version
        )
    }
    // BareMinimum has no runtime dependencies beyond the Montoya API, but anything added later
    // is bundled so the extension stays a single self-contained JAR.
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
