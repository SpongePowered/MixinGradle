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

import static org.spongepowered.asm.gradle.plugins.ReobfMappingType.*

import groovy.transform.PackageScope
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile

import java.util.Map.Entry

public class MixinExtension {
    
    private final Project project
    
    private boolean applyDefault = true
    
    private Set<SourceSet> sourceSets = []
    private Map<String, String> refMaps = [:]
    private Map<String, String> tokens = [:]
    
    boolean disableRefMapWarning
    boolean disableTargetValidator
    boolean disableTargetExport
    Object defaultObfuscationEnv
    
    MixinExtension(Project project) {
        this.project = project
    }
    
    void disableRefMapWarning() {
        this.disableRefMapWarning = true
    }
    
    void disableTargetValidator() {
        this.disableTargetValidator = true
    }
    
    void disableTargetExport() {
        this.disableTargetExport = true
    }
    
    String getNotch() {
        NOTCH
    }
    
    String getSearge() {
        SEARGE
    }
    
    String getSrg() {
        SEARGE
    }
    
    void token(Object name) {
        token(name, "true")
    }
    
    void token(Object name, Object value) {
        this.tokens.put(name.toString().trim(), value.toString().trim())
    }
    
    MixinExtension tokens(Map<String, ?> map) {
        for (Entry<String, ?> entry : map) {
            this.tokens.put(entry.key.trim(), entry.value.toString().trim())
        }
    }
    
    Map<String, String> getTokens() {
        Collections.unmodifiableMap(this.tokens)
    }
    
    @PackageScope String getTokenArgument() {
        def arg = '-Atokens='
        def first = true
        for (Entry<String, String> token : this.tokens) {
            if (!first) arg <<= ";"
            first = false
            arg <<= token.key << "=" << token.value
        }
        arg
    }
    
    @PackageScope void applyDefault() {
        if (this.applyDefault) {
            this.applyDefault = false
            project.logger.info "No sourceSets added for mixin processing, applying defaults"
            this.disableRefMapWarning = true
            project.sourceSets.each { set -> 
                this.add(set, "mixin.refmap.json")
            }
        }
    }
    
    void add(String set) {
        this.add(project.sourceSets[set])
    }
    
    void add(SourceSet set) {
        try {
            set.getRefMap()
        } catch (e) {
            throw new InvalidUserDataException(sprintf('No \'refMap\' defined on %s', set))
        }
        
        this.add(set, set.refMap.toString())
    }
    
    void add(String set, Object refMapName) {
        this.add(project.sourceSets[set], refMapName.toString())
    }
    
    void add(SourceSet set, Object refMapName) {
        this.add(set, refMapName.toString())
    }
    
    void add(SourceSet set, String refMapName) {
        if (this.sourceSets.contains(set)) {
            project.logger.info "Not adding {} to mixin processor, sourceSet already added", set
            return
        }

        project.logger.info "Adding {} to mixin processor", set
        
        def compileTask = project.tasks[set.compileJavaTaskName]
        if (!(compileTask instanceof JavaCompile)) {
            throw new InvalidUserDataException(sprintf('Cannot add non-java %s to mixin processor', set))
        }
        
        this.applyDefault = false
            
        def refMaps = this.refMaps
        def refMapFile = project.file("${compileTask.temporaryDir}/${compileTask.name}-refmap.json")
        def srgFiles = [
            (ReobfMappingType.SEARGE): project.file("${compileTask.temporaryDir}/mcp-srg.srg"),
            (ReobfMappingType.NOTCH): project.file("${compileTask.temporaryDir}/mcp-notch.srg")
        ]
        
        compileTask.ext.outSrgFile = srgFiles[SEARGE]
        compileTask.ext.outNotchFile = srgFiles[NOTCH]
        compileTask.ext.refMap = refMapName
        compileTask.ext.refMapFile = refMapFile
        set.ext.refMap = refMapName
        set.ext.refMapFile = refMapFile
        
        compileTask.doFirst {
            if (!this.disableRefMapWarning && refMaps[refMapName]) {
                project.logger.warn "Potential refmap conflict. Duplicate refmap name {} specified for sourceSet {}, already defined for sourceSet {}",
                    refMapName, set.name, refMaps[refMapName]
            } else {
                refMaps[refMapName] = set.name
            }
            
            refMapFile.delete()
            srgFiles.each {
                it.value.delete()
            }
            this.applyCompilerArgs(compileTask, refMapFile)
        }

        compileTask.doLast {
            if (outSrgFile.exists()) {
                project.reobf.each { task ->
                    def mapped = false
                    [task.mappingType, this.defaultObfuscationEnv.toString()].each { arg ->
                        ReobfMappingType.each { type ->
                            if (type.matches(arg) && !mapped) {
                                this.addMappings(task, type, srgFiles[type])
                                mapped = true
                            }
                        }
                    }

                    if (!mapped) {
                        this.addMappings(task, SEARGE, srgFiles[SEARGE])
                    }
                }
            }
            
            if (refMapFile.exists()) {
                project.reobf.each {
                    def jar = project.tasks[it.name]
                    jar.from(refMapFile)
                    jar.rename(refMapFile.name, refMapName)
                }
            }
        }
    }
    
    @PackageScope void addMappings(task, type, srgFile) {
        project.logger.info "Contributing {} ({}) mappings to {}", type, srgFile, task.name
        task.extraFiles(srgFile)
    }
    
    @PackageScope void applyCompilerArgs(JavaCompile compileTask, File refMapFile) {
        compileTask.options.compilerArgs += [
            "-AreobfSrgFile=${project.tasks.genSrgs.mcpToSrg.canonicalPath}",
            "-AreobfNotchSrgFile=${project.tasks.genSrgs.mcpToNotch.canonicalPath}",
            "-AoutSrgFile=${compileTask.outSrgFile.canonicalPath}",
            "-AoutNotchSrgFile=${compileTask.outNotchFile.canonicalPath}",
            "-AoutRefMapFile=${refMapFile.canonicalPath}"
        ]
        
        if (this.disableTargetValidator) {
            compileTask.options.compilerArgs += '-AdisableTargetValidator=true'
        }
        
        if (this.disableTargetExport) {
            compileTask.options.compilerArgs += '-AdisableTargetExport=true'
        }
        
        if (this.defaultObfuscationEnv != null) {
            compileTask.options.compilerArgs += "-AdefaultObfuscationEnv=${this.defaultObfuscationEnv.toLowerCase()}"
        }
        
        if (this.tokens.size() > 0) {
            compileTask.options.compilerArgs += this.tokenArgument
        }
    }

}
