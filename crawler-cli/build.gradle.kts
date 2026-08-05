plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":shared"))
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.4")
    implementation("org.apache.commons:commons-csv:1.14.1")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

application {
    mainClass.set("cn.gzuoj.crawler.CrawlerCliKt")
}
