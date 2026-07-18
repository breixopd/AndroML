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
include(":core:tools")
include(":core:agents")
include(":core:workflow")
include(":core:api")
include(":api:server")
include(":cluster:core")
include(":cluster:transport")
include(":runtime:api")
include(":runtime:litertlm")
include(":runtime:service")
include(":optimizer")
