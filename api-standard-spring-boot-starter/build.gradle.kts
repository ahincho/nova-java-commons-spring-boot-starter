plugins {
    id("java-library")
    id("maven-publish")
}

val springBootVersion = "4.0.5"

dependencies {
    // api-standard library (from Maven Local)
    api("pe.edu.galaxy.training.java.libs:api-standard:1.0.0")

    // Spring Boot auto-configuration
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-starter-jackson:$springBootVersion")

    // Configuration processor
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.junit.platform:junit-platform-launcher:6.0.3")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
