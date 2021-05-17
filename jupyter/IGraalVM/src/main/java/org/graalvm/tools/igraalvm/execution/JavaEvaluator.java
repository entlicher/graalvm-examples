/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import io.github.spencerpark.jupyter.kernel.ReplacementOptions;
import io.github.spencerpark.jupyter.kernel.util.GlobFinder;
import io.github.spencerpark.jupyter.kernel.util.StringStyler;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import jdk.jshell.DeclarationSnippet;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.UnresolvedReferenceException;

import org.graalvm.nativeimage.ImageInfo;

import org.graalvm.tools.igraalvm.GraalVMKernel;
import static org.graalvm.tools.igraalvm.execution.Patterns.BLANK;
import static org.graalvm.tools.igraalvm.execution.Patterns.LAST_LINE;
import static org.graalvm.tools.igraalvm.execution.Patterns.WHITESPACE_PREFIX;

final class JavaEvaluator implements Evaluator {

    private static final String NO_MAGIC_RETURN = "\"__NO_MAGIC_RETURN\"";

    private final JShell shell;
    private final IJavaExecutionControlProvider executionControlProvider;
    private final String executionControlID;
    private final SourceCodeAnalysis sourceAnalyzer;

    private boolean isInitialized = false;
    private final List<String> startupScripts;

    private final String indentation = "  ";

    JavaEvaluator(List<String> compilerOpts, List<String> classpath, List<String> startupScripts, InputStream in, PrintStream out, PrintStream err, String timeout) {
        notInNative();
        this.executionControlProvider = new IJavaExecutionControlProvider();

        String executionControlID = UUID.randomUUID().toString();
        Map<String, String> executionControlParams = new LinkedHashMap<>();
        executionControlParams.put(IJavaExecutionControlProvider.REGISTRATION_ID_KEY, executionControlID);

        if (timeout != null)
            executionControlParams.put(IJavaExecutionControlProvider.TIMEOUT_KEY, timeout);

        JShell shell = null;
        JShell.Builder builder = JShell.builder();
        if (out != null) builder.out(out);
        if (err != null) builder.err(err);
        if (in != null) builder.in(in);

        shell = builder
                .executionEngine(executionControlProvider, executionControlParams)
                .compilerOptions(compilerOpts.toArray(new String[0]))
                .build();

        for (String cp : classpath) {
            if (BLANK.matcher(cp).matches()) continue;

            GlobFinder resolver = new GlobFinder(cp);
            try {
                for (Path entry : resolver.computeMatchingPaths())
                    shell.addToClasspath(entry.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new RuntimeException(String.format("IOException while computing classpath entries for '%s': %s", cp, e.getMessage()), e);
            }
        }
        this.shell = shell;
        this.executionControlID = executionControlID;
        this.sourceAnalyzer = shell != null ? this.shell.sourceCodeAnalysis() : null;
        this.startupScripts = startupScripts;
    }

    private void notInNative() {
        if (ImageInfo.inImageCode()) {
            throw new IllegalStateException("Can not have Java in the native image.");
        }
    }

    public JShell getShell() {
        return this.shell;
    }

    private SourceCodeAnalysis.CompletionInfo analyzeCompletion(String source) {
        return this.sourceAnalyzer.analyzeCompletion(source);
    }

    private void init() throws Exception {
        for (String script : this.startupScripts)
            eval(script);

        this.startupScripts.clear();
    }

    public ReplacementOptions complete(String language, String code, int at) {
        int[] replaceStart = new int[1]; // As of now this is always the same as the cursor...
        List<SourceCodeAnalysis.Suggestion> suggestions = this.getShell().sourceCodeAnalysis().completionSuggestions(code, at, replaceStart);
        if (suggestions == null || suggestions.isEmpty()) return null;

        List<String> options = suggestions.stream()
                .sorted((s1, s2) ->
                        s1.matchesType()
                                ? s2.matchesType() ? 0 : -1
                                : s2.matchesType() ? 1 : 0
                )
                .map(SourceCodeAnalysis.Suggestion::continuation)
                .distinct()
                .collect(Collectors.toList());

        return new ReplacementOptions(options, replaceStart[0], at);
    }

    protected Object evalJava(String code) throws Exception {
        IJavaExecutionControl executionControl =
                this.executionControlProvider.getRegisteredControlByID(this.executionControlID);

        List<SnippetEvent> events = this.shell.eval(code);

        Object result = null;

        // We iterate twice to make sure throwing an early exception doesn't leak the memory
        // and we `takeResult` everything.
        for (SnippetEvent event : events) {
            String key = event.value();
            if (key == null) continue;

            Snippet.SubKind subKind = event.snippet().subKind();

            // Only executable snippets make their way through the machinery we have setup in the
            // IJavaExecutionControl. Declarations for example simply take their default value without
            // being executed.
            Object value = subKind.isExecutable()
                    ? executionControl.takeResult(key)
                    : event.value();

            switch (subKind) {
                case VAR_VALUE_SUBKIND:
                case OTHER_EXPRESSION_SUBKIND:
                case TEMP_VAR_EXPRESSION_SUBKIND:
                    result = NO_MAGIC_RETURN.equals(value) ? null : value;
                    break;
                default:
                    result = null;
                    break;
            }
        }

        for (SnippetEvent event : events) {
            // If fresh snippet
            if (event.causeSnippet() == null) {
                JShellException e = event.exception();
                if (e != null) {
                    if (e instanceof EvalException) {
                        switch (((EvalException) e).getExceptionClassName()) {
                            case IJavaExecutionControl.EXECUTION_TIMEOUT_NAME:
                                throw new EvaluationTimeoutException(executionControl.getTimeoutDuration(), executionControl.getTimeoutUnit(), code.trim());
                            case IJavaExecutionControl.EXECUTION_INTERRUPTED_NAME:
                                throw new EvaluationInterruptedException(code.trim());
                            default:
                                throw e;
                        }
                    }

                    throw e;
                }

                if (!event.status().isDefined())
                    throw new JavaCompilationException(event);
            }
        }

        return result;
    }

    @Override
    public Object eval(String language, String code) throws Exception {
        return eval(code);
    }

    private Object eval(String code) throws Exception {
        // The init() method runs some code in the shell to initialize the environment. As such
        // it is deferred until the first user requested evaluation to cleanly return errors when
        // they happen.
        if (!this.isInitialized) {
            this.isInitialized = true;
            init();
        }

        Object evalResult = null;
        SourceCodeAnalysis.CompletionInfo info;
        for (info = this.sourceAnalyzer.analyzeCompletion(code); info.completeness().isComplete(); info = analyzeCompletion(info.remaining())) {
            evalResult = this.evalJava(info.source());
        }
        if (info.completeness() != SourceCodeAnalysis.Completeness.EMPTY) {
            throw new IncompleteSourceException(info.remaining().trim());
        }
        return evalResult;
    }

    private String computeJavaIndentation(String partialStatement) {
        // Find the indentation of the last line
        Matcher m = WHITESPACE_PREFIX.matcher(partialStatement);
        String currentIndentation = m.find() ? m.group("ws") : "";

        m = LAST_LINE.matcher(partialStatement);
        if (!m.find())
            throw new Error("Pattern broken. Every string should have a last line.");

        // If a brace or paren was opened on the last line and not closed, indent some more.
        String lastLine = m.group("last");
        int newlyOpenedBraces = -1;
        int newlyOpenedParens = -1;
        for (int i = 0; i < lastLine.length(); i++) {
            switch (lastLine.charAt(i)) {
                case '}':
                    // Ignore closing if one has not been opened on this line yet
                    if (newlyOpenedBraces == -1) continue;
                    // Otherwise close an opened one from this line
                    newlyOpenedBraces--;
                    break;
                case ')':
                    // Same as for braces, but with the parens
                    if (newlyOpenedParens == -1) continue;
                    newlyOpenedParens--;
                    break;
                case '{':
                    // A brace was opened on this line!
                    // If the first then get out og the -1 special case with an extra addition
                    if (newlyOpenedBraces == -1) newlyOpenedBraces++;
                    newlyOpenedBraces++;
                    break;
                case '(':
                    if (newlyOpenedParens == -1) newlyOpenedParens++;
                    newlyOpenedParens++;
                    break;
            }
        }

        return newlyOpenedBraces > 0 || newlyOpenedParens > 0
                ? currentIndentation + this.indentation
                : currentIndentation;
    }

    @Override
    public String isComplete(String language, String code) {
        SourceCodeAnalysis.CompletionInfo info = this.sourceAnalyzer.analyzeCompletion(code);
        while (info.completeness().isComplete())
            info = analyzeCompletion(info.remaining());

        switch (info.completeness()) {
            case UNKNOWN:
                // Unknown means "bad code" and the only way to see if is complete is
                // to execute it.
                return GraalVMKernel.invalidCodeSignifier();
            case COMPLETE:
            case COMPLETE_WITH_SEMI:
            case EMPTY:
                return GraalVMKernel.completeCodeSignifier();
            case CONSIDERED_INCOMPLETE:
            case DEFINITELY_INCOMPLETE:
                // Compute the indent of the last line and match it
                return this.computeJavaIndentation(info.remaining());
            default:
                // For completeness, return an "I don't know" if we somehow get down here
                return GraalVMKernel.maybeCompleteCodeSignifier();
        }
    }

    @Override
    public List<String> formatError(Exception e, StringStyler errorStyler, Function<Exception, List<String>> defaultFormat) {
        if (e instanceof JavaCompilationException) {
            return formatJavaCompilationException((JavaCompilationException) e, errorStyler);
        } else if (e instanceof IncompleteSourceException) {
            return formatIncompleteSourceException((IncompleteSourceException) e, errorStyler);
        } else if (e instanceof EvalException) {
            return formatEvalException((EvalException) e, errorStyler, defaultFormat);
        } else if (e instanceof UnresolvedReferenceException) {
            return formatUnresolvedReferenceException((UnresolvedReferenceException) e, errorStyler);
        }
        return null;
    }

    private List<String> formatJavaCompilationException(JavaCompilationException e, StringStyler errorStyler) {
        List<String> fmt = new ArrayList<>();
        SnippetEvent event = e.getBadSnippetCompilation();
        Snippet snippet = event.snippet();
        getShell().diagnostics(snippet)
                .forEach(d -> {
                    // If has line information related, highlight that span
                    if (d.getStartPosition() >= 0 && d.getEndPosition() >= 0)
                        fmt.addAll(errorStyler.highlightSubstringLines(snippet.source(),
                                (int) d.getStartPosition(), (int) d.getEndPosition()));
                    else
                        fmt.addAll(errorStyler.primaryLines(snippet.source()));

                    // Add the error message
                    for (String line : StringStyler.splitLines(d.getMessage(null))) {
                        // Skip the information about the location of the error as it is highlighted instead
                        if (!line.trim().startsWith("location:"))
                            fmt.add(errorStyler.secondary(line));
                    }

                    fmt.add(""); // Add a blank line
                });
        if (snippet instanceof DeclarationSnippet) {
            List<String> unresolvedDependencies = getShell().unresolvedDependencies((DeclarationSnippet) snippet)
                    .collect(Collectors.toList());
            if (!unresolvedDependencies.isEmpty()) {
                fmt.addAll(errorStyler.primaryLines(snippet.source()));
                fmt.add(errorStyler.secondary("Unresolved dependencies:"));
                unresolvedDependencies.forEach(dep ->
                        fmt.add(errorStyler.secondary("   - " + dep)));
            }
        }

        return fmt;
    }

    private static List<String> formatIncompleteSourceException(IncompleteSourceException e, StringStyler errorStyler) {
        List<String> fmt = new ArrayList<>();

        String source = e.getSource();
        fmt.add(errorStyler.secondary("Incomplete input:"));
        fmt.addAll(errorStyler.primaryLines(source));

        return fmt;
    }

    private static List<String> formatEvalException(EvalException e, StringStyler errorStyler, Function<Exception, List<String>> defaultFormat) {
        List<String> fmt = new ArrayList<>();


        String evalExceptionClassName = EvalException.class.getName();
        String actualExceptionName = e.getExceptionClassName();
        defaultFormat.apply(e).stream()
                .map(line -> line.replace(evalExceptionClassName, actualExceptionName))
                .forEach(fmt::add);

        return fmt;
    }

    private List<String> formatUnresolvedReferenceException(UnresolvedReferenceException e, StringStyler errorStyler) {
        List<String> fmt = new ArrayList<>();

        DeclarationSnippet snippet = e.getSnippet();

        List<String> unresolvedDependencies = getShell().unresolvedDependencies(snippet)
                .collect(Collectors.toList());
        if (!unresolvedDependencies.isEmpty()) {
            fmt.addAll(errorStyler.primaryLines(snippet.source()));
            fmt.add(errorStyler.secondary("Unresolved dependencies:"));
            unresolvedDependencies.forEach(dep ->
                    fmt.add(errorStyler.secondary("   - " + dep)));
        }

        return fmt;
    }

    @Override
    public void interrupt() {
        IJavaExecutionControl executionControl =
                this.executionControlProvider.getRegisteredControlByID(this.executionControlID);

        if (executionControl != null)
            executionControl.interrupt();
    }

    @Override
    public void shutdown() {
        this.shell.close();
    }
}
