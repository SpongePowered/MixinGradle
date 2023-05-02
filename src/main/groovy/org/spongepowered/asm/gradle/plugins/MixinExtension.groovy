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
import groovy.transform.PackageScope
import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.util.VersionNumber
import org.spongepowered.asm.gradle.plugins.meta.Imports
import org.spongepowered.asm.gradle.plugins.struct.DynamicProperties
import java.util.Map.Entry

/**
 * Extension object for mixin configuration, actually manages the configuration
 * of the mixin annotation processor and extensions to sourcesets 
 */
public class MixinExtension {
    
    /**
     * Task to contribute generated mappings from the AP to the downstream reobf
     * tasks
     */
    static abstract class ConfigureReobfTask extends DefaultTask {
        
        /**
         * Task to contribute mappings to, marked as <tt>&#64;Internal</tt>
         * since we don't care about up-to-date checking as this task is merely
         * deferred configuration
         */
        @Internal
        Task reobfTask
        
        /**
         * Mapping files to contribute, marked as <tt>&#64;Internal</tt> for the
         * same reason as <tt>reobfTask</tt>.
         */
        @Internal
        Set<File> mappingFiles = []
        
        ConfigureReobfTask() {
            // No up-to-date checking, we want to run this always
            outputs.upToDateWhen { false }
        }
        
        @TaskAction
        def run() {
            for (File mappingFile : this.mappingFiles) {
                if (mappingFile.exists()) {
                    this.project.logger.info "Contributing tsrg mappings ({}) to {} in {}", mappingFile, reobfTask.name, reobfTask.project
                    this.addMappingFile(mappingFile)
                } else {
                    this.project.logger.debug "Tsrg file ({}) not found, skipping", mappingFile
                }
            }
        }

        /**
         * Stub
         */
        protected abstract void addMappingFile(File mappingFile);
    
    }
    
    /**
     * Reobf configuration task for userdev projects
     */
    static class ConfigureReobfTaskForUserDev extends ConfigureReobfTask {
        
        @Override
        protected void addMappingFile(File mappingFile) {
            def extraMappings = this.reobfTask.properties.extraMappings
            
            // FG5+
            if (extraMappings instanceof ConfigurableFileCollection) {
                extraMappings.from(mappingFile)
                return
            }
            
            // Versions of FG prior to 5
            this.reobfTask.extraMapping(mappingFile)
        }
        
    }

    /**
     * Reobf configuration task for patcher projects
     */
    static class ConfigureReobfTaskForPatcher extends ConfigureReobfTask {

        @Override
        protected void addMappingFile(File mappingFile) {
            def add = [
                '--srg-in', mappingFile.absolutePath
            ]
            if (this.reobfTask.args instanceof ListProperty) {
                this.reobfTask.args.addAll(add)
            } else {
                this.reobfTask.args += add
            }
        }

    }
    
    /**
     * A simple decorated {@link File} struct which keeps the original refmap
     * name internally, so that the relative paths can be preserved when adding
     * the refmap to the jar
     */
    static class ArtefactSpecificRefmap extends File {
        
        /**
         * Preserve original refmap path as-specified so that we can use the
         * correct relative path inside the jar  
         */
        File refMap
        
        ArtefactSpecificRefmap(File parent, String refMap) {
            super(parent, refMap)
            this.refMap = new File(refMap)
        }

    }

    /**
     * Task which contributes generated refmap from configured sourcesets, and
     * configs declared by the user, to the (pre-obf) jar
     */
    static class AddMixinsToJarTask extends DefaultTask {
        
        @Internal
        MixinExtension extension

        @Input
        Jar remappedJar

        @Input
        Set<Task> reobfTasks

        @InputFiles
        Set<File> jarRefMaps = []
        
        @TaskAction
        def run() {
            // Add the refmap to all reobf'd jars
            this.reobfTasks.each { reobfTask ->
                def jarTasks = reobfTask.dependsOn.findAll { it == remappedJar }.toSet()
                // ForgeGradle 5 uses a mapped task output provider instead of explicitly depending on the jar task.
                // So, we need to find that task here.
                try {
                    if (reobfTask.input instanceof org.gradle.api.internal.provider.ValueSupplier) {
                        reobfTask.input.producer.visitProducerTasks {
                            if (it == remappedJar) {
                                jarTasks.add it
                            }
                        }
                    }
                } catch (Throwable e) {
                    // Swallow any issues, since ValueSupplier doesn't exist on Gradle 5 and lower
                }
                jarTasks.each { jar ->
                    jarRefMaps.each { artefactSpecificRefMap ->
                        project.logger.info "Contributing refmap ({}) to {} in {}", artefactSpecificRefMap.refMap, jar.hasProperty('archiveFileName') ? jar.archiveFileName.get() : jar.archiveName, reobfTask.project
                        jar.getRefMaps().from(artefactSpecificRefMap)
                        jar.from(artefactSpecificRefMap) {
                            into artefactSpecificRefMap.refMap.parent
                        }
                    }
                    if (this.extension.configNames && !jar.manifest.attributes.containsKey("MixinConfigs")) {
                        def configNamesCsv = this.extension.configNames.join(',')
                        project.logger.info "Contributing configs ({}) to manifest of {} in {}", configNamesCsv, jar.hasProperty('archiveFileName') ? jar.archiveFileName.get() : jar.archiveName, reobfTask.project
                        jar.manifest.attributes['MixinConfigs'] = configNamesCsv
                    }
                }
            }
        }
    }

    /**
     * Cached reference to containing project 
     */
    @PackageScope final Project project

    /**
     * ForgeGradle has two major types of projects, with slightly different setups 
     * 'patcher' is used for developing Forge itself, and any other project that edits Minecraft's source directly.
     * 'userdev' is used by modders who are using Forge or other project based on the patcher plugin.
     */
    @PackageScope final String projectType
    
    /**
     * Detected gradle major version, used in compatibility checks 
     */
    @PackageScope final int majorGradleVersion
    
    /**
     * Version number to include in error message when complaining about missing
     * annotation processor
     */
    @PackageScope VersionNumber mixinVersionForErrors = null

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
     * System properties to contribute to all runs
     */
    @PackageScope DynamicProperties systemProperties = new DynamicProperties("mixin");
    
    /**
     * Reobf tasks we will target
     */
    @PackageScope Set<Task> reobfTasks = []
    
    /**
     * Handles for tasks which add the mixin resources (refmaps, config manifest
     * entries) to each jar, one per jar
     */
    @PackageScope Set<AddMixinsToJarTask> addMixinsToJarTasks = []
    
    /**
     * Set of all specified mixin configs, added to run configurations and jar
     * manifests
     */
    @PackageScope Set<String> configNames = []
    
    /**
     * Collection of message properties
     */
    HashMap<String, String> messages = [:]

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
     * Disables the part of this plugin that tries to add IDE 
     * integration for the AP to eclipse. Useful if something goes wrong, or
     * you want to manage that part yourself.
     */
    boolean disableEclipseAddon
    
    /**
     * Disables the overwrite checking functionality which raises warnings when
     * overwrite methods are not appropriately decorated 
     */
    boolean disableOverwriteChecker
    
    /**
     * Disables the check for the annotation processor dependency on gradle 5
     * and above. Potential reasons for doing so being that the AP is present
     * but in a different dep, or the detection has just failed for some reason
     * and the user wants to override   
     */
    boolean disableAnnotationProcessorCheck
    
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
     * Instruction for the annotation processor to suppress informational
     * messages such as the AP version, etc. 
     */
    boolean quiet
    
    /**
     * Instruction for the annotation processor to decorate all messages with
     * the message type. This can be used to determine the type for messages you
     * wish to specify custom levels for. 
     */
    boolean showMessageTypes
    
    /**
     * Additional TSRG mapping files to supply to the annotation processor. LTP.
     */
    @PackageScope List<Object> extraMappings = []
    
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
        this.majorGradleVersion = MixinExtension.detectGradleMajorVersion(project)
        
        if (project.extensions.findByName('minecraft')) {
            this.projectType = 'userdev'
        } else if (project.extensions.findByName('patcher')) {
            this.projectType = 'patcher'
        } else {
            throw new InvalidUserDataException("Could not find property 'minecraft', or 'patcher' on $project, ensure ForgeGradle is applied.")
        }
        
        if (!this.disableEclipseAddon) {
            MixinEclipse.configureEclipse(this, this.project, this.projectType)
        }
        
        this.init(this.project, this.projectType)
    }
    
    /**
     * Set up the project by extending relevant objects and adding the
     * <tt>afterEvaluate</tt> handler
     */
    private void init(Project project, String projectType) {
        this.project.afterEvaluate {
            // Gather reobf jars for processing
            if (projectType == 'userdev') {
                // ForgeGradle Modder facing plugin, can have multiple reobf tasks.
                project.reobf.each { reobfTaskHandle ->
                    this.reobfTasks += reobfTaskHandle
                }
            } else if (projectType == 'patcher') {
                // ForgeGradle Patcher plugin, only has one default reobf task.
                this.reobfTasks += project.reobfJar
            }

            // Search for sourceSets with a refmap property and configure them
            project.sourceSets.each { set ->
                if (set.ext.has("refMap")) {
                    this.configure(set, projectType)
                }
            }

            // Search for upstream projects and add our jars to their target set
            def configuration = majorGradleVersion >= 7 ? project.configurations.implementation : project.configurations.compile
            configuration.allDependencies.withType(ProjectDependency) { upstream ->
                def mixinExt = upstream.dependencyProject.extensions.findByName("mixin")
                if (mixinExt) {
                    project.reobf.each { reobfTaskWrapper ->
                        mixinExt.reobfTasks += reobfTaskWrapper
                    }
                }
            }
            
            this.applyDefault()
            this.configureRuns()
        }

        SourceSet.metaClass.getRefMap = {
            delegate.ext.refMap
        }
        
        SourceSet.metaClass.setRefMap = { value ->
            delegate.ext.refMap = value
        }
        
        AbstractArchiveTask.metaClass.getRefMaps = {
            if (!delegate.ext.has('refMaps')) {
                delegate.ext.refMaps = majorGradleVersion >= 7 ? project.objects.fileCollection() : project.layout.configurableFiles()
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
     * Directive version of {@link #disableEclipseAddon}
     */
    void disableEclipseAddon() {
        this.disableEclipseAddon = true
    }
    
    /**
     * Directive version of {@link #disableOverwriteChecker}
     */
    void disableOverwriteChecker() {
        this.disableOverwriteChecker = true
    }
    
    /**
     * Directive version of {@link #disableAnnotationProcessorCheck}
     */
    void disableAnnotationProcessorCheck() {
        this.disableAnnotationProcessorCheck = true
    }
    
    /**
     * Directive version of {@link #quiet}
     */
    void quiet() {
        quiet = true
    }
    
    /**
     * Directive version of {@link #showMessageTypes}
     */
    void showMessageTypes() {
        showMessageTypes = true
    }
    
    /**
     * Directive version of {@link #overwriteErrorLevel}
     */
    void overwriteErrorLevel(Object errorLevel) {
        this.overwriteErrorLevel = errorLevel
    }
    
    /**
     * Getter for reobfSrgFile, fetch from the <tt>genSrgs</tt> task if not configured
     */
    Object getMappings() {
        if (this.reobfSrgFile != null) {
            return project.file(this.reobfSrgFile)
        } else if (this.projectType == 'userdev') {
            return project.tasks.createMcpToSrg.outputs.files[0]
        } else if (this.projectType == 'patcher') {
            return project.tasks.createMcp2Srg.outputs.files[0]
        }
        return null
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
                this.configure(set, this.projectType)
            }
        }
    }
    
    /**
     * Configure all run configurations with mixin config args and any mixin
     * debug args specified in the mixin closure 
     */
    @PackageScope void configureRuns() {
        if (this.projectType == 'userdev') {
            project.extensions.minecraft.runs.each { runConfig ->
                if (project.tasks.findByName('createSrgToMcp')) {
                    def srgToMcpFile = project.tasks.createSrgToMcp.outputs.files[0].path
                    
                    // Supply the legacy GradleStart property which identifies the remapping file
                    runConfig.property 'net.minecraftforge.gradle.GradleStart.srg.srg-mcp', srgToMcpFile
                    
                    // If the user has already specified remapRefMap then we assume they either
                    // supplied a mapping file path manually, or mixin will fall back on the
                    // GradleStart property above. If the user has specified these options in
                    // the mixin closure then the values will be overwritten below anyway.
                    if (!runConfig.properties.containsKey('mixin.env.remapRefMap')) {
                        runConfig.property 'mixin.env.remapRefMap', 'true'
                        runConfig.property 'mixin.env.refMapRemappingFile', srgToMcpFile
                    }
                }
                
                this.configNames.each { configName ->
                    runConfig.args '--mixin.config', configName
                } 
                
                this.systemProperties.args.each {
                    runConfig.property it.key, it.value.toString()
                }
            }
        }
    }
    
    /**
     * Searches the compile configuration of each SourceSet that has been
     * registered via add() looking for the mixin dependency. If the mixin
     * dependency is found and the current gradle version is 5 or higher then
     * check that the Annotation Processor artefact has been added to the
     * corresponding annotationProcessor configuration for the SourceSet and
     * raise an error if the AP was not found.
     * 
     * <p>This is necessary because in previous versions of gradle, APs in
     * compile dependencies were automatically added, however since gradle 5
     * they must be explicitly specified but knowing this assumes that all end
     * users read the gradle upgrade notes when changing versions, which is not
     * realistically the case. So thanks gradle.</p> 
     */
    @PackageScope void checkForAnnotationProcessors() {
        if (this.disableAnnotationProcessorCheck || (this.majorGradleVersion < 5 && this.majorGradleVersion > 0)) {
            return
        }
            
        def missingAPs = this.findMissingAnnotationProcessors()
        if (missingAPs) {
            def gradleVersion = this.majorGradleVersion > 4 ? "Gradle ${this.majorGradleVersion} " : "An unrecognised gradle version "
            def missingAPNames = missingAPs.collect { it.annotationProcessorConfigurationName }
            def addAPName = missingAPNames.size() > 1 ? '<configurationName>' : missingAPNames[0]
            def eachOfThese = missingAPNames.size() > 1 ? " where <configurationName> is each of $missingAPNames." : ''
            def mixinVersion = this.mixinVersionForErrors ?: '0.1.2-SNAPSHOT' 
            def message = "$gradleVersion was detected but the mixin dependency was missing from one or more Annotation Processor " +
                "configurations: $missingAPNames. To enable the Mixin AP please include the mixin processor artefact in each Annotation " +
                "Processor configuration. For example if you are using mixin dependency 'org.spongepowered:mixin:$mixinVersion' you " + 
                "should specify: dependencies { $addAPName 'org.spongepowered:mixin:$mixinVersion:processor' }$eachOfThese. If you " +
                "believe you are seeing this message in error, you can disable this check via by adding disableAnnotationProcessorCheck() " +
                "to your mixin { } block."
                
            // Only promote the error message to an actual error if we're sure there's a gradle version mismatch
            if (this.majorGradleVersion >= 5) {
                throw new MixinGradleException(message)
            } else {
                this.project.logger.error message
            }
        }
    }
    
    /**
     * Searches for the annotation processor dependency in all sourceset ap
     * configurations which have a refmap. Returns true if the AP is found and
     * false if it is not found in any added configuration. 
     */
    @PackageScope Set<SourceSet> findMissingAnnotationProcessors() {
        Set<SourceSet> missingAPs = []
        missingAPs += this.sourceSets.findResults { SourceSet sourceSet ->
            sourceSet.ext.mixinDependency = majorGradleVersion >= 7
                    ? this.findMixinDependency(sourceSet.implementationConfigurationName)
                    : (this.findMixinDependency(sourceSet.compileConfigurationName) ?: this.findMixinDependency(sourceSet.implementationConfigurationName))
            if (sourceSet.ext.mixinDependency) {
                VersionNumber mainVersion = this.getDependencyVersion(sourceSet.ext.mixinDependency)
                if (mainVersion > this.mixinVersionForErrors) {
                    this.mixinVersionForErrors = mainVersion
                } 
                sourceSet.ext.apDependency = this.findMixinDependency(sourceSet.annotationProcessorConfigurationName)
                if (sourceSet.ext.apDependency) {
                    VersionNumber apVersion = this.getDependencyVersion(sourceSet.ext.apDependency)
                    if (mainVersion > apVersion) {
                        this.project.logger.warn "Mixin AP version ($apVersion) in configuration '${sourceSet.annotationProcessorConfigurationName}' is older than compile version ($mainVersion)"
                    }
                } else {
                    return sourceSet
                }
            }
        }
        return missingAPs
    }
    
    /**
     * Checks whether a configuration contains any mixin dependency in its
     * explicit or resolved dependency artefacts
     * 
     * @param configurationName Configuration name to check
     * @return true if the configuration contains a mixin dependency
     */
    @PackageScope def findMixinDependency(String configurationName) {
        def configuration = project.configurations[configurationName]
        return configuration.canBeResolved
            ? configuration.resolvedConfiguration.resolvedArtifacts.find { it.id =~ /:mixin:/ }
            : configuration.allDependencies.find { it.group =~ /spongepowered/ && it.name =~ /mixin/ }
    }
    
    /**
     * Detected dependencies might be a resolved artefact or a declared
     * dependency (depending on where we are in the build lifecycle). This
     * method abstracts parsing a dependency version from either supported
     * object type.
     */
    @PackageScope VersionNumber getDependencyVersion(def dependency) {
        if (dependency instanceof ResolvedArtifact) {
            return VersionNumber.parse(dependency.moduleVersion.id.version)
        } else if (dependency instanceof Dependency) {
            return VersionNumber.parse(dependency.version)
        }
    }
    
    /**
     * Register a mixin config which will be added to all reobf'd jar manifests
     * and all run configurations
     */
    void config(String path) {
        this.configNames += path
    }
    
    /**
     * Accessor for system properties starting with mixin.debug
     */
    def getDebug() {
        return this.systemProperties.debug
    }
    
    /**
     * Accessor for system property mixin.debug
     */
    def setDebug(def value) {
        this.systemProperties.debug = value
    }
    
    /**
     * Accessor for system properties starting with mixin.checks
     */
    def getChecks() {
        return this.systemProperties.checks
    }

    /**
     * Accessor for system property mixin.checks
     */
    def setChecks(def value) {
        this.systemProperties.checks = value
    }
    
    /**
     * Accessor for system property mixin.dumpTargetOnFailure
     */
    def getDumpTargetOnFailure() {
        return this.systemProperties.dumpTargetOnFailure
    }
    
    /**
     * Accessor for system property mixin.dumpTargetOnFailure
     */
    def setDumpTargetOnFailure(def value) {
        this.systemProperties.dumpTargetOnFailure = value
    }
    
    /**
     * Accessor for system property mixin.ignoreConstraints
     */
    def getIgnoreConstraints() {
        return this.systemProperties.ignoreConstraints
    }
    
    /**
     * Accessor for system property mixin.ignoreConstraints
     */
    def setIgnoreConstraints(def value) {
        this.systemProperties.ignoreConstraints = value
    }
    
    /**
     * Accessor for system property mixin.hotSwap
     */
    def getHotSwap() {
        return this.systemProperties.hotSwap
    }
    
    /**
     * Accessor for system property mixin.hotSwap
     */
    def setHotSwap(def value) {
        this.systemProperties.hotSwap = value
    }
    
    /**
     * Accessor for system properties starting with mixin.env
     */
    def getEnv() {
        return this.systemProperties.env
    }
    
    /**
     * Accessor for system property mixin.env
     */
    def setEnv(def value) {
        this.systemProperties.env = value
    }
    
    /**
     * Accessor for system property mixin.initialiserInjectionMode
     */
    def getInitialiserInjectionMode() {
        return this.systemProperties.initialiserInjectionMode
    }
    
    /**
     * Accessor for system property mixin.initialiserInjectionMode
     */
    def setInitialiserInjectionMode(def mode) {
        this.systemProperties.initialiserInjectionMode = mode
    }
    
    def messages(Closure closure) {
        closure.delegate = this.messages
        closure()
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
            throw new InvalidUserDataException(sprintf('No \'refMap\' or \'ext.refMap\' defined on %s. Call \'add(sourceSet, refMapName)\' instead.', set))
        }
        this.manuallyAdd(set)
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
            throw new InvalidUserDataException(sprintf('No sourceSet \'%s\' was found', set))
        }
        sourceSet.ext.refMap = refMapName
        this.manuallyAdd(sourceSet)
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
        this.manuallyAdd(set)
    }
    
    /**
     * We need to disable the flag early, because our main after evaluate happens before the one we are adding now.
     */
    void manuallyAdd(SourceSet set) {
        // Don't perform default behaviour, a sourceSet has been added manually
        this.applyDefault = false
        String pType = this.projectType
        project.afterEvaluate {
            this.configure(set, pType)
        }
    }
    
    /**
     * Configure a sourceSet for mixin processing and specify the refMap name,
     * the SourceSet must exist
     * 
     * @param set SourceSet to add
     */
    void configure(SourceSet set, String projectType) {
        // Check whether this sourceSet was already added
        if (!this.sourceSets.add(set)) {
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
        if (this.projectType == 'userdev') {
            compileTask.dependsOn("createMcpToSrg")
        } else if (this.projectType == 'patcher') {
            compileTask.dependsOn("createMcp2Srg")
        }
        
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
        File taskSpecificRefMap = new ArtefactSpecificRefmap(refMapFile.parentFile, compileTask.ext.refMap)

        // Closure to rename generated refMap to artefact-specific refmap when
        // compile task is completed
        compileTask.doLast {
            // Delete the old one
            taskSpecificRefMap.delete()

            // Copy the new one if it was successfully generated
            if (refMapFile.exists()) {
                taskSpecificRefMap.parentFile.mkdirs()
                Files.copy(refMapFile, taskSpecificRefMap) 
            }
        }
        
        // Create a task to contribute the refmap to the jar. Since we don't
        // know at this point which jars are going to be reobfuscated (the
        // inputs to reobf tasks are lazily evaluated at the dependsOn isn't
        // added until later) we add one such task for every jar and the task
        // can handle the heavy lifting of figuring out what to contribute
        project.tasks.withType(Jar.class) { jarTask ->
            this.addMixinsToJarTasks.add(project.tasks.maybeCreate("addMixinsTo${jarTask.name.capitalize()}", AddMixinsToJarTask.class).configure {
                doFirst {
                    this.checkForAnnotationProcessors()
                }
                extension = this
                dependsOn(compileTask)
                remappedJar = jarTask
                reobfTasks = this.reobfTasks
                jarRefMaps += taskSpecificRefMap
                jarTask.dependsOn(delegate)
            })
            if (projectType == 'patcher') { //Patcher's universal jar is built from a filtered jar, so our normal detection doesn't find it.
                if ('universalJar' == jarTask.name) {
                    project.logger.info "Contributing refmap ({}) to {} in {}", taskSpecificRefMap, jar.hasProperty('archiveFileName') ? jar.archiveFileName.get() : jar.archiveName, project
                    jarTask.getRefMaps().from(taskSpecificRefMap)
                    jarTask.from(taskSpecificRefMap)
                }
            }
        }

        // Closure to allocate generated AP resources once compile task is completed
        this.reobfTasks.each { reobfTask ->
            // We can't use doFirst on the handle task here in ForgeGradle 5, so we instead use a configure task.
            // This is because ForgeGradle 5 uses a ConfigurableFileCollection, and Gradle finalizes properties on task start.
            // Using a task to configure the reobf task on-demand works fine.
            def configureReobfTaskType = this.projectType == 'patcher' ? ConfigureReobfTaskForPatcher.class : this.projectType == 'userdev' ? ConfigureReobfTaskForUserDev.class : null
            if (configureReobfTaskType != null) {
                def configureReobfTaskTask = project.tasks.maybeCreate("configureReobfTaskFor${reobfTask.name.capitalize()}", configureReobfTaskType).configure {
                    delegate.reobfTask = reobfTask
                    mappingFiles += tsrgFile
                }
                reobfTask.dependsOn configureReobfTaskTask
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
            compileTask.options.compilerArgs += listToArg("reobfTsrgFiles", this.extraMappings.collect { file -> this.project.file(file).toString() })
        }

        File importsFile = this.generateImportsFile(compileTask)
        if (importsFile != null) {
            compileTask.options.compilerArgs += "-AdependencyTargetsFile=${importsFile.canonicalPath}"
        }
        
        if (this.quiet) {
            compileTask.options.compilerArgs += '-Aquiet=true'
        }
        
        if (this.showMessageTypes) {
            compileTask.options.compilerArgs += '-AshowMessageTypes=true'
        }
        
        this.messages.each { property, level ->
            if (property =~ /^[A-Z]+[A-Z_]+$/ && level =~ /^(note|warning|error|disabled)$/) {
                compileTask.options.compilerArgs += "-AMSG_$property=$level"
            }
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
            return null
        }
        
        importsFile.newOutputStream().withStream { stream ->
            PrintWriter writer = new PrintWriter(stream)
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
    
    private static int detectGradleMajorVersion(Project project) {
        def strMajorVersion = (project.gradle.gradleVersion =~ /^([0-9]+)\./).findAll()[0][1]
        return strMajorVersion.isInteger() ? strMajorVersion as Integer : 0
    } 
}
