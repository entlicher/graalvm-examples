/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import io.github.spencerpark.jupyter.kernel.util.GlobFinder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.graalvm.tools.igraalvm.execution.Patterns.BLANK;
import static org.graalvm.tools.igraalvm.execution.Patterns.PATH_SPLITTER;

public final class CodeEvaluatorBuilder {

    private static final int BUFFER_SIZE = 1024;

    private static final OutputStream STDOUT = new LazyOutputStreamDelegate(() -> System.out);
    private static final OutputStream STDERR = new LazyOutputStreamDelegate(() -> System.err);
    private static final InputStream STDIN = new LazyInputStreamDelegate(() -> System.in);

    private String timeout;
    private final List<String> classpath;
    private final List<String> compilerOpts;
    private PrintStream out;
    private PrintStream err;
    private InputStream in;
    private List<String> startupScripts;

    public CodeEvaluatorBuilder() {
        this.classpath = new LinkedList<>();
        this.compilerOpts = new LinkedList<>();
        this.startupScripts = new LinkedList<>();
    }

    public CodeEvaluatorBuilder addClasspathFromString(String classpath) {
        if (classpath == null) return this;
        if (BLANK.matcher(classpath).matches()) return this;

        Collections.addAll(this.classpath, PATH_SPLITTER.split(classpath));

        return this;
    }

    public CodeEvaluatorBuilder timeoutFromString(String timeout) {
        this.timeout = timeout;
        return this;
    }

    public CodeEvaluatorBuilder timeout(long timeout, TimeUnit timeoutUnit) {
        return this.timeoutFromString(String.format("%d %s", timeout, timeoutUnit.name()));
    }

    public CodeEvaluatorBuilder compilerOptsFromString(String opts) {
        if (opts == null) return this;
        this.compilerOpts.addAll(split(opts));
        return this;
    }

    public CodeEvaluatorBuilder compilerOpts(String... opts) {
        Collections.addAll(this.compilerOpts, opts);
        return this;
    }

    public CodeEvaluatorBuilder stdout(PrintStream out) {
        this.out = out;
        return this;
    }

    public CodeEvaluatorBuilder stderr(PrintStream err) {
        this.err = err;
        return this;
    }

    public CodeEvaluatorBuilder stdin(InputStream in) {
        this.in = in;
        return this;
    }

    public CodeEvaluatorBuilder sysStdout() {
        return this.stdout(new PrintStream(CodeEvaluatorBuilder.STDOUT));
    }

    public CodeEvaluatorBuilder sysStderr() {
        return this.stderr(new PrintStream(CodeEvaluatorBuilder.STDERR));
    }

    public CodeEvaluatorBuilder sysStdin() {
        return this.stdin(CodeEvaluatorBuilder.STDIN);
    }

    public CodeEvaluatorBuilder startupScript(String script) {
        if (script == null) return this;
        this.startupScripts.add(script);
        return this;
    }

    public CodeEvaluatorBuilder startupScript(InputStream scriptStream) {
        if (scriptStream == null) return this;

        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = scriptStream.read(buffer)) != -1)
                result.write(buffer, 0, read);

            String script = result.toString(StandardCharsets.UTF_8.name());

            this.startupScripts.add(script);
        } catch (IOException e) {
            throw new RuntimeException(String.format("IOException while reading startup script from stream: %s", e.getMessage()), e);
        } finally {
            try {
                scriptStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return this;
    }

    public CodeEvaluatorBuilder startupScriptFiles(String paths) {
        if (paths == null) return this;
        if (BLANK.matcher(paths).matches()) return this;

        for (String glob : PATH_SPLITTER.split(paths)) {
            GlobFinder resolver = new GlobFinder(glob);
            try {
                for (Path path : resolver.computeMatchingPaths())
                    this.startupScriptFile(path);
            } catch (IOException e) {
                throw new RuntimeException(String.format("IOException while computing startup scripts for '%s': %s", glob, e.getMessage()), e);
            }
        }

        return this;
    }

    public CodeEvaluatorBuilder startupScriptFile(Path path) {
        if (path == null) return this;

        if (!Files.isRegularFile(path))
            return this;

        if (!Files.isReadable(path))
            return this;

        try {
            String script = new String(Files.readAllBytes(path), "UTF-8");
            this.startupScripts.add(script);
        } catch (IOException e) {
            throw new RuntimeException(String.format("IOException while loading startup script for '%s': %s", path, e.getMessage()), e);
        }

        return this;
    }

    public CodeEvaluator build() {
        return new CodeEvaluator(compilerOpts, classpath, startupScripts, in, out, err, timeout);
    }

    private static List<String> split(String opts) {
        opts = opts.trim();

        List<String> split = new LinkedList<>();

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escape = false;
        for (char c : opts.toCharArray()) {
            switch (c) {
                case ' ':
                case '\t':
                    if (inQuotes) {
                        current.append(c);
                    } else if (current.length() > 0) {
                        // If whitespace is closing the string the add the current and reset
                        split.add(current.toString());
                        current.setLength(0);
                    }
                    break;
                case '\\':
                    if (escape) {
                        current.append("\\\\");
                        escape = false;
                    } else {
                        escape = true;
                    }
                    break;
                case '\"':
                    if (escape) {
                        current.append('"');
                        escape = false;
                    } else {
                        if (current.length() > 0 && inQuotes) {
                            split.add(current.toString());
                            current.setLength(0);
                            inQuotes = false;
                        } else {
                            inQuotes = true;
                        }
                    }
                    break;
                default:
                    current.append(c);
            }
        }

        if (current.length() > 0) {
            split.add(current.toString());
        }

        return split;
    }
}
