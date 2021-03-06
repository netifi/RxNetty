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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.reactivex.Maybe;
import io.reactivex.MaybeObserver;

/**
 * A decorating {@link Maybe} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public final class ByteBufMaybe extends Maybe<ByteBuf> {

	/**
	 * a {@link ByteBuffer} inbound {@link Maybe}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Maybe}
	 */
	public final Maybe<ByteBuffer> asByteBuffer() {
		return map(ByteBuf::nioBuffer);
	}

	/**
	 * a {@literal byte[]} inbound {@link Maybe}
	 *
	 * @return a {@literal byte[]} inbound {@link Maybe}
	 */
	public final Maybe<byte[]> asByteArray() {
		return map(bb -> {
			byte[] bytes = new byte[bb.readableBytes()];
			bb.readBytes(bytes);
			return bytes;
		});
	}

	/**
	 * a {@link String} inbound {@link Maybe}
	 *
	 * @return a {@link String} inbound {@link Maybe}
	 */
	public final Maybe<String> asString() {
		return asString(Charset.defaultCharset());
	}

	/**
	 * a {@link String} inbound {@link Maybe}
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Maybe}
	 */
	public final Maybe<String> asString(Charset charset) {
		return map(s -> s.toString(charset));
	}

	/**
	 * Convert to an {@link InputStream} inbound {@link Maybe}
	 *
	 * @return a {@link InputStream} inbound {@link Maybe}
	 */
	public Maybe<InputStream> asInputStream() {
		return map(ReleasingInputStream::new);
	}

	/**
	 * Disable auto memory release on each signal published in order to prevent premature
	 * recycling when buffers are accumulated downstream (async).
	 *
	 * @return {@link ByteBufMaybe} of retained {@link ByteBuf}
	 */
	public ByteBufMaybe retain() {
		return new ByteBufMaybe(doOnSuccess(ByteBuf::retain));
	}

	@Override
	protected void subscribeActual(MaybeObserver<? super ByteBuf> actual) {
		source.subscribe(actual);
	}

	final Maybe<ByteBuf> source;

	protected ByteBufMaybe(Maybe<?> source) {
		this.source = source.map(ByteBufFlowable.bytebufExtractor);
	}

	static final class ReleasingInputStream extends ByteBufInputStream {

		final ByteBuf bb;

		volatile int closed;

		static final AtomicIntegerFieldUpdater<ReleasingInputStream> CLOSE =
		AtomicIntegerFieldUpdater.newUpdater(ReleasingInputStream.class, "closed");

		ReleasingInputStream(ByteBuf bb) {
			super(bb.retain());
			this.bb = bb;
		}

		@Override
		public void close() throws IOException {
			if(CLOSE.compareAndSet(this, 0, 1)) {
				super.close();
				bb.release();
			}
		}
	}
}
