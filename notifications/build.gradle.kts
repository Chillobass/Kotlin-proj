dependencies {
    api(project(":common"))
    implementation(project(":users"))
    implementation(project(":tenders"))
    implementation(project(":bidding"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.slf4j:slf4j-api")

    testImplementation(testFixtures(project(":common")))
}
