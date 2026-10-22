plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "tenderhub"

include(
    "common",
    "users",
    "tenders",
    "bidding",
    "notifications",
    "app",
)
