import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.repositories

include(":app", ":contract", ":sdk-api", ":carpp-sdk", ":sdk-iccoa")


rootProject.name = "Car++"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        maven {
            url = uri("https://jitpack.io")
        }
    }
}
