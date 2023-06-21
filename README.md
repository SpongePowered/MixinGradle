![MixinGradle Logo](docs/logo.png?raw=true)

**MixinGradle** is a [Gradle](http://gradle.org/) plugin which simplifies the build-time complexity of working with the **[SpongePowered Mixin](https://github.com/SpongePowered/Mixin)** framework for Java. It currently only supports usage with **[ForgeGradle](https://github.com/MinecraftForge/ForgeGradle)**.

### Features

**MixinGradle** automates the following tasks:

* Locating (via **ForgeGradle**) and supplying input mapping files to the [Mixin](https://github.com/SpongePowered/Mixin) [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Providing processing options to the [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Contributing the generated [reference map (refmap)](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---Obfuscation-and-Mixins#511-the-mixin-reference-map-refmap) to the corresponding sourceSet compile task outputs
* Contributing the generated SRG files to appropriate **ForgeGradle** `reobf` tasks

### Using MixinGradle

To use **MixinGradle** you *must* be using **[ForgeGradle](https://github.com/MinecraftForge/ForgeGradle)**. To configure the plugin for your build:

1. Add a source repository and the MixinGradle dependency to your `buildScript -> dependencies` block:
Groovy DLS:
If Gradle version is lower 4.0
```groovy
buildscript {
        repositories {
            <add source repository here>
        }
        dependencies {
            ...
            classpath 'org.spongepowered:mixingradle:0.7-SNAPSHOT'
        }
}
 ```

If gradle higher 4.0: 
`build.gradle`:
```groovy
buildscript {
        dependencies {
            ...
            classpath 'org.spongepowered:mixingradle:0.7-SNAPSHOT'
        }
}
```
`settings.gradle`:
```groovy
pluginManagement {
    repositories {
        ...
        maven "https://repo.spongepowered.org/repository/maven-public/" 
    }
}
```

Kotlin DSL: 

If Gradle version is lower 4.0
```groovy
buildscript {
        repositories {
            <add source repository here>
        }
        dependencies {
            ...
            classpath("org.spongepowered:mixingradle:0.7-SNAPSHOT")
        }
}
 ```

If gradle higher 4.0: 
`build.gradle`:
```kotlin
buildscript {
        dependencies {
            ...
            classpath("org.spongepowered:mixingradle:0.7-SNAPSHOT")
        }
}
```
`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        ...
        maven("https://repo.spongepowered.org/repository/maven-public/")
    }
}
```

Please ensure you are using the correct version of MixinGradle for your ForgeGradle version. Versions are not interchangeable. 

| ForgeGradle Version | Mixin Version   | MixinGradle Version To Use |
| ------------------- | --------------- | -------------------------- |
| 2.3                 | 0.8 and below   | `0.6-SNAPSHOT`             |
| 3.0+                | 0.8             | `0.7-SNAPSHOT`             |
 
2. Apply the plugin:
Groovy DSL:
 ```groovy
 apply plugin: 'org.spongepowered.mixin'
 ```
Kotlin DSL:
```kotlin
apply("org.spongepowered.mixin")
```
 
3. Create your `mixin` block, specify which sourceSets to process and provide refmap resource names for each one, the generated refmap will be added to the compiler task outputs automatically.
 Groovy DSL:
 ```groovy
mixin {
        add sourceSets.main, "main.refmap.json"
        add sourceSets.another, "another.refmap.json"
}
 ```
Kotlin DSL:
 ```kotlin
 import org.spongepowered.asm.gradle.plugins.MixinExtension
 configure<MixinExtension> {
        add(sourceSets["main"], "main.refmap.json")
        add(sourceSets["another"], "main.refmap.json")
 }
 ```
  
4. Alternatively, you can simply specify the `ext.refMap` property directly on your sourceSet:
 Groovy DSL:
 ```groovy
sourceSets {
        main {
            ext.refMap = "main.refmap.json"
        }
        another {
            ext.refMap = "another.refmap.json"
        }
}
 ```
Kotlin DSL:
```kotlin
sourceSets {
        named("main") {
             ext.set("refMap","main.refmap.json")
        }
        create("another") {
             ext.set("refMap","another.refmap.json")
        }
}
```
 
5. You can define other mixin AP options in the `mixin` block, for example `disableTargetValidator` and `disableTargetExport` can be configured either by setting them as boolean properties:
 Groovy DSL:
 ```groovy
 mixin {
        disableTargetExport = true
        disableTargetValidator = true
 }
 ```
```kotlin
 configure<MixinExtension> {
        disableTargetExport = true
        disableTargetValidator = true
 }
 ```
 
 or simply issuing them as directives:
 Groovy DSL:
 ```groovy
 mixin {
        disableTargetExport
        disableTargetValidator
 }
 ```
 Kotlin DSL:
 ```kotlin
 configure<MixinExtension> {
        disableTargetExport
        disableTargetValidator
 }
 ```
 
 You can also set the default obfuscation environment for generated refmaps, this is the obfuscation environment which will be contributed to the refmap's `mappings` node:
 Groovy DSL: 
 ```groovy
 mixin {
        // Specify "notch" or "searge" here
        defaultObfuscationEnv notch
 }
 ```
 Kotlin DSL:
 ```kotlin
 configure<MixinExtension> {
        // Specify "notch" or "searge" here
        defaultObfuscationEn("notch")
 }
 ```
 
### Building MixinGradle
**MixinGradle** can of course be built using [Gradle](http://gradle.org/). To perform a build simply execute:

    gradlew build

To add the compiled jar to your local maven repository, run:

    gradlew publishToMavenLocal



