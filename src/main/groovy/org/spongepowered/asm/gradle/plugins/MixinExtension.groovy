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
import java.io.File

/**
 * Extension object for mixin configuration, actually manages the configuration
 * of the mixin annotation processor and extensions to sourcesets 
 */
public class MixinExtension {
    
    /**
     * Cached reference to containing project 
     */
    private final Project project
    
    /**
     * Until we add some sourcesets, we will assume that the user hasn't
     * configured the plugin in any way (hasn't added a refMap setting on the
     * sourceSet or added any sourceSets in the mixin block). If this is the
     * case we will attach to all sourceSets in <tt>project.afterEvaluate</tt>
     * as our default behaviour. This flag is set to false as soon as 
     * {@link #add} is called from any source. 
     */
    private boolean applyDefault = true
    
    /**
     * Avoid adding duplicates by adding sourceSets to this collection
     */
    private Set<SourceSet> sourceSets = []
    
    /**
     * Mapping of refMap names to sourceSet names, used to avoid refMap
     * conflicts (or at least notify the user that a conflict has occurred)
     */
    private Map<String, String> refMaps = [:]
    
    /**
     * AP tokens, if this map is empty then the tokens argument will be omitted,
     * otherwise it will be compiled to a semicolon-separated list and passed
     * into the 
     */
    private Map<String, String> tokens = [:]
    
    /**
     * If a refMap overlap is detected a warning will be output, however there
     * are situations where a refMap overlap may be desired (for example if
     * different sourceSets are going into different jars) and thus the warning
     * can be ignored. Setting this value to true will suppress the warning.
     */
    boolean disableRefMapWarning
    
    /**
     * Disables the target validator in the mixin annotation processor.
     */
    boolean disableTargetValidator
    
    /**
     * Disables the target export in the mixin annotation processor, only
     * useful if multiple compile tasks need to be separated from the point of
     * view of the mixin AP. 
     */
    boolean disableTargetExport
    
    /**
     * The default obfuscation environment to use when generating refMaps. This
     * is the obfuscation set which will end up in the <tt>mappings</tt> node
     * in the generated refMap. 
     */
    Object defaultObfuscationEnv
    
    /**
     * By default we will attempt to find the SRG file to feed to the AP by
     * querying the <tt>genSrgs</tt> task, however the user can override the
     * file by setting this argument 
     */
    Object reobfSrgFile
    
    /**
     * By default we will attempt to find the SRG file to feed to the AP by
     * querying the <tt>genSrgs</tt> task, however the user can override the
     * file by setting this argument 
     */
    Object reobfNotchSrgFile
    
    /**
     * ctor
     * 
     * @param project reference to the containing project
     */
    MixinExtension(Project project) {
        this.project = project
        this.init()
    }
    
    /**
     * Set up the project by extending relevant objects and adding the
     * <tt>afterEvaluate</tt> handler
     */
    private void init() {
        this.project.afterEvaluate {
            this.applyDefault()
        }

        SourceSet.metaClass.getRefMap = {
            delegate.ext.refMap
        }
        
        SourceSet.metaClass.setRefMap = { value ->
            delegate.ext.refMap = value
            this.add(delegate)
        }
    }
    
    /**
     * Directive version of {@link #disableRefMapWarning}
     */
    void disableRefMapWarning() {
        this.disableRefMapWarning = true
    }
    
    /**
     * Directive version of {@link #disableTargetValidator}
     */
    void disableTargetValidator() {
        this.disableTargetValidator = true
    }
    
    /**
     * Directive version of {@link #disableTargetExport}
     */
    void disableTargetExport() {
        this.disableTargetExport = true
    }
    
    /**
     * Convenience getter so that "notch" can be used unqoted 
     */
    String getNotch() {
        NOTCH
    }
    
    /**
     * Convenience getter so that "searge" can be used unqoted 
     */
    String getSearge() {
        SEARGE
    }
    
    /**
     * Convenience getter so that "srg" can be used unqoted 
     */
    String getSrg() {
        SEARGE
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getReobfSrgFile() {
        this.reobfSrgFile != null ? project.file(this.reobfSrgFile) : project.tasks.genSrgs.mcpToSrg
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getReobfNotchSrgFile() {
        this.reobfNotchSrgFile != null ? project.file(this.reobfNotchSrgFile) : project.tasks.genSrgs.mcpToNotch
    }
    
    /**
     * Adds a boolean token with the value true
     * 
     * @param name boolean token name
     */
    void token(Object name) {
        token(name, "true")
    }
    
    /**
     * Adds a token with the specified value
     * 
     * @param name Token name
     * @param value Token value
     */
    void token(Object name, Object value) {
        this.tokens.put(name.toString().trim(), value.toString().trim())
    }
    
    /**
     * Add multiple tokens in one go by providing a map
     * 
     * @param map map of tokens to add
     * @return fluent interface
     */
    MixinExtension tokens(Map<String, ?> map) {
        for (Entry<String, ?> entry : map) {
            this.tokens.put(entry.key.trim(), entry.value.toString().trim())
        }
    }
    
    /**
     * Return current tokens as an unmodifyable map
     */
    Map<String, String> getTokens() {
        Collections.unmodifiableMap(this.tokens)
    }
    
    /**
     * Internal method which compiles the token map to an AP argument
     */
    @PackageScope String getTokenArgument() {
        def arg = '-Atokens='
        def first = true
        for (Entry<String, String> token : this.tokens) {
            if (token.value.indexOf(';') > -1) {
                throw new InvalidUserDataException(sprintf('Invalid token value \'%s\' for token \'%s\'', token.value, token.key))
            }
            if (!first) arg <<= ";"
            first = false
            arg <<= token.key << "=" << token.value
        }
        arg
    }
    
    /**
     * Handle the default behaviour of adding all sourceSets if no sourceSets
     * were explicitly added
     */
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
    
    /**
     * Add a sourceSet for mixin processing by name, the sourceSet must exist
     * and define the <tt>refMap</tt> property.
     * 
     * @param set SourceSet name
     */
    void add(String set) {
        this.add(project.sourceSets[set])
    }
    
    /**
     * Add a sourceSet for mixin processing, the sourceSet must define the
     * <tt>refMap</tt> property.
     * 
     * @param set SourceSet to add
     */
    void add(SourceSet set) {
        try {
            set.getRefMap()
        } catch (e) {
            throw new InvalidUserDataException(sprintf('No \'refMap\' defined on %s', set))
        }
        
        this.add(set, set.refMap.toString())
    }
    
    /**
     * Add a sourceSet by name for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet name
     * @param refMapName RefMap name
     */
    void add(String set, Object refMapName) {
        this.add(project.sourceSets[set], refMapName.toString())
    }
    
    /**
     * Add a sourceSet for mixin processing and specify the refMap name, the
     * SourceSet must exist
     * 
     * @param set SourceSet to add
     * @param refMapName RefMap name
     */
    void add(SourceSet set, Object refMapName) {
        this.add(set, refMapName.toString())
    }
    
    /**
     * Add a sourceSet for mixin processing and specify the refMap name, the
     * SourceSet must exist
     * 
     * @param set SourceSet to add
     * @param refMapName RefMap name
     */
    void add(SourceSet set, String refMapName) {
        // Check whether this sourceSet was already added
        if (this.sourceSets.contains(set)) {
            project.logger.info "Not adding {} to mixin processor, sourceSet already added", set
            return
        }

        project.logger.info "Adding {} to mixin processor", set
        
        // Get the sourceSet's compile task
        def compileTask = project.tasks[set.compileJavaTaskName]
        if (!(compileTask instanceof JavaCompile)) {
            throw new InvalidUserDataException(sprintf('Cannot add non-java %s to mixin processor', set))
        }
        
        // Don't perform default behaviour, a sourceSet has been added manually
        this.applyDefault = false

        // For closures below
        def refMaps = this.refMaps
        
        // Refmap file
        def refMapFile = project.file("${compileTask.temporaryDir}/${compileTask.name}-refmap.json")
        
        // Srg files
        def srgFiles = [
            (ReobfMappingType.SEARGE): project.file("${compileTask.temporaryDir}/mcp-srg.srg"),
            (ReobfMappingType.NOTCH): project.file("${compileTask.temporaryDir}/mcp-notch.srg")
        ]
        
        // Add our vars as extension properties to the sourceSet and compile
        // tasks, this will allow them to be used in the build script if needed
        compileTask.ext.outSrgFile = srgFiles[SEARGE]
        compileTask.ext.outNotchFile = srgFiles[NOTCH]
        compileTask.ext.refMap = refMapName
        compileTask.ext.refMapFile = refMapFile
        set.ext.refMap = refMapName
        set.ext.refMapFile = refMapFile
        
        // Closure to prepare AP environment before compile task runs
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
            this.applyCompilerArgs(compileTask)
        }

        // Closure to allocate generated AP resources once compile task
        // is completed
        compileTask.doLast {
            if (outSrgFile.exists() || outNotchFile.exists()) {
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
    
                    // No mapping set was matched, so add the searge mappings
                    if (!mapped) {
                        this.addMappings(task, SEARGE, srgFiles[SEARGE])
                    }
                }
            }
            
            // Add the refmap to all reobf'd jars
            if (refMapFile.exists()) {
                project.reobf.each {
                    def jar = project.tasks[it.name]
                    jar.from(refMapFile)
                    jar.rename(refMapFile.name, refMapName)
                }
            }
        }
    }
    
    /**
     * Callback from <tt>compileTask.doLast</tt> closure, attempts to contribute
     * mappings of the specified type to the supplied task
     * 
     * @param task an <tt>IReobfuscator</tt> instance (hopefully)
     * @param type Mapping type to add
     * @param srgFile SRG mapping file to add to the task
     */
    @PackageScope void addMappings(task, ReobfMappingType type, File srgFile) {
        if (!srgFile.exists()) {
            project.logger.warn "Unable to contribute {} mappings to {}, the specified file ({}) was not found", type, task.name, srgFile
            return    
        }
        
        project.logger.info "Contributing {} ({}) mappings to {}", type, srgFile, task.name
        task.extraFiles(srgFile)
    }
    
    /**
     * Callback from the <tt>compileTask.doFirst</tt> closure, configures the
     * annotation processor arguments based on the settings configured in this
     * extension.
     * 
     * @param compileTask Compile task to modify
     */
    @PackageScope void applyCompilerArgs(JavaCompile compileTask) {
        compileTask.options.compilerArgs += [
            "-AreobfSrgFile=${this.getReobfSrgFile().canonicalPath}",
            "-AreobfNotchSrgFile=${this.getReobfNotchSrgFile().canonicalPath}",
            "-AoutSrgFile=${compileTask.outSrgFile.canonicalPath}",
            "-AoutNotchSrgFile=${compileTask.outNotchFile.canonicalPath}",
            "-AoutRefMapFile=${compileTask.refMapFile.canonicalPath}"
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
