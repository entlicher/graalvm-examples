/**
 * Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
 * This file is made available under version 3 of the GNU General Public License.
 */
package org.graalvm.tools.igraalvm;

import io.github.spencerpark.jupyter.channels.JupyterConnection;
import io.github.spencerpark.jupyter.channels.JupyterSocket;
import io.github.spencerpark.jupyter.kernel.KernelConnectionProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;

public class IGraalVM {

    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Expecting a connection file as an argument");
        }

        JupyterSocket.JUPYTER_LOGGER.setLevel(Level.WARNING);
        String connectionInfo;
        try {
            connectionInfo = Files.readString(Paths.get(args[0]));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Can not read connection file", ex);
        }
        KernelConnectionProperties connProps = KernelConnectionProperties.parse(connectionInfo);
        JupyterConnection connection;
        try {
            connection = new JupyterConnection(connProps);
        } catch (InvalidKeyException | NoSuchAlgorithmException ex) {
            System.err.println("Bad connection information: " + ex.getLocalizedMessage());
            System.exit(1);
            return;
        }

        GraalVMKernel kernel = new GraalVMKernel();
        kernel.becomeHandlerForConnection(connection);

        connection.connect();
        connection.waitUntilClose();
    }
}
