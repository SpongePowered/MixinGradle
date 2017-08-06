![MixinGradle Logo](docs/logo.png?raw=true)

**MixinGradle** is a [Gradle](http://gradle.org/) plugin which simplifies the build-time complexity of working with the **[SpongePowered Mixin](/SpongePowered/Mixin)** framework for Java. It currently only supports usage with **[ForgeGradle](MinecraftForge/ForgeGradle)**.

### Features

**MixinGradle** automates the following tasks:

* Locating (via **ForgeGradle**) and supplying input SRG files to the [Mixin](/SpongePowered/Mixin) [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Providing processing options to the [Annotation Processor](https://github.com/SpongePowered/Mixin/wiki/Using-the-Mixin-Annotation-Processor)
* Contributing the generated [reference map (refmap)](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---Obfuscation-and-Mixins#511-the-mixin-reference-map-refmap) to the corresponding sourceSet compile task outputs
* Contributing the generated SRG files to appropriate **ForgeGradle** `reobf` tasks

### Using MixinGradle

To use **MixinGradle** you *must* be using **[ForgeGradle](MinecraftForge/ForgeGradle)**. To configure the plugin for your build:

1. Add a source repository to your `buildScript -> dependencies` block:

 ```groovy
buildscript {
        repositories {
            <add source repository here>
        }
        dependencies {
            ...
            classpath 'org.spongepowered:mixingradle:0.5-SNAPSHOT'
        }
}
 ```
2. Apply the plugin:
 
 ```groovy
 apply plugin: 'org.spongepowered.mixin'
 ```
 
3. Create your `mixin` block, specify which sourceSets to process and provide refmap resource names for each one, the generated refmap will be added to the compiler task outputs automatically.
 
 ```groovy
mixin {
        add sourceSets.main, "main.refmap.json"
        add sourceSets.another, "another.refmap.json"
}
 ```
  
4. Alternatively, you can simply specify the `ext.refMap` property directly on your sourceSet:
 
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
 
5. You can define other mixin AP options in the `mixin` block, for example `disableTargetValidator` and `disableTargetExport` can be configured either by setting them as boolean properties:
 
 ```groovy
 mixin {
        disableTargetExport = true
        disableTargetValidator = true
 }
 ```
 
 or simply issuing them as directives:
 
 ```groovy
 mixin {
        disableTargetExport
        disableTargetValidator
 }
 ```
 
 You can also set the default obfuscation environment for generated refmaps, this is the obfuscation environment which will be contributed to the refmap's `mappings` node:
 
 ```groovy
 mixin {
        // Specify "notch" or "searge" here
        defaultObfuscationEnv notch
 }
 ```
 
### Building MixinGradle
**MixinGradle** can of course be built using [Gradle](http://gradle.org/). To perform a build simply execute:

    gradle

To add the compiled jar to your local maven repository, run:

    gradle build install



