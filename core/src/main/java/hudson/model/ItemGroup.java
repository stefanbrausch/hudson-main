/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.model;

import java.io.IOException;
import java.util.Collection;
import java.io.File;

/**
 * Represents a grouping inherent to a kind of {@link Item}s.
 *
 * @author Kohsuke Kawaguchi
 * @see ItemGroupMixIn
 */
public interface ItemGroup<T extends Item> extends PersistenceRoot, ModelObject {
    /**
     * Gets the full name of this {@link ItemGroup}.
     *
     * @see Item#getFullName() 
     */
    String getFullName();

    /**
     * @see Item#getFullDisplayName() 
     */
    String getFullDisplayName();

    /**
     * Gets all the items in this collection in a read-only view.
     */
    Collection<T> getItems();

    /**
     * Returns the path relative to the context root,
     * like "foo/bar/zot/". Note no leading slash but trailing slash.
     */
    String getUrl();

    /**
     * Gets the URL token that prefixes the URLs for child {@link Item}s.
     * Like "job", "item", etc.
     */
    String getUrlChildPrefix();

    /**
     * Gets the {@link Item} inside this group that has a given name.
     */
    T getItem(String name);

    /**
     * Assigns the {@link Item#getRootDir() root directory} for children.
     */
    File getRootDirFor(T child);

    /**
     * Internal method. Called by {@link Item}s when they are renamed by users.
     */
    void onRenamed(T item, String oldName, String newName) throws IOException;
}
