pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroML"

include(":app")
include(":core:model")
include(":core:files")
include(":core:database")
include(":core:security")
include(":core:device")
include(":core:network")
include(":core:rag")
include(":runtime:api")
include(":runtime:service")
include(":optimizer")
