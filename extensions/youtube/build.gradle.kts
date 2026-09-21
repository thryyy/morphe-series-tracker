import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    compileOnly(libs.annotation)
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared-youtube:stub"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))

    implementation(libs.collections4)
    implementation(libs.protobuf.javalite)

    // Series repository and callback-order regression coverage.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(libs.morphe.extensions.library)
    testImplementation(project(":extensions:shared:library"))
    testImplementation(project(":extensions:shared-youtube:library"))
}

configure<ApplicationExtension> {
    testOptions.unitTests.isIncludeAndroidResources = true

    defaultConfig {
        minSdk = 26
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
