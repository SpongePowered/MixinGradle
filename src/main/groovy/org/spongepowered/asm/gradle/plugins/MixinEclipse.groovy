/*
 * This file is part of MixinGradle, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.spongepowered.asm.gradle.plugins

import groovy.xml.MarkupBuilder
import org.gradle.api.tasks.InputFiles

import java.util.Collections
import java.util.Enumeration
import java.util.Properties
import java.util.TreeMap

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Eclipse Specific intergration
 */
public class MixinEclipse {
    static void configureEclipse(MixinExtension extension, Project project, String projectType/*, File reobf, File outRef, File outMapping*/) {
        def eclipseModel = project.extensions.findByName('eclipse')
        if (!eclipseModel) {
            project.logger.lifecycle '[MixinGradle] Skipping eclipse integration, extension not found'
            return
        }
        
        eclipseModel.jdt.file.withProperties { it.setProperty('org.eclipse.jdt.core.compiler.processAnnotations', 'enabled') }
        def settings = project.tasks.register('eclipseJdtApt', EclipseJdtAptTask.class) {
            description = 'Creates the Eclipse JDT APT settings file'
            output = project.file('.settings/org.eclipse.jdt.apt.core.prefs')
            mappingsIn = extension.mappings
        }
        project.tasks.eclipse.dependsOn(settings)
        
        def factories = project.tasks.register('eclipseFactoryPath', EclipseFactoryPath.class) {
            config = project.configurations.annotationProcessor
            output = project.file('.factorypath')
        }
        project.tasks.eclipse.dependsOn(factories)
        
    }
    
    static class OrderedProperties extends Properties {
        def order = new LinkedHashSet<Object>()
        
        @Override
        public synchronized Enumeration<Object> keys() {
            return Collections.enumeration(order)
        }
        
        @Override
        public synchronized Object put(Object key, Object value) {
            order.add(key)
            return super.put(key, value)
        }
        
        public Object arg(String key, String value) {
            return put('org.eclipse.jdt.apt.processorOptions/' + key, value)
        }
    }
    
    static class EclipseJdtAptTask extends DefaultTask {
        @InputFile File mappingsIn
        @InputFile File refmapOut = project.file("build/${name}/mixins.refmap.json")
        @InputFile File mappingsOut = project.file("build/${name}/mixins.mappings.tsrg")
        @Input Map<String, String> processorOptions = new TreeMap<>()
        
        @InputFile File genTestDir = project.file('build/.apt_generated_test')
        @InputFile File genDir = project.file('build/.apt_generated')
        
        @OutputFile File output
        
        
        @TaskAction
        def run() {
            MixinExtension extension = project.extensions.findByType(MixinExtension.class)
            def props = new OrderedProperties()
            props.put('eclipse.preferences.version', '1')
            props.put('org.eclipse.jdt.apt.aptEnabled', 'true')
            props.put('org.eclipse.jdt.apt.reconcileEnabled', 'true')
            props.put('org.eclipse.jdt.apt.genSrcDir', genDir.canonicalPath)
            props.put('org.eclipse.jdt.apt.genSrcTestDir', genTestDir.canonicalPath)
            props.arg('reobfTsrgFile', mappingsIn.canonicalPath)
            props.arg('outTsrgFile', mappingsOut.canonicalPath)
            props.arg('outRefMapFile', refmapOut.canonicalPath)
            props.arg('pluginVersion', MixinGradlePlugin.VERSION)
            
            if (extension.disableTargetValidator) {
                props.arg('disableTargetValidator', 'true')
            }
            
            if (extension.disableTargetExport) {
                props.arg('disableTargetExport', 'true')
            }
            
            if (extension.disableOverwriteChecker) {
                props.arg('disableOverwriteChecker', 'true')
            }
            
            if (extension.overwriteErrorLevel != null) {
                props.arg('overwriteErrorLevel', extension.overwriteErrorLevel.toString().trim())
            }
            
            if (extension.defaultObfuscationEnv != null) {
                props.arg('defaultObfuscationEnv', extension.defaultObfuscationEnv)
            }
            
            if (extension.mappingTypes.size() > 0) {
                props.arg('mappingTypes', extension.mappingTypes.join(','))
            }

            if (extension.tokens.size() > 0) {
                props.arg('tokens', extension.tokens.collect { token -> token.key + ' ' + token.value }.join(';'))
            }
            
            if (extension.extraMappings.size() > 0) {
                props.arg('reobfTsrgFiles', extension.extraMappings.collect { file -> project.file(file).toString() }.join(';'))
            }

            /* TODO... Not sure what this is
            File importsFile = extension.generateImportsFile(compileTask)
            if (importsFile != null) {
                compileTask.options.compilerArgs += "-AdependencyTargetsFile=${importsFile.canonicalPath}"
            }
            */
            
            props.store(output.newWriter(), null)
        }
    }

    static class EclipseFactoryPath extends DefaultTask {
        @InputFiles Configuration config
        @OutputFile File output
        
        @TaskAction
        def run() {
            output.withWriter {
                new MarkupBuilder(it).'factorypath' {
                    config.resolvedConfiguration.resolvedArtifacts.each { dep-> factorypathentry( kind: 'EXTJAR', id: dep.file.absolutePath, enabled: true, runInBatchMode: false) }
                }
            }    
        }
    }
}
