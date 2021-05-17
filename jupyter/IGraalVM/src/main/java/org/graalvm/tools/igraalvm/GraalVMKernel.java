/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm;

import io.github.spencerpark.jupyter.kernel.BaseKernel;
import io.github.spencerpark.jupyter.kernel.LanguageInfo;
import io.github.spencerpark.jupyter.kernel.ReplacementOptions;
import io.github.spencerpark.jupyter.kernel.display.DisplayData;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.tools.igraalvm.execution.CodeEvaluator;
import org.graalvm.tools.igraalvm.execution.CodeEvaluatorBuilder;

/**
 *
 * @author martin
 */
public class GraalVMKernel extends BaseKernel {

    public static final String SWITCH_LANGS_PREFIX = "#!";

    private final LanguageInfo languageInfo;
    private final CodeEvaluator evaluator;

    GraalVMKernel() {
        this.languageInfo = new LanguageInfo.Builder("GraalVM")
                .version(Runtime.version().toString())
                //.mimetype("text/x-java-source")
                //.fileExtension(".jshell")
                .pygments("java")
                .codemirror("java")
                .build();
        this.evaluator = new CodeEvaluatorBuilder()
                .sysStdout()
                .sysStderr()
                .sysStdin()
                .build();
    }

    public static String completeCodeSignifier() {
        return BaseKernel.IS_COMPLETE_YES;
    }

    public static String invalidCodeSignifier() {
        return BaseKernel.IS_COMPLETE_BAD;
    }

    public static String maybeCompleteCodeSignifier() {
        return BaseKernel.IS_COMPLETE_MAYBE;
    }

    @Override
    public String isComplete(String code) {
        return this.evaluator.isComplete(code);

        /*if (code.startsWith(SWITCH_LANGS_PREFIX)) {
            String langPrefix = code.substring(2);
            if (langPrefix.isBlank() || !langPrefix.chars().anyMatch(ch -> Character.isWhitespace(ch))) {
                return IS_COMPLETE_BAD;
            }
        }*/
    }

    @Override
    public ReplacementOptions complete(String code, int at) throws Exception {
        if (code.startsWith(SWITCH_LANGS_PREFIX)) {
            String langPrefix = code.substring(2, at);
            List<String> replacements = new ArrayList<>();
            boolean isLanguageName = !langPrefix.chars().anyMatch(ch -> Character.isWhitespace(ch));
            if (isLanguageName) {
                if (!ImageInfo.inImageCode() && startsWithIgnoreCase(langPrefix, "java")) {
                    replacements.add("java");
                }
                for (String language : evaluator.getGuestEvaluator().getLanguages()) {
                    if (startsWithIgnoreCase(langPrefix, language)) {
                        replacements.add(language);
                    }
                }
            }
            return new ReplacementOptions(replacements, 2, at);
        }
        return this.evaluator.complete(code, at);
    }

    private boolean startsWithIgnoreCase(String prefix, String text) {
        if (prefix.length() > text.length()) {
            return false;
        }
        if (prefix.isEmpty()) {
            return true;
        }
        return text.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    public Object evalRaw(String expr) throws Exception {
        //expr = this.magicsTransformer.transformMagics(expr);

        return this.evaluator.eval(expr);
    }

    @Override
    public DisplayData eval(String expr) throws Exception {
        Object result = this.evalRaw(expr);

        if (result != null)
            return result instanceof DisplayData
                    ? (DisplayData) result
                    : this.getRenderer().render(result);

        return null;
    }

    @Override
    public List<String> formatError(Exception e) {
        List<String> error = evaluator.formatError(e, errorStyler, ex -> super.formatError(ex));
        if (error != null) {
            return error;
        } else {
            return super.formatError(e);
        }
    }

    @Override
    public LanguageInfo getLanguageInfo() {
        return languageInfo;
    }

}
