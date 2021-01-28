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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 * @author Gang Li
 * @author Michal Domagala
 * @author Marcin Grzejszczak
 */
class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	private ResourceLoader resourceLoader;

	private Environment environment;

	FeignClientsRegistrar() {
	}

	static void validateFallback(final Class clazz) {
		// 校验 fallback 是否为实现类, 而非接口
		Assert.isTrue(!clazz.isInterface(), "Fallback class must implement the interface annotated by @FeignClient");
	}

	static void validateFallbackFactory(final Class clazz) {
		// 校验 fallbackFactory 是否为实现类, 而非接口
		Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
				+ "of fallback classes that implement the interface annotated by @FeignClient");
	}

	static String getName(String name) {
		// 检测 name 这个 serviceId 是否合法..
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	static String getUrl(String url) {
		// 若 url 不为空则校验 url
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	static String getPath(String path) {
		// 若 path 不为空, 进行一些修正
		if (StringUtils.hasText(path)) {
			path = path.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// @Import 注解导入的类, 会调用这个接口, 传入 registry 对象, 可用于往容器注册 BeanDefinition.
		// 为 Feign 子容器添加一些 BeanDefinition(从 EnableFeignClients 的 defaultConfiguration 中取)
		registerDefaultConfiguration(metadata, registry);

		// 扫描每个 @FeignClient, 使用 FactoryBean 的方式生成 proxy 对象注入到容器中
		registerFeignClients(metadata, registry);
	}

	private void registerDefaultConfiguration(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 1.获取注解配置数据, 判断是否配置了 defaultConfiguration
		// 2.是则添加一个包含 name(default.开头的算全局)和 defaultConfiguration 数据的 FeignClientSpecification 类到容器中.

		Map<String, Object> defaultAttrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		// 获取注解配置数据, 判断是否配置了 defaultConfiguration,
		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			}
			else {
				name = "default." + metadata.getClassName();
			}
			// 添加一个包含 name(default.开头的算全局)和 defaultConfiguration 数据的 FeignClientSpecification 类到容器中.
			registerClientConfiguration(registry, name, defaultAttrs.get("defaultConfiguration"));
		}
	}

	public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// 1.获取 EnableFeignClients 注解的 clients 属性值.
		// 2.若设置了 clients , 则仅加载 clients 下的 class... (把这些 class 理解为 @FeignClient 注解的类, 但既然是配置不少扫描, 所以其实配置的类是不需要加注解的)
		// 3.若 clients 未设置, 则根据其他属性扫描
		//    新建一个 类扫描器(从 classpath 下扫描)
		//    借用容器的 resourceLoader, 达成一致呗
		//    设置仅包含策略, 即仅带有 @FeignClient 注解的类
		//    设定扫描范围, 为 EnableFeignClients 中的配置或默认为配置了 @EnableFeignClients 的类所在包
		//    设定扫描范围, 为 EnableFeignClients 中的配置或默认为配置了 @EnableFeignClients 的类所在包
		// 4.遍历得到的类, 挨个处理
		//    注解必须放接口上
		//    为 feign 容器设置一个名字
		//    为每个 Feign 容器注入自己的配置, 和处理 defaultConfiguration 用处一样, 但 name 不同, 因此只属于这个 feign 容器(即一般以 Service接口为单位)
		//

		// 获取 EnableFeignClients 注解的 clients 属性值.
		LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
		Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName());
		final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");
		// 若 clients 未设置, 则根据其他属性扫描
		if (clients == null || clients.length == 0) {
			// 新建一个 类扫描器(从 classpath 下扫描)
			ClassPathScanningCandidateComponentProvider scanner = getScanner();
			// 借用容器的 resourceLoader, 达成一致呗
			scanner.setResourceLoader(this.resourceLoader);
			// 设置仅包含策略, 即仅带有 @FeignClient 注解的类
			scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
			// 设定扫描范围, 为 EnableFeignClients 中的配置或默认为配置了 @EnableFeignClients 的类所在包
			Set<String> basePackages = getBasePackages(metadata);
			// 遍历扫描包范围, 扫描带有 @FeignClient 注解的类并暂存起来
			for (String basePackage : basePackages) {
				candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
			}
		}
		// 若设置了 clients , 则仅加载 clients 下的 class... (把这些 class 理解为 @FeignClient 注解的类, 但既然是配置不少扫描, 所以其实配置的类是不需要加注解的)
		else {
			for (Class<?> clazz : clients) {
				candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz));
			}
		}

		// 遍历得到的类, 挨个处理
		for (BeanDefinition candidateComponent : candidateComponents) {
			if (candidateComponent instanceof AnnotatedBeanDefinition) {
				// verify annotated class is an interface
				AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
				AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
				// 注解必须放接口上
				Assert.isTrue(annotationMetadata.isInterface(), "@FeignClient can only be specified on an interface");

				Map<String, Object> attributes = annotationMetadata
						.getAnnotationAttributes(FeignClient.class.getCanonicalName());

				// 为 feign 容器设置名字了
				String name = getClientName(attributes);
				// 为每个 Feign 容器注入自己的配置, 和处理 defaultConfiguration 用处一样, 但 name 不同, 因此只属于这个 feign 容器(即一般以 Service接口为单位)
				registerClientConfiguration(registry, name, attributes.get("configuration"));

				// 新建一个 FactoryBean, 并设置一些属性, 注入到容器中
				registerFeignClient(registry, annotationMetadata, attributes);
			}
		}
	}

	private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata,
			Map<String, Object> attributes) {
		// 1.获取并解析 contextId(为空则和 name 相同) 和 name 属性
		// 2.新建一个 FactoryBean, 并设置一些属性
		// 3.注入到容器中


		String className = annotationMetadata.getClassName();
		Class clazz = ClassUtils.resolveClassName(className, null);
		ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory
				? (ConfigurableBeanFactory) registry : null;
		// 获取并解析 contextId(为空则和 name 相同) 和 name 属性
		String contextId = getContextId(beanFactory, attributes);
		String name = getName(attributes);
		// 新建一个 FactoryBean, 并设置一些属性
		FeignClientFactoryBean factoryBean = new FeignClientFactoryBean();
		factoryBean.setBeanFactory(beanFactory);
		factoryBean.setName(name);
		factoryBean.setContextId(contextId);
		factoryBean.setType(clazz);
		BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(clazz, () -> {
			// 继续为 factoryBean 设置属性
			// serviceId 的前缀
			factoryBean.setUrl(getUrl(beanFactory, attributes));
			// RequestMapping 的前缀
			factoryBean.setPath(getPath(beanFactory, attributes));
			// 遇到404是否返回编码, 设置为否则会抛异常
			factoryBean.setDecode404(Boolean.parseBoolean(String.valueOf(attributes.get("decode404"))));
			// 设置 fallback 降级.
			Object fallback = attributes.get("fallback");
			if (fallback != null) {
				factoryBean.setFallback(fallback instanceof Class ? (Class<?>) fallback
						: ClassUtils.resolveClassName(fallback.toString(), null));
			}
			// 设置 fallbackFactory
			Object fallbackFactory = attributes.get("fallbackFactory");
			if (fallbackFactory != null) {
				factoryBean.setFallbackFactory(fallbackFactory instanceof Class ? (Class<?>) fallbackFactory
						: ClassUtils.resolveClassName(fallbackFactory.toString(), null));
			}
			// 使用 JDK 动态代理返回对象
			return factoryBean.getObject();
		});
		// 设置自动注入方式为: 根据类型
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
		// 设置为懒加载
		definition.setLazyInit(true);
		// 校验 fallback 相关配置是否为接口
		validate(attributes);

		String alias = contextId + "FeignClient";
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
		beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, className);
		beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean);

		// has a default, won't be null
		boolean primary = (Boolean) attributes.get("primary");

		beanDefinition.setPrimary(primary);

		String qualifier = getQualifier(attributes);
		if (StringUtils.hasText(qualifier)) {
			alias = qualifier;
		}

		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, new String[] { alias });
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		// FIXME annotation.getAliasedString("name", FeignClient.class, null);
		// 校验 fallback 是否为实现类, 而非接口
		validateFallback(annotation.getClass("fallback"));
		// 校验 fallbackFactory 是否为实现类, 而非接口
		validateFallbackFactory(annotation.getClass("fallbackFactory"));
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		return getName(null, attributes);
	}

	String getName(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		// 挨个从 serviceId(已过时的代码啊) > name > value 从配置中获取 serviceId, 然后调用容器的接口解析 serviceId(若为表达式)
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		// 使用 BeanFactory 或 environment 对象的接口尝试解析表达式, 得到返回值后返回
		name = resolve(beanFactory, name);
		// 检测 contextId 这个 serviceId 是否合法..
		return getName(name);
	}

	private String getContextId(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		// 获取 contextId, 先未配置, 则调用 getName() 作为 contextId
		// 若设置了, 则调用 resolve 根据配置的 contextId 获取解析后的值
		// 最后检测 contextId 这个 serviceId 是否合法..
		String contextId = (String) attributes.get("contextId");
		if (!StringUtils.hasText(contextId)) {
			return getName(attributes);
		}

		// 使用 BeanFactory 或 environment 对象的接口尝试解析表达式, 得到返回值后返回.
		contextId = resolve(beanFactory, contextId);
		// 检测 contextId 这个 serviceId 是否合法..
		return getName(contextId);
	}

	private String resolve(ConfigurableBeanFactory beanFactory, String value) {
		// 若 BeanFactory 为空, 则根据 environment 中的配置解析 value 这个表达式(可能是)
		// 若不为空, 则使用 BeanFactory 的接口解析表达式.
		if (StringUtils.hasText(value)) {
			if (beanFactory == null) {
				return this.environment.resolvePlaceholders(value);
			}
			BeanExpressionResolver resolver = beanFactory.getBeanExpressionResolver();
			String resolved = beanFactory.resolveEmbeddedValue(value);
			if (resolver == null) {
				return resolved;
			}
			return String.valueOf(resolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null)));
		}
		return value;
	}

	private String getUrl(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		// 解析并检查 url, 若为空则啥都不干.
		String url = resolve(beanFactory, (String) attributes.get("url"));
		return getUrl(url);
	}

	private String getPath(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
		// 解析并修正 path, 若为空则啥都不干.
		String path = resolve(beanFactory, (String) attributes.get("path"));
		return getPath(path);
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				boolean isCandidate = false;
				if (beanDefinition.getMetadata().isIndependent()) {
					if (!beanDefinition.getMetadata().isAnnotation()) {
						isCandidate = true;
					}
				}
				return isCandidate;
			}
		};
	}

	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		// 试图从 EnableFeignClients 的配置中找用户配置的扫描范围
		// 若找不到, 则令扫描范围为配置了这个注解的类所在包(啊, 这就是为啥在 main 方法类写这个吗? 一般 main 方法所在类就是在顶级包下)
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}

	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String getClientName(Map<String, Object> client) {
		// 挨个从 contextId > value > name > serviceId(FeignClient 有这个配置?)获取值并返回
		// 若最终一个也无, 则报错
		if (client == null) {
			return null;
		}
		String value = (String) client.get("contextId");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("value");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException(
				"Either 'name' or 'value' must be provided in @" + FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name, Object configuration) {
		// 添加一个 FeignClientSpecification 类到容器中
		// 目的是将 EnableFeignClients 的 defaultConfiguration 属性里配置的 class 加入到每个 FeignContext 容器.
		// 使得其后 feign 从容器中取对象时会优先获取 parent 容器(即这里)内的相应的类(如 Encoder, Decoder).
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		registry.registerBeanDefinition(name + "." + FeignClientSpecification.class.getSimpleName(),
				builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
