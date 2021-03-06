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

package io.reactivex.netty.http.client;

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.reactivex.functions.Consumer;
import io.reactivex.netty.http.websocket.WebsocketInbound;
import io.reactivex.netty.http.websocket.WebsocketOutbound;
import io.reactivex.netty.NettyPipeline;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class HttpClientWSOperations extends HttpClientOperations
		implements WebsocketInbound, WebsocketOutbound, Consumer<Throwable> {

	final WebSocketClientHandshaker handshaker;
	final ChannelPromise            handshakerResult;

	volatile int closeSent;

	HttpClientWSOperations(URI currentURI,
			String protocols,
			HttpClientOperations replaced) {
		super(replaced.channel(), replaced);

		Channel channel = channel();

		//The following is commented until further review - this allows a user to
		// override websocket headers written (especially key hash) if the original
		// headers contain UPGRADE:websocket.
		/*if (replaced.requestHeaders.contains(HttpHeaderNames.UPGRADE, HttpHeaderValues
				.WEBSOCKET, true)) {
			handshaker = new WebSocketClientHandshaker13(currentURI,
					WebSocketVersion.V13,
					protocols,
					true,
					null,
					65536) {
				@Override
				protected FullHttpRequest newHandshakeRequest() {
					FullHttpRequest request =
							new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
									HttpMethod.GET,
									replaced.uri());
					request.headers()
					       .set(replaced.requestHeaders);
					return request;
				}

				@Override
				protected void verify(FullHttpResponse response) {
					final HttpResponseStatus status = HttpResponseStatus.SWITCHING_PROTOCOLS;
					final HttpHeaders headers = response.headers();

					if (!response.status().equals(status)) {
						throw new WebSocketHandshakeException("Invalid handshake response getStatus: " + response.status());
					}

					CharSequence upgrade = headers.get(HttpHeaderNames.UPGRADE);
					if (!HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(upgrade)) {
						throw new WebSocketHandshakeException("Invalid handshake response upgrade: " + upgrade);
					}

					if (!headers.containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)) {
						throw new WebSocketHandshakeException("Invalid handshake response connection: "
								+ headers.get(HttpHeaderNames.CONNECTION));
					}
				}
			};
		}
		else {*/
			handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
					WebSocketVersion.V13,
					protocols,
					true,
					replaced.requestHeaders()
					        .remove(HttpHeaderNames.HOST));
//		}
		handshakerResult = channel.newPromise();

		String handlerName = channel.pipeline()
		                            .context(HttpRequestEncoder.class)
		                            .name();

		if (!handlerName.equals(NettyPipeline.HttpEncoder)) {
			channel.pipeline()
			       .remove(handlerName);
		}
		handshaker.handshake(channel)
		          .addListener(f -> {
			          markPersistent(false);
			          channel.read();
		          });
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	public String selectedSubprotocol() {
		return handshaker.actualSubprotocol();
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpResponse) {
			started = true;
			channel().pipeline()
			         .remove(HttpObjectAggregator.class);
			FullHttpResponse response = (FullHttpResponse) msg;

			setNettyResponse(response);

			if (checkResponseCode(response)) {

				try {
					if (!handshaker.isHandshakeComplete()) {
						handshaker.finishHandshake(channel(), response);
					}
				}
				catch (WebSocketHandshakeException wshe) {
					if (serverError) {
						onInboundError(wshe);
						return;
					}
				}

				parentContext().fireContextActive(this);
				handshakerResult.trySuccess();
			}
			return;
		}
		if (msg instanceof PingWebSocketFrame) {
			channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
			ctx.read();
			return;
		}
		if (msg instanceof CloseWebSocketFrame &&
				((CloseWebSocketFrame)msg).isFinalFragment()) {
			CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
			sendClose(new CloseWebSocketFrame(true,
					close.rsv(),
					close.content()));
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	public WebsocketInbound receiveWebsocket() {
		return this;
	}

	@Override
	protected void onInboundCancel() {
		sendClose(null);
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			sendClose(new CloseWebSocketFrame(1002, "Client internal error"));
		}
	}

	void sendClose(CloseWebSocketFrame frame) {
		if (frame != null && !frame.isFinalFragment()) {
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			ChannelFuture f = channel().writeAndFlush(
					frame == null ? new CloseWebSocketFrame() : frame);
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void accept(Throwable throwable) {
		if (throwable == null) {
			if (channel().isActive()) {
				sendClose(null);
			}
		}
		else {
			onOutboundError(throwable);
		}
	}

	static final AtomicIntegerFieldUpdater<HttpClientWSOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpClientWSOperations.class,
					"closeSent");
}
