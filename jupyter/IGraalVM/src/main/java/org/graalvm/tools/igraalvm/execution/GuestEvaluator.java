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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.tools.igraalvm.GraalVMKernel;
import org.graalvm.tools.igraalvm.gui.Gui;

/**
 */
public final class GuestEvaluator implements Evaluator {

    private final Context guestContext;
    private final Set<String> allLanguages;
    private final Map<String, Set<String>> pristineBindings = new HashMap<>();
    private final PrintStream out;
    private final PrintStream err;
    private String lastLanguage;

    GuestEvaluator(InputStream in, PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
        guestContext = Context.newBuilder().allowAllAccess(true).in(in).out(out).err(err).build();
        allLanguages = guestContext.getEngine().getLanguages().entrySet().stream().filter(entry -> entry.getValue().isInteractive()).map(entry -> entry.getKey()).sorted().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> getLanguages() {
        return allLanguages;
    }

    @Override
    public String isComplete(String language, String code) {
        return GraalVMKernel.maybeCompleteCodeSignifier();
    }

    @Override
    public ReplacementOptions complete(String language, String code, int at) {
        return null;
    }

    @Override
    public Object eval(String language, String code) {
        if (language == null) {
            Iterator<String> languagesIt = allLanguages.iterator();
            if (!languagesIt.hasNext()) {
                err.println("Have no language to evaluate.");
                return null;
            }
            language = languagesIt.next();
        }
        if (!allLanguages.contains(language)) {
            String languages = allLanguages.toString();
            languages = languages.substring(1, languages.length() - 1);
            err.println("Language '" + language + "' is not supported.\nAvailable interactive languages are: " + (ImageInfo.inImageCode() ? languages : "java, " + languages));
            return null;
        }
        if (!language.equals(lastLanguage)) {
            if (!pristineBindings.containsKey(language)) {
                pristineBindings.put(language, new HashSet<>(guestContext.getBindings(language).getMemberKeys()));
            }
            if (lastLanguage != null) {
                copyBindings(lastLanguage, language);
            }
            lastLanguage = language;
        }
        Source source = Source.newBuilder(language, code, "eval").interactive(true).buildLiteral();
        Gui gui = Gui.get(guestContext, language);
        if (gui != null) {
            return gui.evaluate(guestContext, source);
        } else {
            guestContext.eval(source);
            return null;
        }
    }

    private void copyBindings(String fromLanguage, String toLanguage) {
        Value from = guestContext.getBindings(fromLanguage);
        Value to = guestContext.getBindings(toLanguage);
        Set<String> ignoreFrom = pristineBindings.get(fromLanguage);
        Set<String> ignoreTo = pristineBindings.get(toLanguage);
        boolean messagePrinted = false;
        List<String> unsupportedImport = null;
        for (String member : from.getMemberKeys()) {
            if (ignoreFrom.contains(member)) {
                continue;
            }
            String toMember = member;
            if ("ruby".equals(fromLanguage)) {
                if (toMember.startsWith("$")) {
                    toMember = toMember.substring(1);
                }
            } else if ("ruby".equals(toLanguage)) {
                toMember = "$" + toMember;
            }
            if (ignoreTo.contains(toMember)) {
                continue;
            }
            try {
                to.putMember(toMember, from.getMember(member));
                if (!messagePrinted) {
                    out.print("Imported variables: " + toMember);
                    messagePrinted = true;
                } else {
                    out.print(", " + toMember);
                }
            } catch (UnsupportedOperationException e) {
                // Can not be copied
                if (unsupportedImport == null) {
                    unsupportedImport = new ArrayList<>();
                }
                unsupportedImport.add(member);
            }
        }
        if (messagePrinted) {
            out.println();
        }
        if (unsupportedImport != null) {
            String unsupported = unsupportedImport.toString();
            err.println("Import of following variables is not supported: " + unsupported.substring(1, unsupported.length() - 1));
        }
    }

    public List<String> formatError(Exception e, StringStyler errorStyler, Function<Exception, List<String>> defaultFormat) {
        if (e instanceof PolyglotException) {
            List<String> fmt = new ArrayList<>();
            PolyglotException pe = (PolyglotException) e;
            if (pe.isGuestException()) {
                String message = pe.getMessage();
                if (!message.isEmpty()) {
                    fmt.add(errorStyler.primary(message));
                } else {
                    fmt.add(errorStyler.primary("Error"));
                }
                SourceSection sourceLocation = pe.getSourceLocation();
                if (sourceLocation != null) {
                    fmt.addAll(errorStyler.highlightSubstringLines(sourceLocation.getSource().getCharacters().toString(),
                                sourceLocation.getCharIndex(), sourceLocation.getCharEndIndex()));
                }
                for (StackFrame sf : pe.getPolyglotStackTrace()) {
                    if (sf.isGuestFrame()) {
                        fmt.add(errorStyler.secondary(sf.toString()));
                    }
                }
                return fmt;
            } else {
                // Print the stack trace by default
                return null;
            }
        }
        return null;
    }

    @Override
    public void interrupt() {
    }

    @Override
    public void shutdown() {
    }
}
