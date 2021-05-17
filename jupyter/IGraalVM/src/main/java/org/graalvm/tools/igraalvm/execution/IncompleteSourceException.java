/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

public class IncompleteSourceException extends Exception {
    private final String source;

    public IncompleteSourceException(String source) {
        super(source);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
