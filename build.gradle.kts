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

// Force patched versions of any transitive deps that carry known CVEs (CVSS >= 7).
// Versions verified against Maven Central 2026-07-13. Applied globally so they
// cover any classpath (compile, runtime, even buildscript transitives) so the
// OWASP gate reflects the real, patched state.
//
//  - Apache HttpComponents Core 4.4.16+ for CVE-2026-54428, CVE-2026-54399
//  - Apache HttpComponents Core5 5.4.2+ for CVE-2026-54428, CVE-2026-54399
//  - Apache Commons BeanUtils 1.11.0+ for CVE-2025-48734
//  - plexus-utils 3.5.1+ for CVE-2025-67030 (commit 6d780b3 per NVD)
configurations.all {
    resolutionStrategy.eachDependency {
            if (requested.group == "org.apache.httpcomponents" && requested.name.startsWith("httpcore")) {
                useVersion("4.4.16")
                because("CVE-2026-54428, CVE-2026-54399 require httpcore 4.4.16+")
            }
            if (requested.group == "org.apache.httpcomponents.core5" && requested.name.startsWith("httpcore5")) {
                useVersion("5.4.2")
                because("CVE-2026-54428, CVE-2026-54399 require httpcore5 5.4.2+")
            }
            if (requested.group == "commons-beanutils" && requested.name == "commons-beanutils") {
                useVersion("1.11.0")
                because("CVE-2025-48734 requires commons-beanutils 1.11.0+")
            }
            if (requested.group == "org.codehaus.plexus" && requested.name == "plexus-utils") {
                useVersion("3.5.1")
                because("CVE-2025-67030 requires plexus-utils 3.5.1+")
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

    // Force Tomcat 11.0.24 strictly - fixes 3 CVEs in Spring Boot 4.1.0's
    // transitive tomcat-embed-* deps (CVE-2026-53434, CVE-2026-55276,
    // CVE-2026-53404 - all in 11.0.22, fixed in 11.0.23+). Constraints
    // + strictly() used instead of resolutionStrategy.force() to avoid
    // a Gradle 9 config-cache serialization bug. Applied at subprojects
    // level so it covers both nova-mask-starter and nova-api-standard-starter.
    dependencies {
        constraints {
            "implementation"("org.apache.tomcat.embed:tomcat-embed-core") {
                version { strictly("11.0.24") }
                because("CVE-2026-53434, CVE-2026-55276, CVE-2026-53404 require 11.0.23+")
            }
            "implementation"("org.apache.tomcat.embed:tomcat-embed-websocket") {
                version { strictly("11.0.24") }
                because("Same CVEs in 11.0.22")
            }
            "implementation"("org.apache.tomcat.embed:tomcat-embed-el") {
                version { strictly("11.0.24") }
                because("Same CVEs in 11.0.22")
            }
        }
    }

    configure<DependencyCheckExtension> {
        // NVD_API_KEY / NOVA_OWASP_FAIL_ON_CVSS are injected by reusable-owasp-check.yml.
        // Locally (no env vars set) this defaults to "never fail" (11.0, matches plugin default)
        // and an empty NVD key (slower updates, acceptable for local dev).
        failBuildOnCVSS = (System.getenv("NOVA_OWASP_FAIL_ON_CVSS") ?: "11").toFloat()
        nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""

        // Restrict OWASP analysis to configurations that actually propagate to
        // consumers of this artifact. Without this, the plugin also scans test
        // configurations and (via the gradle daemon's own classpath) the buildscript
        // plugin transitives that NEVER reach a downstream project consuming this
        // artifact - for libraries this surfaces CVEs in httpcore, plexus-utils,
        // and commons-beanutils that are build-time only and not security-relevant
        // for library consumers.
        //
        // Verified pattern in nova-java-mask-utils PR#6 (commit f133fa2) and
        // nova-java-api-standard PR#3 (commit 8c7e89f).
        scanConfigurations = listOf("compileClasspath", "runtimeClasspath")

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

            // Per-call analyzer override (opt-in): the reusable-owasp-check.yml workflow
            // reads inputs.analyzer-override (or vars.DEPENDENCY_CHECK_ANALYZERS) and
            // exports it as NOVA_OWASP_ANALYZER_OVERRIDE. Re-enable here any analyzer
            // that this repo actually depends on (npm frontend, native C/C++ source,
            // etc.). The default above already disables every analyzer the current
            // build does not need. The Maven plugin has a richer analyzer set than the
            // Gradle one (verified by javap on dependency-check-gradle-12.2.2.jar): some
            // Maven-only tokens (gogradle, yarn, pnpm, pipenv, poetry, pyinstall,
            // setuptools, clang, haskell, mix, rebar, cargo) are accepted by the
            // reusable workflow but no-op for Gradle consumers. Format:
            // comma-separated, e.g. "npm,clang" or "npm, node, yarn".
            val enableAnalyzers = System.getenv("NOVA_OWASP_ANALYZER_OVERRIDE")
                ?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()
            if (enableAnalyzers.isNotEmpty()) {
                enableAnalyzers.forEach { name ->
                    when (name) {
                        "nuspec" -> nuspecEnabled = true
                        "nugetconf" -> nugetconfEnabled = true
                        "msbuild" -> msbuildEnabled = true
                        "golang", "go", "golangdep" -> golangDepEnabled = true
                        "golangmod", "gomod" -> golangModEnabled = true
                        "swift" -> { swiftEnabled = true; swiftPackageResolvedEnabled = true }
                        "cocoapods" -> cocoapodsEnabled = true
                        "composer" -> composerEnabled = true
                        "cpanfile", "perl" -> cpanEnabled = true
                        "cmake" -> cmakeEnabled = true
                        "autoconf" -> autoconfEnabled = true
                        "bundle", "ruby", "bundler" -> bundleAuditEnabled = true
                        "pydistribution" -> pyDistributionEnabled = true
                        "pypackage", "pip" -> pyPackageEnabled = true
                        "rubygems" -> rubygemsEnabled = true
                        "dart", "pub" -> dartEnabled = true
                        "assembly" -> assemblyEnabled = true
                        "retirejs" -> retirejs.enabled = true
                    }
                }
            }
        }
    }
}
