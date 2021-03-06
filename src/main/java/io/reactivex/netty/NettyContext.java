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
package io.reactivex.netty;

import java.net.InetSocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.internal.functions.Functions;

/**
 * Hold contextual information for the underlying {@link Channel}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
@FunctionalInterface
public interface NettyContext extends Disposable {

	/**
	 * Add a {@link ChannelHandler} with {@link #addHandlerFirst} if of type of
	 * {@link io.netty.channel.ChannelOutboundHandler} otherwise with
	 * {@link #addHandlerLast}. Implementation may add more auto handling in particular
	 * HTTP based context will prepend an HttpContent body extractor.
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param handler handler instance
	 *
	 * @return this NettyContext

	 */
	default NettyContext addHandler(ChannelHandler handler){
		return addHandler(handler.getClass().getSimpleName(), handler);
	}
	
	/**
	 * Add a {@link ChannelHandler} with {@link #addHandlerFirst} if of type of
	 * {@link io.netty.channel.ChannelOutboundHandler} otherwise with
	 * {@link #addHandlerLast}. Implementation may add more auto handling in particular
	 * HTTP based context will prepend an HttpContent body extractor.
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addHandler(String name, ChannelHandler handler){
		if(handler instanceof ChannelOutboundHandler){
			addHandlerFirst(name, handler);
		}
		else {
			addHandlerLast(name, handler);
		}
		return this;
	}

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the rxnetty-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param handler handler instance
	 *
	 * @return this NettyContext

	 */
	default NettyContext addHandlerLast(ChannelHandler handler){
		return addHandlerLast(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the end of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just before the rxnetty-added handlers (like {@link NettyPipeline#ReactiveBridge}.
	 * If a handler with a similar name already exists, this operation is skipped.
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addHandlerLast(String name, ChannelHandler handler){
		RxNetty.addHandlerBeforeRxNettyEndHandlers(this, name, handler);
		return this;
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the rxnetty-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. 
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}.
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addHandlerFirst(ChannelHandler handler){
		return addHandlerFirst(handler.getClass().getSimpleName(), handler);
	}

	/**
	 * Add a {@link ChannelHandler} to the beginning of the "user" {@link io.netty.channel.ChannelPipeline},
	 * that is just after the rxnetty-added codecs. If a handler with a similar name already
	 * exists, this operation is skipped. 
	 * <p>
	 * {@code [ [rxnetty codecs], [<- user FIRST HANDLERS added here, user LAST HANDLERS added here ->], [rxnetty handlers] ]}
	 * <p>
	 * If effectively added, the handler will be safely removed when the channel is made
	 * inactive (pool release).
	 *
	 * @param name handler name
	 * @param handler handler instance
	 *
	 * @return this NettyContext
	 */
	default NettyContext addHandlerFirst(String name, ChannelHandler handler){
		RxNetty.addHandlerAfterRxNettyCodecs(this, name, handler);
		return this;
	}

	/**
	 * Return remote address if remote channel {@link NettyContext} otherwise local
	 * address if server selector channel.
	 *
	 * @return remote or local {@link InetSocketAddress}
	 */
	default InetSocketAddress address(){
		Channel c = channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel) {
			return ((ServerSocketChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	/**
	 * Return the underlying {@link Channel}. Direct interaction might be considered
	 * insecure if that affects the
	 * underlying IO processing such as read, write or close or state such as pipeline
	 * handler addition/removal.
	 *
	 * @return the underlying {@link Channel}
	 */
	Channel channel();

	@Override
	default void dispose() {
		channel().close();
	}

	@Override
	default boolean isDisposed() {
		return !channel().isActive();
	}

	/**
	 * Mark the underlying channel as persistent or not.
	 * If false, it will force a close on terminal protocol events thus defeating
	 * any pooling strategy
	 * if true (default), it will release on terminal protocol events thus
	 * keeping alive the channel if possible.
	 *
	 * @param persist the boolean flag to mark the {@link Channel} as fully disposable
	 * or reusable when a user handler has terminated
	 *
	 * @return this NettyContext
	 */
	default NettyContext  markPersistent(boolean persist){
		if(persist && !channel().hasAttr(RxNetty.PERSISTENT_CHANNEL)) {
			return this;
		}
		else {
			channel().attr(RxNetty.PERSISTENT_CHANNEL)
			         .set(persist);
		}
		return this;
	}

	/**
	 * Return an observing {@link Flowable} terminating with success when shutdown
	 * successfully
	 * or error.
	 *
	 * @return a {@link Flowable} terminating with success if shutdown successfully or error
	 */
	default Flowable<Void> onClose(){
		return FutureFlowable.from(channel().closeFuture());
	}

	/**
	 * Assign a {@link Runnable} to be invoked when the channel is closed.
	 *
	 * @param onClose the close event handler
	 *
	 * @return {@literal this}
	 */
	default NettyContext onClose(Action onClose){
		onClose().subscribe(Functions.emptyConsumer(), e -> onClose.run(), onClose);
		return this;
	}

	/**
	 * Remove a named handler if present and return this context
	 *
	 * @param name handler name
	 *
	 * @return this NettyContext
	 */
	default NettyContext removeHandler(String name) {
		RxNetty.removeHandler(channel(), name);
		return this;
	}

	/**
	 * Replace a named handler if present and return this context.
	 * If handler wasn't present, an {@link RuntimeException} will be thrown.
	 * <p>
	 *     Note: if the new handler is of different type, dependent handling like
	 *     the "extractor" introduced via HTTP-based {@link #addHandler} might not
	 *     expect/support the new messages type.
	 *
	 * @param name handler name
	 *
	 * @return this NettyContext
	 */
	default NettyContext replaceHandler(String name, ChannelHandler handler) {
		RxNetty.replaceHandler(channel(), name, handler);
		return this;
	}
}