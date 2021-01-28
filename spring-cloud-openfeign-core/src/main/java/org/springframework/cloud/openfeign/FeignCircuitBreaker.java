/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign;

import feign.Feign;
import feign.Target;

import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

/**
 * Allows Feign interfaces to work with {@link CircuitBreaker}.
 *
 * @author Marcin Grzejszczak
 * @since 3.0.0
 */
public final class FeignCircuitBreaker {

	private FeignCircuitBreaker() {
		throw new IllegalStateException("Don't instantiate a utility class");
	}

	/**
	 * @return builder for Feign CircuitBreaker integration
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for Feign CircuitBreaker integration.
	 */
	public static final class Builder extends Feign.Builder {

		private CircuitBreakerFactory circuitBreakerFactory;

		private String feignClientName;

		Builder circuitBreakerFactory(CircuitBreakerFactory circuitBreakerFactory) {
			this.circuitBreakerFactory = circuitBreakerFactory;
			return this;
		}

		Builder feignClientName(String feignClientName) {
			this.feignClientName = feignClientName;
			return this;
		}

		public <T> T target(Target<T> target, T fallback) {
			// 设置 InvocationHandlerFactory 然后调用 build() 得到 Feign 对象, 再调用 newInstance 返回一个实例
			return build(fallback != null ? new FallbackFactory.Default<T>(fallback) : null).newInstance(target);
		}

		public <T> T target(Target<T> target, FallbackFactory<? extends T> fallbackFactory) {
			return build(fallbackFactory).newInstance(target);
		}

		@Override
		public <T> T target(Target<T> target) {
			// newInstance 方法概述:
			//    先获取一些信息
			//    接着遍历接口的每个方法, 为其添加 methodToHandler 中, 顺带处理 java8 接口的 default 方法
			//    调用 JDK 的 Proxy api, 生产一个代理对象, 其 InvocationHandler 其实就是 FeignCircuitBreakerInvocationHandler
			//    遍历 DefaultMethodHandler 集合, 将其绑定到 proxy 对象, 处理 default 方法.
			//
			return build(null).newInstance(target);
		}

		public Feign build(final FallbackFactory<?> nullableFallbackFactory) {
			// 为 断路器设置 InvocationHandlerFactory 属性, 此类的 create 返回一个 FeignCircuitBreakerInvocationHandler
			//      此处 dispatch 是 method handler map
			// 然后调用 build() 创建一个 Feign 对象
			super.invocationHandlerFactory((target, dispatch) -> new FeignCircuitBreakerInvocationHandler(
					circuitBreakerFactory, feignClientName, target, dispatch, nullableFallbackFactory));
			return super.build();
		}

	}

}
