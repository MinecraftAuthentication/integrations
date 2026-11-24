plugins {
    id("mcauth.kotlin-serialization")
    `maven-publish`
}

base.archivesName.set("lib")

dependencies {
    api(libs.kotlinx.serialization.json)
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifactId = "lib"
        from(components["java"])
        pom {
            name.set("MinecraftAuth lib")
            description.set("JVM client for the MinecraftAuth.me v2 API")
            url.set("https://github.com/MinecraftAuthentication/integrations")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
    repositories {
        maven("https://nexus.scarsz.me/content/repositories/releases/") {
            name = "scarsz"
            credentials(PasswordCredentials::class)
        }
    }
}
