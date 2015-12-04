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
package org.spongepowered.asm.gradle.plugins;

import java.util.Map.Entry
import org.eclipse.jdt.core.dom.ThisExpression;
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile

public class MixinExtension {
    
    private final Project project
    
    private Map<String, String> tokens = [:]
    private boolean disableTargetValidator
    private boolean disableTargetExport
    
    MixinExtension(Project project) {
        this.project = project
    }
    
    boolean isDisableTargetValidator() {
        this.disableTargetValidator
    }
    
    void setDisableTargetValidator(boolean disable) {
        this.disableTargetValidator = disable
    }
    
    void disableTargetValidator() {
        this.disableTargetValidator = true
    }
    
    boolean isDisableTargetExport() {
        this.disableTargetExport
    }
    
    void setDisableTargetExport(boolean disable) {
        this.disableTargetExport = disable
    }
    
    void disableTargetExport() {
        this.disableTargetExport = true
    }
    
    MixinExtension tokens(Map<String, ?> map) {
        for (Entry<String, ?> entry : map) { // not a closure because tokens is private
            tokens.put(entry.key.trim(), entry.value.toString().trim())
        }
    }
    
    Map<String, String> getTokens() {
        Collections.unmodifiableMap(this.tokens)
    }
    
    String getTokenArgument() {
        def arg = '-Atokens='
        def first = true
        for (Entry<String, String> token : this.tokens) {
            if (!first) arg <<= ";"
            first = false
            arg <<= token.key << "=" << token.value
        }
        arg
    }
    
    void add(String set, String refMapName) {
        this.add(project.sourceSets[set], refMapName)
    }
    
    void add(SourceSet set, String refMapName) {
        def compileTask = project.tasks[set.compileJavaTaskName]
        def outSrgFile = project.file("${compileTask.temporaryDir}/mcp-srg.srg")
        def outNotchFile = project.file("${compileTask.temporaryDir}/mcp-notch.srg")
        def refMapFile = project.file("${compileTask.temporaryDir}/${compileTask.name}-refmap-srg.json")
        def notchRefMapFile = project.file("${compileTask.temporaryDir}/${compileTask.name}-refmap-notch.json")
        
        compileTask.ext.outSrgFile = outSrgFile
        compileTask.ext.outNotchFile = outNotchFile
        set.ext.refMapName = refMapName
        set.ext.srgRefMap = refMapFile
        set.ext.notchRefMap = notchRefMapFile
        
        compileTask.doFirst {
            if (compileTask instanceof JavaCompile) {
                this.applyCompilerArgs(compileTask, refMapFile, notchRefMapFile);
            }
        }
        
        compileTask.doLast {
            if (outSrgFile.exists()) {
                project.reobf.each {
                    it.extraFiles(compileTask.outSrgFile)
                }
            }
            
            if (refMapFile.exists()) {
                project.reobf.each {
                    def jar = project.tasks[it.name]
//                    if ("NOTCH" == jar.mappingType) {
                        jar.from(notchRefMapFile)
                        jar.rename(notchRefMapFile.name, refMapName)
//                    } else if ("SEARGE" == jar.mappingType) {
//                        jar.from(rRefMapFile)
//                        jar.rename(notchRefMapFile.name, refMapName)
//                    }
                }
            }
        }
    }
    
    void applyCompilerArgs(JavaCompile compileTask, File refMapFile, File notchRefMapFile) {
        compileTask.options.compilerArgs += [
            "-AreobfSrgFile=${project.tasks.genSrgs.mcpToSrg.canonicalPath}",
            "-AreobfNotchSrgFile=${project.tasks.genSrgs.mcpToNotch.canonicalPath}",
            "-AoutSrgFile=${compileTask.ext.outSrgFile.canonicalPath}",
            "-AoutNotchSrgFile=${compileTask.ext.outNotchFile.canonicalPath}",
            "-AoutRefMapFile=${refMapFile.canonicalPath}",
            "-AoutNotchRefMapFile=${notchRefMapFile.canonicalPath}"
        ]
        
        if (this.disableTargetValidator) {
            compileTask.options.compilerArgs += '-AdisableTargetValidator=true'
        }
        
        if (this.disableTargetExport) {
            compileTask.options.compilerArgs += '-AdisableTargetExport=true'
        }
        
        if (this.tokens.size() > 0) {
            compileTask.options.compilerArgs += this.tokenArgument
        }
    }

}
