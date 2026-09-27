plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
