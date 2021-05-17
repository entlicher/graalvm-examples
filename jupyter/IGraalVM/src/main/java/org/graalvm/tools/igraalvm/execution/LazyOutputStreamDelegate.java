/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Supplier;

final class LazyOutputStreamDelegate extends OutputStream {
    private final Supplier<OutputStream> writeTo;

    public LazyOutputStreamDelegate(Supplier<OutputStream> writeTo) {
        this.writeTo = writeTo;
    }

    @Override
    public void write(int b) throws IOException {
        this.writeTo.get().write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        this.writeTo.get().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        this.writeTo.get().write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        this.writeTo.get().flush();
    }

    @Override
    public void close() throws IOException {
        this.writeTo.get().close();
    }
}
