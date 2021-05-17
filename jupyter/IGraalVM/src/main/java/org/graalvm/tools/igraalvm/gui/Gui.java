/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.gui;

import io.github.spencerpark.jupyter.kernel.display.DisplayData;
import java.util.HashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/**
 *
 * @author martin
 */
public abstract class Gui {

    private static final Map<String, Gui> guis = new HashMap<>();

    public static Gui get(Context context, String language) {
        return guis.computeIfAbsent(language, l -> createGui(context, l));
    }

    private static Gui createGui(Context context, String language) {
        switch (language) {
            case "R":
                return new GuiR(context);
            default:
                return null;
        }
    }

    public abstract DisplayData evaluate(Context context, Source code);
}
