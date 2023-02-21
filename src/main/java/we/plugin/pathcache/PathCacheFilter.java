package we.plugin.pathcache;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.fizzgate.plugin.FizzPluginFilter;
import com.fizzgate.plugin.FizzPluginFilterChain;
import com.fizzgate.util.NettyDataBufferUtils;
import com.fizzgate.util.ReactorUtils;

/**
 * 缓存插件
 * <p>
 * 根据请求地址与请求参数，缓存目标接口的返回结果。本插件默认采用机器内存作为缓存媒介，以求达到性能最优之目的。 本插件也可以使用 Redis
 * 作为缓存媒介，需要配置相应的参数。
 * <p>
 * 缓存的有效期为：{@link CacheEnv#cacheExpire}。
 * <p>
 * 缓存的内容长度为：{@link CacheEnv#cacheLength}。
 * <p>
 * 缓存的最大容量为：{@link CacheEnv#cacheSize}(本地缓存)。
 * <p>
 * 缓存的最小清理间隔为：{@link CacheEnv#cacheInterval}(本地缓存)。
 * 
 * <pre>
 * 本插件具备以下特性：
 *  1. 可配置本地缓存或Redis缓存，默认使用本地缓存。
 *  2. 可配置无效数据(非业务数据)，如：多个用户使用同一个缓存。
 *  3. 可配置缓存有效期，缓存内容长度，缓存容量与清理间隔(本地缓存)。
 *  4. 不处理非http与https的请求，不处理非GET与POST的请求。
 * </pre>
 * 
 * <pre>
 * 本地缓存清理机制：
 *  1. 缓存容量
 *   1.1 容量超过最大值({@link CacheEnv#cacheSize})，可以清理。
 *   1.2 容量未超过最大值({@link CacheEnv#cacheSize})，不清理。
 *  2. 与上一次清理缓存的间隔
 *   2.1 超过最小清理间隔({@link CacheEnv#cacheInterval})，可以清理。
 *   2.1 未超过最小清理间隔({@link CacheEnv#cacheInterval})，不清理。
 *  3. 清理缓存操作由保存缓存操作间接触发。
 *     当有新缓存需要保存时，且满足条件1.1与条件2.1，则触发清理缓存操作。
 *  4. 触发清理缓存操作后，所有有效期超过阈值({@link CacheEnv#cacheExpire})的缓存都将被清理。
 * </pre>
 */
@Component(PathCacheFilter.PATH_CACHE_ID)
public class PathCacheFilter implements FizzPluginFilter {
	public static Map<String, Object> staticConfig = null;

	/**
	 * 插件 ID
	 */
	public static final String PATH_CACHE_ID = "kmjbCachePlugin";

	/**
	 * 静态 Jackson 对象
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * 可重入互斥锁
	 */
	private static final ReentrantLock LOCK = new ReentrantLock();

	/**
	 * 配置参数哈希表
	 */
	private static final Map<String, CacheEnv> ENV_MAP = new ConcurrentHashMap<>();

	/**
	 * Redis 哈希表
	 * <p>
	 * 以键值对的方式，保存 StatefulRedisConnection 实例。
	 */
	private static final Map<String, StatefulRedisConnection<String, String>> REDIS_MAP = new ConcurrentHashMap<>();

	/**
	 * 本地缓存哈希表
	 * <p>
	 * 以键值对的方式，保存请求体(含路径与参数)与响应结果。
	 * <p>
	 * 缓存的最大容量为：{@link CacheEnv#cacheSize}。
	 * <p>
	 * 缓存的有效期为：{@link CacheEnv#cacheExpire}。
	 */
	private static final Map<String, CacheObject> CACHE_MAP = new ConcurrentHashMap<>();

	/**
	 * 上一次清理缓存的时间戳
	 */
	private static long lastClearTimestamp = 0L;

	/**
	 * 查找缓存对象
	 * <p>
	 * 若不存在缓存对象或缓存对象已过期，则返回 null
	 * 
	 * @param env      配置参数
	 * @param cacheKey 缓存的键
	 * @return 缓存对象
	 */
	private CacheObject doLoadCache(CacheEnv env, String cacheKey) {
		CacheObject cache = null;
		if (env != null && env.useRedis) {
			// 使用 Redis 作为缓存媒介
			StatefulRedisConnection<String, String> connection = REDIS_MAP.get(env.redisKey);
			// 使用同步方式获取缓存值
			String value = connection.sync().get(cacheKey);
			if (value != null) {
				try {
					cache = MAPPER.readValue(value, CacheObject.class);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} else {
			// 使用机器内存作为缓存媒介
			cache = CACHE_MAP.get(cacheKey);
			if (cache != null && System.currentTimeMillis() - cache.timestamp > env.cacheExpire * 1000L) {
				// 缓存已过期
				cache = null;
			}
		}
		return cache;
	}

	/**
	 * 保存缓存对象，并检查当前缓存哈希表容量
	 * <p>
	 * 若当前缓存媒介是机器内存哈希表容量超过阈值({@link CacheEnv#cacheSize})，则异步触发清理缓存操作。
	 * <p>
	 * 清理缓存操作只对机器内存媒介有效。
	 * 
	 * @param env      配置参数
	 * @param cacheKey 缓存的键
	 * @param cache    缓存对象
	 */
	private void doSaveCache(CacheEnv env, String cacheKey, CacheObject cache) {
		boolean doClear = false;
		if (env != null && env.useRedis) {
			// 使用 Redis 作为缓存媒介
			StatefulRedisConnection<String, String> connection = REDIS_MAP.get(env.redisKey);
			try {
				// 使用异步方式保存缓存值
				connection.async().set(cacheKey, MAPPER.writeValueAsString(cache), SetArgs.Builder.ex(env.cacheExpire));
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}
		} else {
			// 使用机器内存作为缓存媒介
			try {
				LOCK.lock();
				CACHE_MAP.put(cacheKey, cache);
				int size = CACHE_MAP.size();
				// 检查容量
				if (size > env.cacheSize && env.cacheSize > 0) {
					// 容量超过阈值，触发清理缓存操作
					doClear = true;
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				LOCK.unlock();
			}
		}
		if (doClear) {
			if (System.currentTimeMillis() - lastClearTimestamp > env.cacheInterval * 1000L) {
				// 符合清理间隔
				ExecutorService service = Executors.newSingleThreadExecutor();
				service.execute(new Runnable() {
					@Override
					public void run() {
						doClearExpiredCache(env);
					}
				});
				service.shutdown();
			} else {
				// 不符合清理间隔
			}
		}
	}

	/**
	 * 清理过期的缓存
	 * <p>
	 * 所有有效期超过阈值({@link CacheEnv#cacheExpire})的缓存都将被清理。
	 * 
	 * @param env 配置参数
	 */
	private void doClearExpiredCache(CacheEnv env) {
		try {
			LOCK.lock();
			long now = System.currentTimeMillis();
			if (now - lastClearTimestamp > env.cacheInterval * 1000L) {
				for (String key : CACHE_MAP.keySet()) {
					CacheObject cache = CACHE_MAP.get(key);
					if (now - cache.timestamp > env.cacheExpire * 1000L) {
						CACHE_MAP.remove(key);
					}
				}
				lastClearTimestamp = now;
			}
		} finally {
			LOCK.unlock();
		}
	}

	/**
	 * 初始化配置参数
	 * 
	 * @param requestId 请求编号
	 * @param config    配置参数
	 */
	private void initEnv(String requestId, Map<String, Object> config) {
		CacheEnv env = null;
		try {
			// 序列化与反序列化
			env = MAPPER.readValue(MAPPER.writeValueAsString(config), CacheEnv.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
		// 验证数据
		env = CacheEnv.verify(env);
		if (env != null && env.useRedis) {
			if (REDIS_MAP.get(env.redisKey) == null) {
				try {
					REDIS_MAP.put(env.redisKey, this.initRedisConnection(env));
				} catch (Exception e) {
					env.useRedis = false;
					e.printStackTrace();
				}
			}
		}
		ENV_MAP.put(requestId, env);
	}

	/**
	 * 初始化 StringRedisTemplate
	 * 
	 * @param env 配置参数
	 * @return StatefulRedisConnection
	 */
	private StatefulRedisConnection<String, String> initRedisConnection(CacheEnv env) {
		RedisURI uri = RedisURI.Builder.redis(env.redisHost).withPort(env.redisPort).withPassword(env.redisPass)
				.withDatabase(env.redisDatabase).withTimeout(Duration.ofSeconds(env.redisTimeout)).build();
		RedisClient client = RedisClient.create(uri);
		return client.connect();
	}

	/**
	 * 获取请求体
	 * <p>
	 * 只支持 http 与 https 请求，其他请求返回 null
	 * <p>
	 * 只支持 GET 与 POST 方法，其他方法返回 null
	 * 
	 * @param request    请求
	 * @param dataBuffer 字节缓冲区
	 * @return 请求体
	 */
	private String getRequestBody(CacheEnv env, ServerHttpRequest request, DataBuffer dataBuffer) {
		// 统一资源标识符
		URI uri = request.getURI();
		String schema = uri.getScheme();
		if (("http".equals(schema) == false && "https".equals(schema) == false)) {
			return null;
		}
		String methodName = request.getMethod().name();
		if ("GET".equals(methodName) == false && "POST".equals(methodName) == false) {
			return null;
		}
		String requestBody = null;
		try {
			Map<String, Object> map = null;
			if ("GET".equals(request.getMethod().name())) {
				// GET 方法获取入参
				map = new TreeMap<>();
				MultiValueMap<String, String> multiValueMap = request.getQueryParams();
				for (String key : multiValueMap.keySet()) {
					List<String> list = new LinkedList<>();
					list.addAll(multiValueMap.get(key));
					Collections.sort(list);
					map.put(key, list);
				}
			}
			if ("POST".equals(request.getMethod().name())) {
				// POST 方法获取入参
				requestBody = dataBuffer.toString(StandardCharsets.UTF_8);
				if (requestBody.length() > 0) {
					Class<?>[] array = { String.class, Object.class };
					// 反序列化
					map = MAPPER.readValue(requestBody,
							MAPPER.getTypeFactory().constructParametricType(new TreeMap<>().getClass(), array));
				} else {
					map = new TreeMap<>();
				}
			}
			// 无效数据(非业务数据)
			String invalidData = env.invalidData;
			if (invalidData == null) {
				invalidData = "";
			}
			String[] array = invalidData.split("\n");
			for (String data : array) {
				// 删除无效数据
				map.remove(data.trim());
			}
			// 序列化
			requestBody = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath()
					+ MAPPER.writeValueAsString(map);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}
		return requestBody;
	}

	/**
	 * 获取重新包装的响应
	 * <p>
	 * 使用响应修饰器重定义原始响应，覆盖 writeWith 方法。
	 * <p>
	 * 缓存内容长度控制({@link CacheEnv#cacheLength})，若符合阈值要求，则异步保存缓存内容。
	 * 
	 * @param response  原始响应
	 * @param requestId 请求编号
	 * @param cacheKey  缓存的键
	 * @return 重新包装的响应
	 */
	public ServerHttpResponse getWrappingResponse(ServerHttpResponse response, String requestId, String cacheKey) {
		// 响应修饰器，重定义响应
		return new ServerHttpResponseDecorator(response) {
			// 重定义写入方法，获取原始响应内容
			@Override
			public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
				if (body instanceof Mono || body instanceof Flux) {
					
					Flux<? extends DataBuffer> fluxBody = null;
					if (body instanceof Mono) {
						fluxBody = ((Mono<? extends DataBuffer>) body).flux();
					} else {
						fluxBody = (Flux<? extends DataBuffer>) body;
					}
					
					return super.writeWith(fluxBody.map(dataBuffer -> {
						// 以数据缓冲区的可读取字节数，创建缓冲数组，保存原始响应内容
						byte[] originalContent = new byte[dataBuffer.readableByteCount()];
						// 读取数据缓冲区
						dataBuffer.read(originalContent);
						// 释放数据缓冲区
						DataBufferUtils.release(dataBuffer);
						// 获取配置参数
						CacheEnv env = ENV_MAP.get(requestId);
						// 缓存内容长度控制
						if (env.cacheLength <= 0 || originalContent.length <= env.cacheLength) {
							// 暂存缓存内容
							CacheObject cache = new CacheObject().saveContent(response, originalContent);
							// 异步处理，保存缓存的键与缓存对象
							ExecutorService service = Executors.newSingleThreadExecutor();
							service.execute(new Runnable() {
								@Override
								public void run() {
									doSaveCache(env, cacheKey, cache);
								}
							});
							service.shutdown();
						}
						// 数据缓冲区工厂重新包装数据
						return response.bufferFactory().wrap(originalContent);
					}));
				}
				return super.writeWith(body);
			}
		};
	}

	/**
	 * 覆盖父类方法，实现缓存插件功能
	 * 
	 * @param exchange 交互协定
	 * @param config   配置项
	 * @return 反应流
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, Map<String, Object> config) {
		String requestId = UUID.randomUUID().toString();
		// 初始化配置参数
		this.initEnv(requestId, config);
		// 原始请求
		ServerHttpRequest originalRequest = exchange.getRequest();
		// 原始响应
		ServerHttpResponse originalResponse = exchange.getResponse();
		// 返回调用结果
		return originalRequest.getBody().defaultIfEmpty(NettyDataBufferUtils.EMPTY_DATA_BUFFER).single()
				.flatMap(dataBuffer -> {
					// 获取配置参数
					CacheEnv env = ENV_MAP.get(requestId);
					// 获取请求体
					String requestBody = this.getRequestBody(env, originalRequest, dataBuffer);
					// 无请求体不处理
					if (requestBody == null) {
						return Mono.empty();
					}
					// 查找缓存对象
					CacheObject cache = this.doLoadCache(env, requestBody);
					if (cache != null) {
						// 有缓存，则直接返回
						cache.resetServerHttpResponse(originalResponse);
						DataBuffer cacheBuffer = originalResponse.bufferFactory().wrap(cache.content);
						return originalResponse.writeWith(Mono.just(cacheBuffer));
					} else {
						// 获取重新包装的响应
						ServerHttpResponse wrapResponse = this.getWrappingResponse(originalResponse, requestId,
								requestBody);
						// 重定义交互协定
						ServerWebExchange newExchange = exchange.mutate().response(wrapResponse).build();
						// 调用目标地址开始
						return FizzPluginFilterChain.next(newExchange).ofType(Object.class)
								.defaultIfEmpty(ReactorUtils.NULL).flatMap(object -> {
									// 调用目标地址结束，删除配置参数
									ENV_MAP.remove(requestId);
									return Mono.empty();
								});
					}
				});
	}

	/**
	 * 缓存配置
	 */
	private static class CacheEnv {
		/**
		 * 是否使用 Redis 作为缓存媒介
		 * <p>
		 * 默认：否。默认使用机器内存作为缓存媒介。
		 */
		public boolean useRedis = false;

		/**
		 * Redis 服务器地址
		 */
		public String redisHost;

		/**
		 * Redis 服务器端口
		 */
		public int redisPort;

		/**
		 * Redis 服务器密码
		 */
		public String redisPass;

		/**
		 * Redis 服务器数据库
		 */
		public int redisDatabase;

		/**
		 * Redis 连接超时时长(单位：秒)
		 */
		public int redisTimeout;

		/**
		 * Redis 连接关键字
		 * <p>
		 * {@link #redisHost}:{@link #redisPort}@{@link #redisPass}#{@link #redisDatabase}
		 */
		public String redisKey;

		/**
		 * 缓存的有效期(单位：秒)
		 * <p>
		 * 默认有效期：300秒，即5分钟。取值范围：[10, 24*3600]。
		 */
		public int cacheExpire;

		/**
		 * 缓存内容长度(单位：字节)
		 * <p>
		 * 默认长度：4096，即4KB。
		 * <p>
		 * 若大于0，则当缓存内容长度超过阈值时，系统不会保存缓存内容。
		 * <p>
		 * 若小于等于0，则保存操作不受限制。
		 */
		public int cacheLength;

		/**
		 * 缓存的最大容量
		 * <p>
		 * 默认最大容量：3万。取值范围：[100, 十万]。此参数只对机器内存媒介有效。
		 */
		public int cacheSize;

		/**
		 * 缓存的最小清理间隔(单位：秒)
		 * <p>
		 * 默认清理间隔：60秒，即1分钟。取值范围：[60, 600]。此参数只对机器内存媒介有效。
		 */
		public int cacheInterval;

		/**
		 * 无效数据(非业务数据)
		 * <p>
		 * 如：token，userId，sessionId 等类似数据
		 */
		public String invalidData;

		/**
		 * 验证数据
		 */
		public static CacheEnv verify(CacheEnv env) {
			if (env == null) {
				env = new CacheEnv();
			}
			env.redisKey = env.redisHost + ":" + env.redisPort + "@" + env.redisPass + "#" + env.redisDatabase;
			if (env.cacheExpire < 60) {
				env.cacheExpire = 60;
			}
			if (env.cacheExpire > 24 * 3600) {
				env.cacheExpire = 24 * 3600;
			}
			if (env.cacheSize < 100) {
				env.cacheSize = 100;
			}
			if (env.cacheSize > 100000) {
				env.cacheSize = 100000;
			}
			if (env.cacheInterval < 60) {
				env.cacheInterval = 60;
			}
			if (env.cacheInterval > 600) {
				env.cacheInterval = 600;
			}
			return env;
		}
	}

	/**
	 * 缓存对象
	 */
	private static class CacheObject {
		/**
		 * 缓存时间戳
		 */
		public long timestamp;

		/**
		 * 缓存内容
		 */
		public byte[] content;

		/**
		 * Cookies
		 */
		public Map<String, List<ResponseCookie>> cookies;

		/**
		 * 请求头
		 */
		public Map<String, List<String>> headers;

		/**
		 * 状态码
		 */
		public int rawStatusCode;

		/**
		 * 暂存缓存内容
		 * 
		 * @param response 原始响应
		 * @param content  缓存内容
		 */
		public CacheObject saveContent(ServerHttpResponse response, byte[] content) {
			this.timestamp = System.currentTimeMillis();
			this.content = content;
			this.cookies = response.getCookies();
			this.headers = response.getHeaders();
			this.rawStatusCode = response.getRawStatusCode();
			return this;
		}

		/**
		 * 重置响应参数
		 * 
		 * @param response 响应
		 */
		public void resetServerHttpResponse(ServerHttpResponse response) {
			if (cookies != null) {
				MultiValueMap<String, ResponseCookie> responseCookies = response.getCookies();
				responseCookies.get("");
				responseCookies.clear();
				for (String key : cookies.keySet()) {
					responseCookies.put(key, cookies.get(key));
				}
			}
			if (headers != null) {
				HttpHeaders httpHeaders = response.getHeaders();
				httpHeaders.clear();
				for (String key : headers.keySet()) {
					httpHeaders.put(key, headers.get(key));
				}
			}
			response.setRawStatusCode(rawStatusCode);
		}
	}
}
