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

package io.reactivex.netty.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.netty.NettyContext;
import io.reactivex.netty.NettyPipeline;
import io.reactivex.netty.SocketUtils;
import io.reactivex.netty.channel.AbortedException;
import io.reactivex.netty.http.client.HttpClient;
import io.reactivex.schedulers.Schedulers;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class TcpClientTests {

	private final ExecutorService threadPool = Executors.newCachedThreadPool();
	int                     echoServerPort;
	EchoServer              echoServer;
	int                     abortServerPort;
	ConnectionAbortServer   abortServer;
	int                     timeoutServerPort;
	ConnectionTimeoutServer timeoutServer;
	int                     heartbeatServerPort;
	HeartbeatServer         heartbeatServer;

	@Before
	public void setup() throws Exception {
		echoServerPort = SocketUtils.findAvailableTcpPort();
		echoServer = new EchoServer(echoServerPort);
		threadPool.submit(echoServer);
		if(!echoServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		abortServerPort = SocketUtils.findAvailableTcpPort();
		abortServer = new ConnectionAbortServer(abortServerPort);
		threadPool.submit(abortServer);
		if(!abortServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		timeoutServerPort = SocketUtils.findAvailableTcpPort();
		timeoutServer = new ConnectionTimeoutServer(timeoutServerPort);
		threadPool.submit(timeoutServer);
		if(!timeoutServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}

		heartbeatServerPort = SocketUtils.findAvailableTcpPort();
		heartbeatServer = new HeartbeatServer(heartbeatServerPort);
		threadPool.submit(heartbeatServer);
		if(!heartbeatServer.await(10, TimeUnit.SECONDS)){
			throw new IOException("fail to start test server");
		}
	}

	@After
	public void cleanup() throws InterruptedException, IOException {
		echoServer.close();
		abortServer.close();
		timeoutServer.close();
		heartbeatServer.close();
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Thread.sleep(500);
	}

	@Test
	public void testTcpClient() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		NettyContext client = TcpClient.create("localhost", echoServerPort)
		                               .newHandler((in, out) -> {
			                               in.receive()
			                                 .subscribe(s -> latch.countDown());

			                               return out.sendString(Flowable.just("Hello World!"))
			                                  .neverComplete();
		                               })
		                               .blockingSingle();

		latch.await(30, TimeUnit.SECONDS);

		client.dispose();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void testTcpClientWithInetSocketAddress() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);

		TcpClient client =
				TcpClient.create(echoServerPort);

		NettyContext s = client.newHandler((in, out) -> {
			in.receive()
			  .subscribe(d -> latch.countDown());

			return out.sendString(Flowable.just("Hello"))
			   .neverComplete();
		})
		                       .blockingSingle();

		latch.await(5, TimeUnit.SECONDS);

		s.dispose();

		assertThat("latch was counted down", latch.getCount(), is(0L));
	}

	@Test
	public void tcpClientHandlesLineFeedData() throws InterruptedException {
		final int messages = 100;
		final CountDownLatch latch = new CountDownLatch(messages);
		final List<String> strings = new ArrayList<String>();

				TcpClient.create(opts -> opts.host("localhost")
				                             .port(echoServerPort)
				                             .afterChannelInit(c -> c.pipeline()
				                                                     .addBefore(
						                                                     NettyPipeline.ReactiveBridge,
						                                                     "codec",
						                                                     new LineBasedFrameDecoder(
								                                                     8 * 1024))))
				         .newHandler((in, out) ->
					        out.sendString(Flowable.range(1, messages)
					                            .map(i -> "Hello World!" + i + "\n")
					                            .subscribeOn(Schedulers.computation()))
					            .then( in.receive()
					                     .asString()
					                     .take(100)
					                     .flatMapIterable(s -> Arrays.asList(s.split("\\n")))
					                     .doOnNext(s -> {
						                     strings.add(s);
						                     latch.countDown();
					                     }).ignoreElements().toFlowable())
				         )
				         .blockingSingle()
				         .onClose()
				         .blockingSubscribe();

		assertTrue("Expected messages not received. Received " + strings.size() + " messages: " + strings,
				latch.await(15, TimeUnit.SECONDS));

		assertEquals(messages, strings.size());
	}

	@Test
	public void closingPromiseIsFulfilled() throws InterruptedException {
		TcpClient client =
				TcpClient.create(opts -> opts.host("localhost")
				                             .port(abortServerPort)
				                             .disablePool());

		client.newHandler((in, out) -> Flowable.empty())
		      .blockingSingle()
		      .onClose()
		      .blockingSubscribe();
	}

	@Test
	public void connectionWillRetryConnectionAttemptWhenItFails()
			throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();

		TcpClient.create(ops -> ops.host("localhost")
		                           .port(abortServerPort + 3)
		                           .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100))
		         .newHandler((in, out) -> Flowable.never())
		         .retryWhen(errors -> errors.zipWith(Flowable.range(1, 4), (a, b) -> b)
		                                    .flatMap(attempt -> {
			                                    switch (attempt) {
				                                    case 1:
					                                    totalDelay.addAndGet(100);
					                                    return Flowable.timer(100, TimeUnit.MILLISECONDS);
				                                    case 2:
					                                    totalDelay.addAndGet(500);
					                                    return Flowable.timer(500, TimeUnit.MILLISECONDS);
				                                    case 3:
					                                    totalDelay.addAndGet(1000);
					                                    return Flowable.timer(1, TimeUnit.SECONDS);
				                                    default:
					                                    latch.countDown();
					                                    return Flowable.<Long>empty();
			                                    }
		                                    }))
		         .subscribe(System.out::println);

		latch.await(5, TimeUnit.SECONDS);
		assertTrue("latch was counted down:" + latch.getCount(), latch.getCount() == 0);
		assertThat("totalDelay was >1.6s", totalDelay.get(), greaterThanOrEqualTo(1600L));
	}

	@Test
	public void connectionWillAttemptToReconnectWhenItIsDropped()
			throws InterruptedException, IOException {
		final CountDownLatch connectionLatch = new CountDownLatch(1);
		final CountDownLatch reconnectionLatch = new CountDownLatch(1);

		try {
			TcpClient tcpClient =
					TcpClient.create(opts -> opts.host("localhost")
					                             .port(abortServerPort)
					                             .disablePool());

			Flowable<? extends NettyContext> handler = tcpClient.newHandler((in, out) -> {
				System.out.println("Start");
				connectionLatch.countDown();
				in.receive()
				  .subscribe();
				return Flowable.never();
			});

			handler.blockingSingle()
			       .onClose()
			       .ignoreElements()
			       .andThen(handler.doOnComplete(() -> reconnectionLatch.countDown()))
			       .blockingSubscribe();

			assertTrue("Initial connection is made", connectionLatch.await(5, TimeUnit.SECONDS));
			assertTrue("A reconnect attempt was made", reconnectionLatch.await(5, TimeUnit.SECONDS));
		}
		catch (AbortedException ise){
			return;
		}
	}

	@Test
	public void consumerSpecAssignsEventHandlers()
			throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(2);
		final CountDownLatch close = new CountDownLatch(1);
		final AtomicLong totalDelay = new AtomicLong();
		final long start = System.currentTimeMillis();

		TcpClient client =
				TcpClient.create(opts -> opts.host("localhost").port(timeoutServerPort));

		NettyContext s = client.newHandler((in, out) -> {
			in.onReadIdle(500, () -> {
				  totalDelay.addAndGet(System.currentTimeMillis() - start);
				  latch.countDown();
			})
			  .context()
			  .onClose(close::countDown);

			out.onWriteIdle(500, () -> {
				totalDelay.addAndGet(System.currentTimeMillis() - start);
				latch.countDown();
			});

			return Completable.timer(3, TimeUnit.SECONDS)
			           .toFlowable();
		})
		                       .blockingSingle();

		assertTrue("latch was counted down", latch.await(5, TimeUnit.SECONDS));
		assertTrue("close was counted down", close.await(30, TimeUnit.SECONDS));
		assertThat("totalDelay was >500ms", totalDelay.get(), greaterThanOrEqualTo(500L));
		s.dispose();
	}

	@Test
	public void readIdleDoesNotFireWhileDataIsBeingRead()
			throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		TcpClient client = TcpClient.create("localhost", heartbeatServerPort);

		NettyContext s = client.newHandler((in, out) -> {
			in.onReadIdle(500, latch::countDown);
			return Flowable.never();
		})
		                       .blockingSingle();

		assertTrue(latch.await(15, TimeUnit.SECONDS));
		heartbeatServer.close();

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500L)));
		s.dispose();
	}

	@Test
	public void writeIdleDoesNotFireWhileDataIsBeingSent()
			throws InterruptedException, IOException {
		final CountDownLatch latch = new CountDownLatch(1);
		long start = System.currentTimeMillis();

		NettyContext client = TcpClient.create("localhost", echoServerPort)
		                               .newHandler((in, out) -> {
			                               System.out.println("hello");
			                               out.onWriteIdle(500, latch::countDown);

			                               List<Publisher<Void>> allWrites =
					                               new ArrayList<>();
			                               for (int i = 0; i < 5; i++) {
				                               allWrites.add(out.sendString(Flowable.just("a")
				                                                                .delay(70, TimeUnit.MILLISECONDS)));
			                               }
			                               return Flowable.merge(allWrites);
		                               })
		                               .blockingSingle();

		System.out.println("Started");

		assertTrue(latch.await(5, TimeUnit.SECONDS));

		long duration = System.currentTimeMillis() - start;

		assertThat(duration, is(greaterThanOrEqualTo(500l)));
		client.dispose();
	}

	@Test
	public void nettyNetChannelAcceptsNettyChannelHandlers() throws InterruptedException {
		HttpClient client = HttpClient.create();

		final CountDownLatch latch = new CountDownLatch(1);
		System.out.println(client.get("http://www.google.com/?q=test%20d%20dq")
		                         .flatMapSingle(r -> r.receive()
		                                     .asString()
		                                     .toList())
		                         .doOnComplete(() -> latch.countDown())
		                         .blockingSingle());

		assertTrue("Latch didn't time out", latch.await(15, TimeUnit.SECONDS));
	}

	@Test
	public void toStringShowsOptions() {
		TcpClient client = TcpClient.create(opt -> opt.host("foo").port(123));

		Assertions.assertThat(client.toString()).isEqualTo("TcpClient: connecting to foo:123");
	}

	@Test
	public void gettingOptionsDuplicates() {
		TcpClient client = TcpClient.create(opt -> opt.host("foo").port(123));
		Assertions.assertThat(client.options())
		          .isNotSameAs(client.options)
		          .isNotSameAs(client.options());
	}

	private static final class EchoServer
			extends CountDownLatch
			implements Runnable {

		private final    int                 port;
		private final    ServerSocketChannel server;
		private volatile Thread              thread;

		private EchoServer(int port) {
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

					ByteBuffer buffer = ByteBuffer.allocate(8192);
					while (true) {
						int read = ch.read(buffer);
						if (read > 0) {
							buffer.flip();
						}

						int written = ch.write(buffer);
						if (written < 0) {
							throw new IOException("Cannot write to client");
						}
						buffer.rewind();
					}
				}
			}
			catch (IOException e) {
				// Server closed
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

	private static final class ConnectionAbortServer
			extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

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
				while (true) {
					SocketChannel ch = server.accept();
					System.out.println("ABORTING");
					ch.close();
				}
			}
			catch (Exception e) {
				// Server closed
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class ConnectionTimeoutServer
			extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

		private ConnectionTimeoutServer(int port) {
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
				while (true) {
					SocketChannel ch = server.accept();
					ByteBuffer buff = ByteBuffer.allocate(1);
					ch.read(buff);
				}
			}
			catch (IOException e) {
			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

	private static final class HeartbeatServer extends CountDownLatch
			implements Runnable {

		final         int                 port;
		private final ServerSocketChannel server;

		private HeartbeatServer(int port) {
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
				while (true) {
					SocketChannel ch = server.accept();
					while (server.isOpen()) {
						ByteBuffer out = ByteBuffer.allocate(1);
						out.put((byte) '\n');
						out.flip();
						ch.write(out);
						Thread.sleep(100);
					}
				}
			}
			catch (IOException e) {
				// Server closed
			}
			catch (InterruptedException ie) {

			}
		}

		public void close() throws IOException {
			ServerSocketChannel server = this.server;
			if (server != null) {
				server.close();
			}
		}
	}

}
