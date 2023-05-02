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

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.tasks.DefaultSourceSet
import org.gradle.api.tasks.SourceSet
import org.gradle.util.VersionNumber

import javax.inject.Inject

/**
 * This is effectively just the entry point for the MixinGradle plugin, most of
 * the heavy lifting with respect to the AP is handled by {@link MixinExtension}
 * and here we merely take the opportunity to validate the environment (eg.
 * check that ForgeGradle is active and the things we need are accessible) and
 * add the mixin extension to the project.
 */
public class MixinGradlePlugin implements Plugin<Project> {
    
    /**
     * Plugin version, sent to the AP so that the AP can notify if the plugin
     * version is too old to support its feature set
     */
    static final String VERSION = getCurrentVersion() ?: '0.7'

    /* (non-Groovydoc)
     * @see org.gradle.api.Plugin#apply(java.lang.Object)
     */
    @Override
    void apply(Project project) {
        // This will throw an exception if any criteria are not met
        this.checkEnvironment(project)
        
        // create the mixin extension
        project.extensions.create('mixin', MixinExtension.class, project)
    }
    
    /**
     * Perform some basic validation on the project environment, mainly checking
     * that the tasks and extensions we need (provided by ForgeGradle) are
     * available.
     * 
     * @param project Project to validate
     */
    private void checkEnvironment(Project project) {
        if (project.tasks.findByName('genSrgs')) {
            throw new InvalidUserDataException("Found a 'genSrgs' task on $project, this version of MixinGradle does not support ForgeGradle 2.x.")
        }

        if (!project.extensions.findByName('minecraft') && !project.extensions.findByName('patcher')) {
            throw new InvalidUserDataException("Could not find property 'minecraft', or 'patcher' on $project, ensure ForgeGradle is applied.")
        }
    }
    
    private static String getCurrentVersion() {
        try {
            def versionMatch = Class.forName('org.spongepowered.asm.gradle.plugins.MixinGradlePlugin').package.implementationVersion =~ /^(\d+\.\d+(\.\d+))/
            return versionMatch ? VersionNumber.parse(versionMatch[0][1]) : null
        } catch (Throwable th) {
            // probably development
        }
    }
    
}
