/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

public class EvaluationInterruptedException extends Exception {
    private final String source;

    public EvaluationInterruptedException(String source) {
        this.source = source;
    }

    public String getSource() {
        return source;
    }

    @Override
    public String getMessage() {
        return String.format("Evaluator was interrupted while executing: '%s'",
                this.source);
    }
}
