/*
 * Copyright 2015 Odnoklassniki Ltd, Mail.Ru Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package one.nio.server;

import one.nio.net.Selector;
import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.net.SslContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;

final class AcceptorThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(AcceptorThread.class);
    
    final Server server;
    final InetAddress address;
    final int port;
    final Random random;
    final Socket serverSocket;

    long acceptedSessions;
    long rejectedSessions;

    AcceptorThread(Server server, InetAddress address, int port, SslContext sslContext,
                   int backlog, int recvBuf, int sendBuf, boolean defer, boolean noDelay) throws IOException {
        super("NIO Acceptor " + address + ":" + port);

        this.server = server;
        this.address = address;
        this.port = port;
        this.random = new Random();

        Socket serverSocket = Socket.createServerSocket();
        if (sslContext != null) serverSocket = serverSocket.ssl(sslContext);
        this.serverSocket = serverSocket;

        if (recvBuf != 0) serverSocket.setRecvBuffer(recvBuf);
        if (sendBuf != 0) serverSocket.setSendBuffer(sendBuf);
        if (defer) serverSocket.setDeferAccept(true);

        serverSocket.setNoDelay(noDelay);
        serverSocket.setReuseAddr(true);
        serverSocket.bind(address, port, backlog);
    }

    void shutdown() {
        serverSocket.close();
        try {
            join();
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    @Override
    public void run() {
        while (serverSocket.isOpen()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setBlocking(false);
                Session session = server.createSession(socket);
                getSmallestSelector().register(session);
                acceptedSessions++;
            } catch (RejectedSessionException e) {
                if (log.isDebugEnabled()) {
                    log.debug("Rejected session from " + socket.getRemoteAddress(), e);
                }
                rejectedSessions++;
                socket.close();
            } catch (Throwable e) {
                if (serverSocket.isOpen()) {
                    log.error("Cannot accept incoming connection", e);
                }
                if (socket != null) socket.close();
            }
        }
    }

    private Selector getSmallestSelector() {
        SelectorThread[] selectors = server.selectors;
        Selector a = selectors[random.nextInt(selectors.length)].selector;
        Selector b = selectors[random.nextInt(selectors.length)].selector;
        return a.size() < b.size() ? a : b;
    }
}
