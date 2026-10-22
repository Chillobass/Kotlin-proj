dependencies {
    api(project(":common"))
    implementation(project(":tenders"))
    implementation(project(":users"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.slf4j:slf4j-api")

    testImplementation(testFixtures(project(":common")))
}
