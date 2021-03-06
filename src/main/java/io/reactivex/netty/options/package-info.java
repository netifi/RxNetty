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

/**
 * Provider or Netty reusable component given various RxNetty parameters, for
 * clients
 * and servers.
 * This will produce {@link io.netty.bootstrap.Bootstrap} and
 * {@link io.netty.bootstrap.ServerBootstrap} along with some specific helper like
 * {@link io.netty.channel.pool.ChannelPool}.
 */
package io.reactivex.netty.options;