package com.nike.riposte.server.testutils;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Helper methods for working with component tests.
 *
 * @author Nic Munroe
 */
public class ComponentTestUtils {

    public static int findFreePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }

}
