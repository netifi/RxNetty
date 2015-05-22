/*
 * Copyright 2015 Netflix, Inc.
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

package io.reactivex.netty.examples.tcp.secure;

import io.netty.buffer.ByteBuf;
import io.reactivex.netty.codec.StringLineDecoder;
import io.reactivex.netty.examples.AbstractServerExample;
import io.reactivex.netty.protocol.tcp.server.TcpServer;

import java.nio.charset.Charset;

/**
 * A TCP echo server that decodes the data received on the socket using {@link StringLineDecoder} and echoes the same
 * after prepending "echo =>" to the message.
 */
public final class SecureEchoServer extends AbstractServerExample {

    public static void main(final String[] args) {
        TcpServer<ByteBuf, ByteBuf> server = TcpServer.newServer(0)
                                                      .unsafeSecure()
                                                      .start(connection ->
                     connection.writeStringAndFlushOnEach(connection.getInput()
                                                                    .map(bb -> bb.toString(Charset.defaultCharset()))
                                                                    .doOnNext(logger::error)
                                                                    .map(msg -> "echo => " + msg)));

        if (shouldWaitForShutdown(args)) {
            /*When testing the args are set, to avoid blocking till shutdown*/
            server.awaitShutdown();
        }

        setServerPort(server.getServerPort());
    }
}