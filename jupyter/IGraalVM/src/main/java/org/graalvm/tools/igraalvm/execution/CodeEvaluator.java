/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import io.github.spencerpark.jupyter.kernel.ReplacementOptions;
import io.github.spencerpark.jupyter.kernel.util.StringStyler;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.graalvm.tools.igraalvm.GraalVMKernel;

import org.graalvm.nativeimage.ImageInfo;

public final class CodeEvaluator {

    private boolean isInitialized = false;
    private final List<String> startupScripts;

    private final List<String> compilerOpts;
    private final List<String> classpath;
    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final String timeout;

    private String language = null;
    private JavaEvaluator javaEvaluator;
    private GuestEvaluator guestEvaluator;
    private Evaluator currentEvaluator;

    CodeEvaluator(List<String> compilerOpts, List<String> classpath, List<String> startupScripts, InputStream in, PrintStream out, PrintStream err, String timeout) {
        this.compilerOpts = compilerOpts;
        this.classpath = classpath;
        this.startupScripts = startupScripts;
        this.in = in;
        this.out = out;
        this.err = err;
        this.timeout = timeout;
    }

    private synchronized JavaEvaluator getJavaEvaluator() {
        if (javaEvaluator == null) {
            assert !ImageInfo.inImageCode();
            javaEvaluator = new JavaEvaluator(compilerOpts, classpath, startupScripts, in, out, err, timeout);
        }
        return javaEvaluator;
    }

    public synchronized GuestEvaluator getGuestEvaluator() {
        if (guestEvaluator == null) {
            guestEvaluator = new GuestEvaluator(in, out, err);
        }
        return guestEvaluator;
    }

    private void init() throws Exception {
        for (String script : this.startupScripts)
            eval(script);

        this.startupScripts.clear();
    }

    private boolean isJavaEval() {
        return !ImageInfo.inImageCode() && (this.language == null || this.language.equalsIgnoreCase("java"));
    }

    private String stripAndSetLanguage(String code) {
        if (code.startsWith(GraalVMKernel.SWITCH_LANGS_PREFIX)) {
            int langIndex = 2;
            while (langIndex < code.length() && !Character.isWhitespace(code.charAt(langIndex))) {
                langIndex++;
            }
            String langName = code.substring(2, langIndex);
            if (!langName.isEmpty()) {
                String nl = System.getProperty("line.separator");
                while (langIndex < code.length() && Character.isWhitespace(code.charAt(langIndex))) {
                    if (code.substring(langIndex).startsWith(nl)) {
                        langIndex += nl.length();
                        break;
                    } else {
                        langIndex++;
                    }
                }
                code = code.substring(langIndex);
                this.language = langName;
            }
        }
        Evaluator evaluator;
        if (isJavaEval()) {
            evaluator = getJavaEvaluator();
        } else {
            evaluator = getGuestEvaluator();
        }
        currentEvaluator = evaluator;
        return code;
    }

    public String isComplete(String code) {
        code = stripAndSetLanguage(code);
        return currentEvaluator.isComplete(this.language, code);
    }

    public ReplacementOptions complete(String code, int at) {
        code = stripAndSetLanguage(code);
        return currentEvaluator.complete(language, code, at);
    }

    public Object eval(String code) throws Exception {
        // The init() method runs some code in the shell to initialize the environment. As such
        // it is deferred until the first user requested evaluation to cleanly return errors when
        // they happen.
        if (!this.isInitialized) {
            this.isInitialized = true;
            init();
        }

        code = stripAndSetLanguage(code);
        return currentEvaluator.eval(language, code);
    }

    public List<String> formatError(Exception e, StringStyler errorStyler, Function<Exception, List<String>> defaultFormat) {
        if (e instanceof EvaluationTimeoutException) {
            return formatEvaluationTimeoutException((EvaluationTimeoutException) e, errorStyler);
        } else if (e instanceof EvaluationInterruptedException) {
            return formatEvaluationInterruptedException((EvaluationInterruptedException) e, errorStyler);
        }
        Evaluator evaluator = currentEvaluator;
        if (evaluator != null) {
            return evaluator.formatError(e, errorStyler, defaultFormat);
        } else {
            return null;
        }
    }

    private static List<String> formatEvaluationTimeoutException(EvaluationTimeoutException e, StringStyler errorStyler) {
        List<String> fmt = new ArrayList<>(errorStyler.primaryLines(e.getSource()));

        fmt.add(errorStyler.secondary(String.format(
                "Evaluation timed out after %d %s.",
                e.getDuration(),
                e.getUnit().name().toLowerCase())
        ));

        return fmt;
    }

    private static List<String> formatEvaluationInterruptedException(EvaluationInterruptedException e, StringStyler errorStyler) {
        List<String> fmt = new ArrayList<>(errorStyler.primaryLines(e.getSource()));

        fmt.add(errorStyler.secondary("Evaluation interrupted."));

        return fmt;
    }

    public void interrupt() {
        Evaluator evaluator = currentEvaluator;
        if (evaluator != null) {
            evaluator.interrupt();
        }
    }

    public void shutdown() {
        Evaluator evaluator = currentEvaluator;
        if (evaluator != null) {
            evaluator.shutdown();
        }
    }

}
