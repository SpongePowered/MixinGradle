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

package buildSrc;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.internal.tasks.GroovydocAntAction;
import org.gradle.api.provider.Property;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.javadoc.Groovydoc;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.workers.WorkQueue;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;

// This is a Groovydoc task but the Groovydoc ant action runs in a forked JVM using getJavaLauncher()
@CacheableTask
public abstract class GroovydocForking extends Groovydoc {
    public abstract @Nested Property<JavaLauncher> getJavaLauncher();

    protected abstract @Inject FileSystemOperations getFileSystemOperations();

    @Override
    protected void generate() {
        this.checkGroovyClasspathNonEmpty(this.getGroovyClasspath().getFiles());
        File destinationDir = this.getDestinationDir();

        try {
            this.getDeleter().ensureEmptyDirectory(destinationDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        FileSystemOperations fsOperations = this.getFileSystemOperations();
        File tmpDir = this.getTemporaryDir();
        fsOperations.delete((spec) -> spec.delete(tmpDir));
        fsOperations.copy((spec) -> spec.from(this.getSource()).into(tmpDir));

        WorkQueue queue = this.getWorkerExecutor().processIsolation(spec -> spec.forkOptions(fork -> {
            fork.setExecutable(getJavaLauncher().get().getExecutablePath().toString());
        }));

        queue.submit(GroovydocAntAction.class, (parameters) -> {
            parameters.getAntLibraryClasspath().from(this.getClasspath());
            parameters.getAntLibraryClasspath().from(this.getGroovyClasspath());
            parameters.getSource().convention(this.getSource());
            parameters.getDestinationDirectory().fileValue(destinationDir);
            parameters.getUse().convention(this.isUse());
            parameters.getNoTimestamp().convention(this.isNoTimestamp());
            parameters.getNoVersionStamp().convention(this.isNoVersionStamp());
            parameters.getWindowTitle().convention(this.getWindowTitle());
            parameters.getDocTitle().convention(this.getDocTitle());
            parameters.getHeader().convention(this.getHeader());
            parameters.getFooter().convention(this.getFooter());
            parameters.getOverview().convention(this.getPathToOverview());
            parameters.getAccess().convention(this.getAccess());
            parameters.getLinks().convention(this.getLinks());
            parameters.getTmpDir().fileValue(this.getTemporaryDir());
            parameters.getIncludeAuthor().convention(this.getIncludeAuthor());
            parameters.getProcessScripts().convention(this.getProcessScripts());
            parameters.getIncludeMainForScripts().convention(this.getIncludeMainForScripts());
        });
    }

    private void checkGroovyClasspathNonEmpty(Collection<File> classpath) {
        if (classpath.isEmpty()) {
            throw new InvalidUserDataException("You must assign a Groovy library to the groovy configuration!");
        }
    }

    private @Nullable String getPathToOverview() {
        TextResource overview = this.getOverviewText();
        return overview != null ? overview.asFile().getAbsolutePath() : null;
    }
}
