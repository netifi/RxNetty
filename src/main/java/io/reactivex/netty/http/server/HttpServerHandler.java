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

package io.reactivex.netty.http.server;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.reactivex.Flowable;
import io.reactivex.exceptions.MissingBackpressureException;
import io.reactivex.internal.queue.SpscLinkedArrayQueue;
import io.reactivex.netty.NettyPipeline;
import io.reactivex.netty.channel.ContextHandler;

import static io.netty.handler.codec.http.HttpUtil.*;

/**
 * Replace {@link io.netty.handler.codec.http.HttpServerKeepAliveHandler} with extra
 * handler management.
 */
final class HttpServerHandler extends ChannelDuplexHandler
		implements Runnable {

	static final String MULTIPART_PREFIX = "multipart";

	final ContextHandler<?> parentContext;

	boolean persistentConnection = true;
	// Track pending responses to support client pipelining: https://tools.ietf.org/html/rfc7230#section-6.3.2
	int pendingResponses;

	SpscLinkedArrayQueue<Object> pipelined;

	ChannelHandlerContext ctx;

	boolean overflow;
	boolean mustRecycleEncoder;

	HttpServerHandler(ContextHandler<?> parentContext) {
		this.parentContext = parentContext;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		this.ctx = ctx;
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		// read message and track if it was keepAlive
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;
			if (persistentConnection) {
				pendingResponses += 1;
				persistentConnection = isKeepAlive(request);
			}
			else {
				return;
			}
			if (overflow || pendingResponses > 1) {
				overflow = true;
				doPipeline(ctx, msg);
				return;
			}
			else {
				overflow = false;
				parentContext.createOperations(ctx.channel(), msg);

				if (!(msg instanceof FullHttpRequest)) {
					return;
				}
			}
		}
		else if (overflow) {
			doPipeline(ctx, msg);
			return;
		}
		else if (persistentConnection && msg instanceof LastHttpContent) {
			ctx.read();
		}
		ctx.fireChannelRead(msg);
	}

	void doPipeline(ChannelHandlerContext ctx, Object msg) {
		if (pipelined == null) {
			pipelined = new SpscLinkedArrayQueue<>(Flowable.bufferSize());
		}
		if (!pipelined.offer(msg)) {
			ctx.fireExceptionCaught(new MissingBackpressureException());
		}
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		// modify message on way out to add headers if needed
		if (msg instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) msg;
			trackResponse(response);
			// Assume the response writer knows if they can persist or not and sets isKeepAlive on the response
			if (!isKeepAlive(response) || !isSelfDefinedMessageLength(response)) {
				// No longer keep alive as the client can't tell when the message is done unless we close connection
				pendingResponses = 0;
				persistentConnection = false;
			}
			// Server might think it can keep connection alive, but we should fix response header if we know better
			if (!shouldKeepAlive()) {
				setKeepAlive(response, false);
			}
		}
		if (msg instanceof LastHttpContent) {
			if (!shouldKeepAlive()) {
				promise.addListener(ChannelFutureListener.CLOSE);
				ctx.write(msg, promise);
				return;
			}
			ctx.write(msg, promise);

			if(mustRecycleEncoder) {
				mustRecycleEncoder = false;
				pendingResponses -= 1;

				ctx.pipeline()
				   .replace(NettyPipeline.HttpEncoder, NettyPipeline.HttpEncoder, new HttpResponseEncoder());
			}

			if (pipelined != null && !pipelined.isEmpty()) {
				ctx.executor()
				   .execute(this);
			}
			else {
				ctx.read();
			}
			return;
		}
		ctx.write(msg, promise);
	}

	void trackResponse(HttpResponse response) {
		mustRecycleEncoder = !isInformational(response);
	}

	@Override
	public void run() {
		Object next;
		boolean nextRequest = false;
		while ((next = pipelined.peek()) != null) {
			if (next instanceof HttpRequest) {
				if (nextRequest || !persistentConnection) {
					return;
				}
				nextRequest = true;
				parentContext.createOperations(ctx.channel(), next);

				if (!(next instanceof FullHttpRequest)) {
					pipelined.poll();
					continue;
				}
			}
			ctx.fireChannelRead(pipelined.poll());
		}
		overflow = false;
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		discard();
	}

	final void discard() {
		if(pipelined != null && !pipelined.isEmpty()){
			Object o;
			while((o = pipelined.poll()) != null){
				ReferenceCountUtil.release(o);
			}

		}
	}

	boolean shouldKeepAlive() {
		return pendingResponses != 0 || persistentConnection;
	}

	/**
	 * Keep-alive only works if the client can detect when the message has ended without
	 * relying on the connection being closed.
	 * <p>
	 * <ul> <li>See <a href="https://tools.ietf.org/html/rfc7230#section-6.3"/></li>
	 * <li>See <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3"/></li> </ul>
	 *
	 * @param response The HttpResponse to check
	 *
	 * @return true if the response has a self defined message length.
	 */
	static boolean isSelfDefinedMessageLength(HttpResponse response) {
		return isContentLengthSet(response) || isTransferEncodingChunked(response) || isMultipart(
				response) || isInformational(response);
	}

	static boolean isInformational(HttpResponse response) {
		return response.status()
		               .codeClass() == HttpStatusClass.INFORMATIONAL;
	}

	static boolean isMultipart(HttpResponse response) {
		String contentType = response.headers()
		                             .get(HttpHeaderNames.CONTENT_TYPE);
		return contentType != null && contentType.regionMatches(true,
				0,
				MULTIPART_PREFIX,
				0,
				MULTIPART_PREFIX.length());
	}
}
