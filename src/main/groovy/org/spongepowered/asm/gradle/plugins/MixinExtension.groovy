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

import com.google.common.io.Files
import groovy.lang.MissingPropertyException
import groovy.transform.PackageScope
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.internal.impldep.bsh.This
import org.gradle.jvm.tasks.Jar
import org.spongepowered.asm.gradle.plugins.meta.Import
import org.spongepowered.asm.gradle.plugins.meta.Imports

import java.util.HashSet
import java.util.Map.Entry
import java.io.File

/**
 * Extension object for mixin configuration, actually manages the configuration
 * of the mixin annotation processor and extensions to sourcesets 
 */
public class MixinExtension {
    
    static class ReobfTask {
        final Project project
        final Object handle
        
        ReobfTask(Project project, Object handle) {
            this.project = project
            this.handle = handle
        }
        
        Jar getJar() {
            this.handle
        }
        
        String getName() {
            this.handle.name
        }
    }
    
    static class AddRefMapToJarTask extends DefaultTask {
        
        Jar remappedJar
        
        Set<ReobfTask> reobfTasks
        
        Set<File> jarRefMaps = []
        
        @TaskAction
        def run() {
            // Add the refmap to all reobf'd jars
            this.reobfTasks.each { reobfTask ->
                reobfTask.handle.dependsOn.findAll { it == remappedJar }.each { jar ->
                    jarRefMaps.each { artefactSpecificRefMap ->                        
                        project.logger.info "Contributing refmap ({}) to {} in {}", artefactSpecificRefMap, jar.archiveName, reobfTask.project
                        jar.getRefMaps().from(artefactSpecificRefMap)
                        jar.from(artefactSpecificRefMap)
                    }
                }
            }
        }
    }

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
     * Reobf tasks we will target
     */
    @PackageScope Set<ReobfTask> reobfTasks = []
    
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
     * Disables the overwrite checking functionality which raises warnings when
     * overwrite methods are not appropriately decorated 
     */
    boolean disableOverwriteChecker
    
    /**
     * Sets the overwrite checker error level, the default is to raise WARNING
     * however this can be set to ERROR in order to cause missing decorations
     * to be treated as errors 
     */
    Object overwriteErrorLevel
    
    /**
     * The default obfuscation environment to use when generating refMaps. This
     * is the obfuscation set which will end up in the <tt>mappings</tt> node
     * in the generated refMap. 
     */
    String defaultObfuscationEnv = "searge"
    
    /**
     * Mapping types list to pass to the AP. Mapping services may parse the
     * supplied options to determine whether they should activate. 
     */
    List<Object> mappingTypes = [ "tsrg" ]

    /**
     * By default we will attempt to find the SRG file to feed to the AP by
     * querying the <tt>genSrgs</tt> task, however the user can override the
     * file by setting this argument 
     */
    Object reobfSrgFile
    
    /**
     * Additional TSRG mapping files to supply to the annotation processor. LTP.
     */
    private List<Object> extraMappings = []
    
    /**
     * Configurations to scan for dependencies when running AP
     */
    private Set<Object> importConfigs = []
    
    /**
     * Additional libraries to scan when running AP
     */
    private Set<Object> importLibs = []

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
        Project proj = this.project
        
        this.project.afterEvaluate {
            // Gather reobf jars for processing
            proj.reobf.each { reobfTaskHandle ->
                this.reobfTasks += new ReobfTask(proj, reobfTaskHandle)
            }

            // Search for sourceSets with a refmap property and configure them
            proj.sourceSets.each { set ->
                if (set.ext.has("refMap")) {
                    this.configure(set)
                }
            }

            // Search for upstream projects and add our jars to their target set
            proj.configurations.compile.allDependencies.withType(ProjectDependency) { upstream ->
                def mixinExt = upstream.dependencyProject.extensions.findByName("mixin")
                if (mixinExt) {
                    proj.reobf.each { reobfTaskWrapper ->
                        mixinExt.reobfTasks += new ReobfTask(proj, reobfTaskWrapper)
                    }
                }
            }
            
            this.applyDefault()
        }

        SourceSet.metaClass.getRefMap = {
            delegate.ext.refMap
        }
        
        SourceSet.metaClass.setRefMap = { value ->
            delegate.ext.refMap = value
        }
        
        AbstractArchiveTask.metaClass.getRefMaps = {
            if (!delegate.ext.has('refMaps')) {
                delegate.ext.refMaps = proj.layout.configurableFiles();
            }
            delegate.ext.refMaps
        }
        
        AbstractArchiveTask.metaClass.setRefMaps = { value ->
            delegate.ext.refMaps = value
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
     * Directive version of {@link #disableOverwriteChecker}
     */
    void disableOverwriteChecker() {
        this.disableOverwriteChecker = true
    }
    
    /**
     * Directive version of {@link #disableOverwriteChecker}
     */
    void overwriteErrorLevel(Object errorLevel) {
        this.overwriteErrorLevel = errorLevel
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getMappings() {
        this.reobfSrgFile != null ? project.file(this.reobfSrgFile) : project.tasks.createMcpToSrg.outputs.files[0]
    }
    
    /**
     * Adds an additional TSRG file for the AP to consume.
     * 
     * @param file Object which resolves to a file
     */
    void extraMappings(Object file) {
        this.extraMappings += file
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
     * Sanity check current tokens before passing them to the AP
     */
    @PackageScope void checkTokens() {
        this.tokens.find { it.value.contains(';') }.each {
            throw new InvalidUserDataException("Invalid token value '${it.value}' for token '${it.key}'")
        }
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
                if (!set.ext.has("refMap")) {
                    set.ext.refMap = "mixin.refmap.json"
                }
                this.configure(set)
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
        
        project.afterEvaluate {
            this.configure(set)
        }
    }
    
    /**
     * Add a sourceSet by name for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet name
     * @param refMapName RefMap name
     */
    void add(String set, Object refMapName) {
        SourceSet sourceSet = project.sourceSets.findByName(set)
        if (sourceSet == null) {
            throw new InvalidUserDataException(sprintf('No \'refMap\' defined on %s', set))
        }
        sourceSet.ext.refMap = refMapName
        project.afterEvaluate {
            this.configure(sourceSet)
        }
    }
    
    /**
     * Add a sourceSet for mixin processing and specify the refMap name, the
     * SourceSet must exist
     * 
     * @param set SourceSet to add
     * @param refMapName RefMap name
     */
    void add(SourceSet set, Object refMapName) {
        set.ext.refMap = refMapName.toString()
        project.afterEvaluate {
            this.configure(set)
        }
    }
    
    /**
     * Configure a sourceSet for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet to add
     */
    void configure(SourceSet set) {
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
        
        // Generated tsrg file
        def tsrgFile = project.file("${compileTask.temporaryDir}/${compileTask.name}-mappings.tsrg")
        
        // Add our vars as extension properties to the sourceSet and compile
        // tasks, this will allow them to be used in the build script if needed
        compileTask.ext.outTsrgFile = tsrgFile
        compileTask.ext.refMapFile = refMapFile
        set.ext.refMapFile = refMapFile
        compileTask.ext.refMap = set.ext.refMap.toString()

        // We need createMcpToSrg to run in order to generate the mappings
        // consumed by the AP        
        compileTask.dependsOn("createMcpToSrg")
        
        // Closure to prepare AP environment before compile task runs
        compileTask.doFirst {
            if (!this.disableRefMapWarning && refMaps[compileTask.ext.refMap]) {
                project.logger.warn "Potential refmap conflict. Duplicate refmap name {} specified for sourceSet {}, already defined for sourceSet {}",
                    compileTask.ext.refMap, set.name, refMaps[compileTask.ext.refMap]
            } else {
                refMaps[compileTask.ext.refMap] = set.name
            }
            
            refMapFile.delete()
            tsrgFile.delete()
            
            this.checkTokens()
            this.applyCompilerArgs(compileTask)
        }

        // Refmap is generated with a generic name, rename to sourceset-specific
        // name ready for inclusion into target jar. We can't use rename in the
        // jar spec because there may be multiple refmaps with the same source
        // name
        File taskSpecificRefMap = new File(refMapFile.parentFile, compileTask.ext.refMap)

        // Closure to rename generated refMap to artefact-specific refmap when
        // compile task is completed
        compileTask.doLast {
            // Delete the old one
            taskSpecificRefMap.delete()

            // Copy the new one if it was successfully generated
            if (refMapFile.exists()) {
                Files.copy(refMapFile, taskSpecificRefMap) 
            }
        }
        
        // Create a task to contribute the refmap to the jar. Since we don't
        // know at this point which jars are going to be reobfuscated (the
        // inputs to reobf tasks are lazily evaluated at the dependsOn isn't
        // added until later) we add one such task for every jar and the task
        // can handle the heavy lifting of figuring out what to contribute
        project.tasks.withType(Jar.class) { jarTask ->
            project.tasks.maybeCreate("addRefMapTo${jarTask.name.capitalize()}", AddRefMapToJarTask.class).configure {
                dependsOn(compileTask)
                remappedJar = jarTask
                reobfTasks = this.reobfTasks
                jarRefMaps += taskSpecificRefMap
                jarTask.dependsOn(delegate)
            }
        }

        // Closure to allocate generated AP resources once compile task is completed
        this.reobfTasks.each { reobfTask ->
            reobfTask.handle.doFirst {
                if (tsrgFile.exists()) {
                    project.logger.info "Contributing tsrg mappings ({}) to {} in {}", tsrgFile, reobfTask.name, reobfTask.project
                    delegate.extraMapping(tsrgFile)
                } else {
                    project.logger.debug "Tsrg file ({}) not found, skipping", tsrgFile
                }
            }
        }
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
            "-AreobfTsrgFile=${this.mappings.canonicalPath}",
            "-AoutTsrgFile=${compileTask.outTsrgFile.canonicalPath}",
            "-AoutRefMapFile=${compileTask.refMapFile.canonicalPath}",
            "-AmappingTypes=tsrg",
            "-ApluginVersion=${MixinGradlePlugin.VERSION}"
        ]
        
        if (this.disableTargetValidator) {
            compileTask.options.compilerArgs += '-AdisableTargetValidator=true'
        }
        
        if (this.disableTargetExport) {
            compileTask.options.compilerArgs += '-AdisableTargetExport=true'
        }
        
        if (this.disableOverwriteChecker) {
            compileTask.options.compilerArgs += '-AdisableOverwriteChecker=true'
        }
        
        if (this.overwriteErrorLevel != null) {
            compileTask.options.compilerArgs += '-AoverwriteErrorLevel=${this.overwriteErrorLevel.toString().trim()}'
        }
        
        if (this.defaultObfuscationEnv != null) {
            compileTask.options.compilerArgs += "-AdefaultObfuscationEnv=${this.defaultObfuscationEnv}"
        }
        
        if (this.mappingTypes.size() > 0) {
            compileTask.options.compilerArgs += listToArg("mappingTypes", this.mappingTypes, ",")
        }

        if (this.tokens.size() > 0) {
            compileTask.options.compilerArgs += mapToArg("tokens", this.tokens)
        }
        
        if (this.extraMappings.size() > 0) {
            compileTask.options.compilerArgs += listToArg("reobfTsrgFiles", this.extraMappings.collect { file -> project.file(file).toString() })
        }

        File importsFile = this.generateImportsFile(compileTask)
        if (importsFile != null) {
            compileTask.options.compilerArgs += "-AdependencyTargetsFile=${importsFile.canonicalPath}"
        }
    }
    
    void importConfig(Object config) {
        if (config == null) {
            throw new InvalidUserDataException("Cannot import from null config")
        }
        this.importConfigs += config
    }
    
    void importLibrary(Object lib) {
        if (lib == null) {
            throw new InvalidUserDataException("Cannot import null library")
        }
        this.importLibs += lib
    }

    /**
     * Generates an "imports" file given the currently specified imports. If the
     * import set is empty then null is returned, otherwise generates and
     * returns a {@link File} which contains the generated import mappings.
     * 
     * @param compileTask Compile task for context
     * @return generated imports file or null if no imports in scope
     */
    private File generateImportsFile(JavaCompile compileTask) {
        File importsFile = new File(compileTask.temporaryDir, "mixin.imports.json")
        importsFile.delete()
        
        Set<File> libs = []
        
        for (Object cfg : this.importConfigs) {
            def config = (cfg instanceof Configuration) ? cfg : project.configurations.findByName(cfg.toString())
            if (config != null) {
                for (File file : config.files) {
                    libs += file
                }
            }
        }

        for (Object lib : this.importLibs) {
            libs += project.file(lib)
        }

        if (libs.size() == 0) {
            return null;
        }
        
        importsFile.newOutputStream().withStream { stream ->
            PrintWriter writer = new PrintWriter(stream);
            for (File lib : libs) {
                Imports[lib].appendTo(writer)
            }
            writer.flush()
        }
        
        return importsFile
    }

        
    @PackageScope static String mapToArg(String argName, Map<?, ?> map, String separator = ";") {
        map.size() < 1 ? "" : "-A${argName}=" + map.collect { token -> token.key << "=" << token.value }.join(separator)
    }
    
    @PackageScope static String listToArg(String argName, List<Object> list, String separator = ";") {
        list.size() < 1 ? "" : "-A${argName}=${list.join(separator)}"
    }
}
