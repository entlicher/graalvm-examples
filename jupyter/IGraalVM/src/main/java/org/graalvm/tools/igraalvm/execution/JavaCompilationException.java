/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import jdk.jshell.SnippetEvent;

public final class JavaCompilationException extends Exception {
    private final SnippetEvent badSnippetCompilation;

    public JavaCompilationException(SnippetEvent badSnippetCompilation) {
        this.badSnippetCompilation = badSnippetCompilation;
    }

    public SnippetEvent getBadSnippetCompilation() {
        return badSnippetCompilation;
    }
}
