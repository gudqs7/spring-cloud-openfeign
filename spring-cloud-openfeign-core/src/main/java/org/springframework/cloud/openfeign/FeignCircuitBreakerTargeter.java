/*
 * Copyright 2013-2020 the original author or authors.
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

import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.util.StringUtils;

@SuppressWarnings("unchecked")
class FeignCircuitBreakerTargeter implements Targeter {

	private final CircuitBreakerFactory circuitBreakerFactory;

	FeignCircuitBreakerTargeter(CircuitBreakerFactory circuitBreakerFactory) {
		this.circuitBreakerFactory = circuitBreakerFactory;
	}

	@Override
	public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignContext context,
			Target.HardCodedTarget<T> target) {
		if (!(feign instanceof FeignCircuitBreaker.Builder)) {
			return feign.target(target);
		}
		// 获取 name
		FeignCircuitBreaker.Builder builder = (FeignCircuitBreaker.Builder) feign;
		String name = !StringUtils.hasText(factory.getContextId()) ? factory.getName() : factory.getContextId();
		// 判断两个 fallback
		Class<?> fallback = factory.getFallback();
		if (fallback != void.class) {
			// 处理 fallback 对象然后调用 build() 得到一个 Feign 对象, 再调用 newInstance 返回一个实例
			return targetWithFallback(name, context, target, builder, fallback);
		}
		Class<?> fallbackFactory = factory.getFallbackFactory();
		if (fallbackFactory != void.class) {
			// 类似的
			// 处理 fallbackFactoryClass 对象然后调用 build() 得到一个 Feign 对象, 再调用 newInstance 返回一个实例
			return targetWithFallbackFactory(name, context, target, builder, fallbackFactory);
		}
		// 无 fallback, 还是类似的
		// 为 builder 设置 断路器和 feignClientName(即serviceId) 属性值
		// 设置 fallback 对象为空, 然后调用 build() 得到一个 Feign 对象, 再调用 newInstance 返回一个实例
		return builder(name, builder).target(target);
	}

	private <T> T targetWithFallbackFactory(String feignClientName, FeignContext context,
			Target.HardCodedTarget<T> target, FeignCircuitBreaker.Builder builder, Class<?> fallbackFactoryClass) {
		FallbackFactory<? extends T> fallbackFactory = (FallbackFactory<? extends T>) getFromContext("fallbackFactory",
				feignClientName, context, fallbackFactoryClass, FallbackFactory.class);

		// 为 builder 设置 断路器和 feignClientName(即serviceId) 属性值
		// 处理 fallbackFactoryClass 对象然后调用 build() 得到一个 Feign 对象, 再调用 newInstance 返回一个实例
		return builder(feignClientName, builder).target(target, fallbackFactory);
	}

	private <T> T targetWithFallback(String feignClientName, FeignContext context, Target.HardCodedTarget<T> target,
			FeignCircuitBreaker.Builder builder, Class<?> fallback) {

		// 从容器中获取一个 bean 对象, 并确保其为 fallback 类的子类
		T fallbackInstance = getFromContext("fallback", feignClientName, context, fallback, target.type());

		// 为 builder 设置 断路器和 feignClientName(即serviceId) 属性值
		// 根据 fallback 设置 InvocationHandlerFactory 然后调用 build() 得到 Feign 对象, 再调用 newInstance 返回一个实例
		return builder(feignClientName, builder).target(target, fallbackInstance);
	}

	private <T> T getFromContext(String fallbackMechanism, String feignClientName, FeignContext context,
			Class<?> beanType, Class<T> targetType) {
		// 从容器中获取一个 bean 对象, 并确保其为 fallback 类的子类
		Object fallbackInstance = context.getInstance(feignClientName, beanType);
		if (fallbackInstance == null) {
			throw new IllegalStateException(
					String.format("No " + fallbackMechanism + " instance of type %s found for feign client %s",
							beanType, feignClientName));
		}

		if (!targetType.isAssignableFrom(beanType)) {
			throw new IllegalStateException(String.format("Incompatible " + fallbackMechanism
					+ " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
					beanType, targetType, feignClientName));
		}
		return (T) fallbackInstance;
	}

	private FeignCircuitBreaker.Builder builder(String feignClientName, FeignCircuitBreaker.Builder builder) {
		// 为 builder 设置 断路器和 feignClientName 属性
		return builder.circuitBreakerFactory(this.circuitBreakerFactory).feignClientName(feignClientName);
	}

}
