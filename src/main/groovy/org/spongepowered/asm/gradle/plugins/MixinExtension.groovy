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

import com.google.common.io.Files
import groovy.lang.MissingPropertyException
import groovy.transform.PackageScope
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
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
    
    class ReobfTask {
        final Project project
        final Object taskWrapper
        
        ReobfTask(Project project, Object taskWrapper) {
            this.project = project
            this.taskWrapper = taskWrapper
        }
        
        Jar getJar() {
            this.project.tasks[this.taskWrapper.name]
        }
        
        String getName() {
            this.taskWrapper.name
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
    Set<ReobfTask> reobfTasks = []
    
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
     * Additional Searge SRG files to supply to the annotation processor. LTP.
     */
    private List<Object> extraSrgFiles = []
    
    /**
     * Additional Notch SRG files to supply to the annotation processor. LTP.
     */
    private List<Object> extraNotchFiles = []
    
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
        Project project = this.project
        def sourceSets = this.sourceSets
        
        this.project.afterEvaluate {
            // Search for sourceSets with a refmap property and configure them
            project.sourceSets.each { set ->
                if (set.ext.has("refMap")) {
                    this.configure(set)
                }
            }

            // Search for upstream projects and add our jars to their target set
            project.configurations.compile.allDependencies.withType(ProjectDependency) { upstream ->
                def mixinExt = upstream.dependencyProject.extensions.findByName("mixin")
                if (mixinExt) {
                    project.reobf.each { reobfTaskWrapper ->
                        mixinExt.reobfTasks += new ReobfTask(project, reobfTaskWrapper)
                    }
                }
            }
            
            // Gather reobf jars for processing
            project.reobf.each { reobfTaskWrapper ->
                this.reobfTasks += new ReobfTask(project, reobfTaskWrapper)
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
                delegate.ext.refMaps = new SimpleFileCollection()
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
     * Adds an additional SRG file for the AP to consume. The environment for
     * the SRG file must be specified
     * 
     * @param type Obfuscation type to add mapping to
     * @param file Object which resolves to a file
     */
    void extraSrgFile(String type, Object file) {
        type = type.toUpperCase()
        if (SEARGE.matches(type)) {
            this.extraSrgFiles += file
        } else if (NOTCH.matches(type)) {
            this.extraNotchFiles += file
        } else {
            throw new InvalidUserDataException("Invalid obfuscation type '${type}' specified for extraSrgFile")
        }
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
    
    @PackageScope String getSrgsArgument(String argName, List<Object> list) {
        if (list.size() < 1) {
            return ""
        }
        def arg = "-A${argName}="
        def first = true
        for (String entry : list) {
            if (!first) arg <<= ";"
            first = false
            arg <<= project.file(entry).toString()
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
        
        this.configure(set)
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
        this.configure(set)
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
        
        // Srg files
        def srgFiles = [
            (ReobfMappingType.SEARGE): project.file("${compileTask.temporaryDir}/mcp-srg.srg"),
            (ReobfMappingType.NOTCH): project.file("${compileTask.temporaryDir}/mcp-notch.srg")
        ]
        
        // Add our vars as extension properties to the sourceSet and compile
        // tasks, this will allow them to be used in the build script if needed
        compileTask.ext.outSrgFile = srgFiles[SEARGE]
        compileTask.ext.outNotchFile = srgFiles[NOTCH]
        compileTask.ext.refMapFile = refMapFile
        set.ext.refMapFile = refMapFile
        compileTask.ext.refMap = set.ext.refMap.toString()
        
        // Closure to prepare AP environment before compile task runs
        compileTask.doFirst {
            if (!this.disableRefMapWarning && refMaps[compileTask.ext.refMap]) {
                project.logger.warn "Potential refmap conflict. Duplicate refmap name {} specified for sourceSet {}, already defined for sourceSet {}",
                    compileTask.ext.refMap, set.name, refMaps[compileTask.ext.refMap]
            } else {
                refMaps[compileTask.ext.refMap] = set.name
            }
            
            refMapFile.delete()
            srgFiles.each {
                it.value.delete()
            }
            this.applyCompilerArgs(compileTask)
        }

        // Refmap is generated with a generic name, rename to
        // artefact-specific name ready for inclusion into target jar. We
        // can't use rename in the jar spec because there may be multiple
        // refmaps with the same source name
        File artefactSpecificRefMap = new File(refMapFile.parentFile, compileTask.ext.refMap)

        // Closure to allocate generated AP resources once compile task
        // is completed
        compileTask.doLast {
            if (outSrgFile.exists() || outNotchFile.exists()) {
                try {
                    this.reobfTasks.each { reobfTask ->
                        def mapped = false
                        [reobfTask.taskWrapper.mappingType, this.defaultObfuscationEnv.toString()].each { arg ->
                            ReobfMappingType.each { type ->
                                if (type.matches(arg) && !mapped) {
                                    this.addMappings(reobfTask, type, srgFiles[type])
                                    mapped = true
                                }
                            }
                        }
        
                        // No mapping set was matched, so add the searge mappings
                        if (!mapped) {
                            this.addMappings(reobfTask, SEARGE, srgFiles[SEARGE])
                        }
                    }
                } catch (MissingPropertyException ex) {
                    if (ex.property == "mappingType") {
                        throw new InvalidUserDataException("Could not determine mapping type for obf task, ensure ForgeGradle up to date.")
                    } else {
                        throw ex
                    }
                }
            }

            // Delete the old one
            artefactSpecificRefMap.delete()

            // Copy the new one if it was successfully generated
            if (compileTask.ext.refMapFile.exists()) {
                Files.copy(refMapFile, artefactSpecificRefMap) 
            }

        }

        // Add the refmap to all reobf'd jars
        this.reobfTasks.each { reobfTask ->
            reobfTask.jar.getRefMaps().files.add(artefactSpecificRefMap)
            reobfTask.jar.from(artefactSpecificRefMap)
        }

    }
    
    /**
     * Callback from <tt>compileTask.doLast</tt> closure, attempts to contribute
     * mappings of the specified type to the supplied task
     * 
     * @param reobfTask a <tt>ReobfTask</tt> instance
     * @param type Mapping type to add
     * @param srgFile SRG mapping file to add to the task
     */
    @PackageScope void addMappings(ReobfTask reobfTask, ReobfMappingType type, File srgFile) {
        if (!srgFile.exists()) {
            project.logger.warn "Unable to contribute {} mappings to {}, the specified file ({}) was not found", type, reobfTask.name, srgFile
            return    
        }
        
        project.logger.info "Contributing {} ({}) mappings to {} in {}", type, srgFile, reobfTask.name, reobfTask.project
        reobfTask.taskWrapper.extraFiles(srgFile)
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
        
        if (this.disableOverwriteChecker) {
            compileTask.options.compilerArgs += '-AdisableOverwriteChecker=true'
        }
        
        if (this.overwriteErrorLevel != null) {
            compileTask.options.compilerArgs += '-AoverwriteErrorLevel=${this.overwriteErrorLevel.toString().trim()}'
        }
        
        if (this.defaultObfuscationEnv != null) {
            compileTask.options.compilerArgs += "-AdefaultObfuscationEnv=${this.defaultObfuscationEnv.toLowerCase()}"
        }
        
        if (this.tokens.size() > 0) {
            compileTask.options.compilerArgs += this.tokenArgument
        }
        
        if (this.extraSrgFiles.size() > 0) {
            compileTask.options.compilerArgs += this.getSrgsArgument("reobfSrgFiles", this.extraSrgFiles)
        }
        
        if (this.extraNotchFiles.size() > 0) {
            compileTask.options.compilerArgs += this.getSrgsArgument("reobfNotchSrgFiles", this.extraNotchFiles)
        }

        File importsFile = this.generateImportsFile(compileTask)
        if (importsFile != null) {
            compileTask.options.compilerArgs += "-AdependencyTargetsFile=${importsFile.canonicalPath}"
        }    
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
}
