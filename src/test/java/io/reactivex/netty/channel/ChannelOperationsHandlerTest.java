/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 * Modifications Copyright (c) 2017 RxNetty Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivex.netty.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.reactivex.Flowable;
import io.reactivex.netty.FutureFlowable;
import io.reactivex.netty.SocketUtils;
import io.reactivex.netty.http.client.HttpClient;
import io.reactivex.netty.http.server.HttpServer;
import org.junit.Test;
import io.reactivex.netty.NettyContext;
import io.reactivex.netty.http.client.HttpClientResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class ChannelOperationsHandlerTest {

	@Test
	public void publisherSenderOnCompleteFlushInProgress() {
		NettyContext server =
				HttpServer.create(0)
				          .newHandler((req, res) ->
				                  req.receive()
				                     .asString()
				                     .doOnNext(System.err::println)
														 .ignoreElements()
				                     .andThen(res.status(200).sendHeaders().then()))
				          .blockingSingle();

		Flowable<String> flux = Flowable.range(1, 257).map(count -> count + "");
		Flowable<HttpClientResponse> client =
				HttpClient.create(server.address().getPort())
				          .post("/", req -> req.sendString(flux));

		client.test()
				.awaitDone(30, TimeUnit.SECONDS)
				.assertValue(res -> res.status().code() == 200)
				.assertComplete();
	}

	@Test
	public void keepPrefetchSizeConstantEqualsWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(1024, 1024);
	}

	@Test
	public void keepPrefetchSizeConstantDifferentWriteBufferLowHighWaterMark() {
		doTestPrefetchSize(0, 1024);
	}

	private void doTestPrefetchSize(int writeBufferLowWaterMark, int writeBufferHighWaterMark) {
		ChannelOperationsHandler handler = new ChannelOperationsHandler(null);

		EmbeddedChannel channel = new EmbeddedChannel(handler);
		channel.config().setWriteBufferLowWaterMark(writeBufferLowWaterMark)
		                .setWriteBufferHighWaterMark(writeBufferHighWaterMark);

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();

		FutureFlowable.deferFuture(() -> channel.writeAndFlush(Flowable.range(0, 70)))
				.test()
				.awaitDone(30, TimeUnit.SECONDS)
				.assertComplete();

		assertThat(handler.prefetch == (handler.inner.requested - handler.inner.produced)).isTrue();
	}

	@Test
	public void testChannelInactiveThrowsIOException() throws Exception {
		ExecutorService threadPool = Executors.newCachedThreadPool();

		int abortServerPort = SocketUtils.findAvailableTcpPort();
		ConnectionAbortServer abortServer = new ConnectionAbortServer(abortServerPort);

		threadPool.submit(abortServer);

		if(!abortServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("Fail to start test server");
		}

		Flowable<HttpClientResponse> response =
				HttpClient.create(ops -> ops.host("localhost")
				                            .port(abortServerPort))
				          .get("/",
						          req -> req.sendHeaders()
						                    .sendString(Flowable.just("a", "b", "c")));

		response.test()
				.awaitDone(30, TimeUnit.SECONDS)
				.assertError(EncoderException.class);

		abortServer.close();
	}

	private static final class ConnectionAbortServer extends CountDownLatch implements Runnable {

		private final int port;
		private final ServerSocketChannel server;
		private volatile boolean read = false;
		private volatile Thread thread;

		private ConnectionAbortServer(int port) {
			super(1);
			this.port = port;
			try {
				server = ServerSocketChannel.open();
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void run() {
			try {
				server.configureBlocking(true);
				server.socket()
				      .bind(new InetSocketAddress(port));
				countDown();
				thread = Thread.currentThread();
				while (true) {
					SocketChannel ch = server.accept();

					while (true) {
						int bytes = ch.read(ByteBuffer.allocate(256));
						if (bytes > 0) {
							if (!read) {
								read = true;
							}
							else {
								ch.close();
								return;
							}
						}
					}
				}
			}
			catch (IOException e) {
			}
		}

		public void close() throws IOException {
			Thread thread = this.thread;
			if (thread != null) {
				thread.interrupt();
			}
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}
}
