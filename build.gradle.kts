import org.gradle.api.plugins.quality.CheckstyleExtension
import org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension

plugins {
    id("net.nemerosa.versioning") version "4.0.1"
    id("org.owasp.dependencycheck") version "12.2.2" apply false
    id("org.cyclonedx.bom") version "3.2.4"
}

versioning {
    releaseMode = "snapshot"
    displayMode = "snapshot"
    releaseBuild = false
}

subprojects {
    group = "pe.edu.nova.java.starters"
    version = findProperty("version") as String
    
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "org.owasp.dependencycheck")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    repositories {
        mavenLocal()
        mavenCentral()
        // Internal Nova Platform dependencies (each lives in its own repo/package).
        // GITHUB_TOKEN cannot read packages from another repo, so this needs a PAT
        // (falls back to GITHUB_TOKEN for local/manual builds where only that is set).
        val readToken = System.getenv("NOVA_PACKAGES_READ_TOKEN") ?: System.getenv("GITHUB_TOKEN")
        maven {
            name = "NovaMaskUtils"
            url = uri("https://maven.pkg.github.com/ahincho/nova-java-mask-utils")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = readToken
            }
        }
        maven {
            name = "NovaApiStandard"
            url = uri("https://maven.pkg.github.com/ahincho/nova-java-api-standard")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = readToken
            }
        }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform()
    }

    tasks.named<Javadoc>("javadoc") {
        (options as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:all", "-quiet")
            encoding = "UTF-8"
            charSet = "UTF-8"
        }
    }

    configure<CheckstyleExtension> {
        // Only lint production code. Test suites commonly rely on static-import
        // wildcards (org.junit.jupiter.api.Assertions.*, net.jqwik.api.*), which
        // is an accepted convention that would otherwise trip AvoidStarImport.
        sourceSets = listOf(the<SourceSetContainer>().getByName("main"))
    }

    configure<DependencyCheckExtension> {
        // NVD_API_KEY / NOVA_OWASP_FAIL_ON_CVSS are injected by reusable-owasp-check.yml.
        // Locally (no env vars set) this defaults to "never fail" (11.0, matches plugin default)
        // and an empty NVD key (slower updates, acceptable for local dev).
        failBuildOnCVSS = (System.getenv("NOVA_OWASP_FAIL_ON_CVSS") ?: "11").toFloat()
        nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""

        // Must match the path reusable-owasp-check.yml caches AND restores the
        // shared nova-devops NVD mirror into. Do NOT rely on the plugin's
        // built-in default here - it was never verified/documented and previous
        // cache sizes (15-57MB) strongly suggest it did not match what was
        // being cached. Locally (no env var set) this falls back to a plain,
        // dedicated directory outside ~/.gradle so it is never confused with
        // unrelated Gradle caches.
        data.directory = System.getenv("NOVA_OWASP_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.dependency-check-data"

        // Investigation (2026-07-13, docs/java/06-semantic-versioning-en-java.md):
        // a cold NVD sync took 50+ min mostly due to cache scoping, NOT these
        // analyzers - but disabling ecosystems that plainly do not exist
        // anywhere in this repo removes real (if smaller) analyze-phase
        // overhead and network surface at zero detection-feature cost.
        //
        // Deliberately NOT disabled: nodeEnabled / nodeAudit.enabled
        // (package.json IS present - commitlint/lefthook devDependencies -
        // keep scanning it for real) and opensslEnabled (harmless/fast).
        // RetireJS IS disabled: it fingerprints vendored/bundled JS *library*
        // files - this repo has no such files, only commitlint.config.js.
        analyzers {
            retirejs.enabled = false
            assemblyEnabled = false
            nuspecEnabled = false
            nugetconfEnabled = false
            msbuildEnabled = false
            golangDepEnabled = false
            golangModEnabled = false
            swiftEnabled = false
            swiftPackageResolvedEnabled = false
            cocoapodsEnabled = false
            composerEnabled = false
            cpanEnabled = false
            cmakeEnabled = false
            autoconfEnabled = false
            bundleAuditEnabled = false
            pyDistributionEnabled = false
            pyPackageEnabled = false
            rubygemsEnabled = false
            dartEnabled = false
        }
    }
}
