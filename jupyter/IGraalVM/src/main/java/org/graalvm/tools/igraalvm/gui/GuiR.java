/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.gui;

import io.github.spencerpark.jupyter.kernel.display.DisplayData;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 *
 * @author martin
 */
public class GuiR extends Gui {
    
    private static final String GRAPHICS_PREPARE = "grDevices:::svg()";
    private static final String GRAPHICS_GET = "grDevices:::svg.off()";

    private final Value graphicsPrepare;
    private final Value graphicsGet;

    GuiR(Context context) {
        graphicsPrepare = context.parse(Source.newBuilder("R", GRAPHICS_PREPARE, "PrepareGraphics.r").internal(true).buildLiteral());
        graphicsGet = context.parse(Source.newBuilder("R", GRAPHICS_GET, "GetGraphics.r").internal(true).buildLiteral());

    }
    @Override
    public DisplayData evaluate(Context context, Source code) {
        graphicsPrepare.execute();
        context.eval(code);
        String svg = graphicsGet.execute().asString();
        if (svg.isEmpty()) {
            return null;
        }
        DisplayData displayData = new DisplayData();
        displayData.putSVG(svg);
        return displayData;
    }
}
