/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import io.github.spencerpark.jupyter.kernel.ReplacementOptions;
import io.github.spencerpark.jupyter.kernel.util.StringStyler;

import java.util.List;
import java.util.function.Function;

public interface Evaluator {

    String isComplete(String language, String code);

    ReplacementOptions complete(String language, String code, int at);

    Object eval(String language, String code) throws Exception;

    List<String> formatError(Exception e, StringStyler errorStyler, Function<Exception, List<String>> defaultFormat);

    void interrupt();

    void shutdown();

}
