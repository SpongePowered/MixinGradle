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

package org.spongepowered.asm.gradle.plugins.struct

/**
 * A collection of String-to-whatever properties which supports a value <em>at
 * the node</em> and also subnodes with values. This is to represent the semi-
 * hierarchical nature of mixin's sytem properties. There's probably a builtin
 * Gradle class which does something similar to this but I couldn't find it so
 * here we are.
 * 
 * <p>The most important aspect of this class is that the value is actually
 * always set by the parent node because when the node is accessed via the
 * getter, the last element of the node chain is actually the <em>member</em>
 * being accessed (via propertyMissing) so since the last element of the chain
 * which is actually a node is the parent of that node, the parent node sets the
 * value. Child nodes are automatically added for every missing property.</p>
 */
public class DynamicProperties {
    
    /**
     * Local node name, including parent's coordinate
     */
    private final String name
    
    /**
     * Child nodes of this node
     */
    private final Map<String, DynamicProperties> properties = [:]
    
    /**
     * Local value of this node, can be null if we only have children 
     */
    private String value
    
    DynamicProperties(String name) {
        this.name = name
    }
    
    /**
     * A getter, if this node is in the middle of a node chain
     */
    def propertyMissing(String name) {
        if (!this.properties[name]) {
            this.properties[name] = new DynamicProperties(this.name + '.' + name)
        }
        this.properties[name]
    }
    
    /**
     * A setter, sets the leaf node value
     */
    def propertyMissing(String name, def value) {
        if (!this.properties[name]) {
            this.properties[name] = new DynamicProperties(this.name + '.' + name)
        }
        this.properties[name].value = value
    }
    
    /**
     * Probably not used but does technically allow leaf node values to be set
     * in a method-call style instead of assignment style
     */
    def methodMissing(String name, def args) {
        if (args.length == 1) {
            this.propertyMissing(name, args[0])
        }
    }
    
    /**
     * Get all args from this properties collection and all of its children as a
     * single map
     */
    def getArgs() {
        def args = [:]
        if (this.value) {
            args[this.name] = value 
        }
        this.properties.each {
            args += it.value.args
        }
        return args
    }
    
}
