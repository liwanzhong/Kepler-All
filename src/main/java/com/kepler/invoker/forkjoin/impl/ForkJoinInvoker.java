package com.kepler.invoker.forkjoin.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;

import com.kepler.KeplerRoutingException;
import com.kepler.KeplerValidateException;
import com.kepler.annotation.ForkJoin;
import com.kepler.config.Profile;
import com.kepler.config.PropertiesUtils;
import com.kepler.header.Headers;
import com.kepler.host.Host;
import com.kepler.id.IDGenerators;
import com.kepler.invoker.Invoker;
import com.kepler.invoker.forkjoin.Forker;
import com.kepler.invoker.forkjoin.Joiner;
import com.kepler.mock.Mocker;
import com.kepler.mock.MockerContext;
import com.kepler.org.apache.commons.collections.map.MultiKeyMap;
import com.kepler.protocol.Request;
import com.kepler.protocol.RequestFactory;
import com.kepler.service.Imported;
import com.kepler.service.Quiet;
import com.kepler.service.Service;

/**
 * ForkJoin仅消耗1个Threshold限制, 强依赖Headers.ENABLED
 * 
 * @author kim 2016年1月15日
 */
public class ForkJoinInvoker implements Imported, Invoker {

	/**
	 * 需要Fork Request的Tag集合
	 */
	public static final String TAGS_KEY = ForkJoinInvoker.class.getName().toLowerCase() + ".tags";

	private static final String TAGS_DEF = PropertiesUtils.get(ForkJoinInvoker.TAGS_KEY, Host.TAG_VAL);

	private static final boolean ACTIVED = PropertiesUtils.get(ForkJoinInvoker.class.getName().toLowerCase() + ".actived", false);

	private static final Log LOGGER = LogFactory.getLog(ForkJoinInvoker.class);

	private final MultiKeyMap forkers = new MultiKeyMap();

	private final ThreadPoolExecutor threads;

	private final IDGenerators generators;

	private final RequestFactory request;

	private final MockerContext mocker;

	private final Invoker delegate;

	private final Profile profile;

	private final Quiet quiet;

	private final Forks forks;

	private final Joins joins;

	public ForkJoinInvoker(Forks forks, Joins joins, Quiet quiet, Invoker delegate, Profile profile, IDGenerators generator, RequestFactory request, MockerContext mocker, ThreadPoolExecutor threads) {
		super();
		this.generators = generator;
		this.delegate = delegate;
		this.threads = threads;
		this.profile = profile;
		this.request = request;
		this.mocker = mocker;
		this.quiet = quiet;
		this.forks = forks;
		this.joins = joins;
	}

	@Override
	public boolean actived() {
		return ForkJoinInvoker.ACTIVED && Headers.ENABLED;
	}

	@Override
	public void subscribe(Service service) throws Exception {
		try {
			for (Method method : Service.clazz(service).getMethods()) {
				// 注册Fork方法
				ForkJoin forkjoin = method.getAnnotation(ForkJoin.class);
				if (forkjoin != null) {
					Assert.state(!method.getReturnType().equals(void.class), "Method must not return void ... ");
					this.forkers.put(service, method.getName(), forkjoin);
				}
			}
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			ForkJoinInvoker.LOGGER.info("Class not found: " + service);
		}
	}

	@Override
	public Object invoke(Request request) throws Throwable {
		// 是否开启了Fork, 否则进入下一个Invoker
		return this.forkers.containsKey(request.service(), request.method()) ? this.fork(request) : Invoker.EMPTY;
	}

	/**
	 * Mock
	 * 
	 * @param request
	 * @param exception
	 * @return
	 */
	private Object mock(Request request, KeplerRoutingException exception) {
		Mocker mocker = this.mocker.get(request.service());
		if (mocker != null) {
			return mocker.mock(request);
		} else {
			throw exception;
		}
	}

	/**
	 * 获取Forker策略
	 * 
	 * @param name
	 * @param request
	 * @return
	 */
	private Forker fork(String name, Request request) {
		Forker forker = this.forks.get(name);
		if (forker == null) {
			throw new KeplerValidateException("None Forker for " + request);
		}
		return forker;
	}

	/**
	 * 获取Joiner策略
	 * 
	 * @param name
	 * @param request
	 * @return
	 */
	private Joiner join(String name, Request request) {
		Joiner joiner = this.joins.get(name);
		if (joiner == null) {
			throw new KeplerValidateException("None Joiner for " + request);
		}
		return joiner;
	}

	/**
	 * 获取请求标签组
	 * 
	 * @param request
	 * @return
	 */
	private String[] tag(Request request) {
		String tags = PropertiesUtils.profile(this.profile.profile(request.service()), ForkJoinInvoker.TAGS_KEY, ForkJoinInvoker.TAGS_DEF);
		return tags.split(";");
	}

	/**
	 * 分发请求
	 * 
	 * @param request
	 * @return
	 */
	private Object fork(Request request) throws Throwable {
		try {
			ForkJoin fk = ForkJoin.class.cast(this.forkers.get(request.service(), request.method()));
			Joiner joiner = this.join(fk.join(), request);
			Forker forker = this.fork(fk.fork(), request);
			String[] tags = this.tag(request);
			// 发起请求, 等待合并结果
			return new ForkJoinProcessor(joiner).fork(request, forker, tags).value();
		} catch (KeplerRoutingException exception) {
			// 失败则尝试Mock
			return this.mock(request, exception);
		}
	}

	/**
	 * FK主流程
	 * 
	 * @author KimShen
	 *
	 */
	private class ForkJoinProcessor {

		/**
		 * Fork任务列表
		 */
		private final List<ForkerRunnable> forkers = new ArrayList<ForkerRunnable>();

		private final Joiner joiner;

		/**
		 * 异常(唯一)
		 */
		private Throwable throwable;

		/**
		 * 计数
		 */
		private int running;

		/**
		 * @param joiner
		 */
		private ForkJoinProcessor(Joiner joiner) {
			super();
			this.joiner = joiner;
		}

		/**
		 * 释放资源
		 */
		private void release() {
			for (ForkerRunnable fork : this.forkers) {
				fork.release();
			}
		}

		/**
		 * 归并结果
		 * 
		 * @return
		 */
		private Object join() {
			Object current = null;
			for (ForkerRunnable forker : this.forkers) {
				current = this.joiner.join(current, forker.response);
			}
			return current;
		}

		/**
		 * 校验, 如果存在异常则抛出
		 * 
		 * @return
		 * @throws Throwable
		 */
		private ForkJoinProcessor valid() throws Throwable {
			if (this.throwable != null) {
				throw throwable;
			}
			return this;
		}

		public ForkJoinProcessor fork(Request request, Forker forker, String[] tags) {
			// 向所有Tag集群发送请求(可能出现None Service Exception)
			for (int index = 0; index < tags.length; this.running++, index++) {
				// 拆分参数
				Object[] args = forker.fork(request.args(), tags[index], this.running);
				// 生成ACK
				byte[] ack = ForkJoinInvoker.this.generators.get(request.service(), request.method()).generate();
				// 构造请求
				Request actual = ForkJoinInvoker.this.request.request(request, ack, args).put(Host.TAG_KEY, tags[index]);
				ForkerRunnable runnable = new ForkerRunnable(this, actual);
				this.forkers.add(runnable);
				ForkJoinInvoker.this.threads.execute(runnable);
			}
			return this;
		}

		/**
		 * 递减计数器, 并触发唤醒
		 */
		public void decrease() {
			synchronized (this) {
				// 计数器归0或者存在异常则唤醒
				if ((--this.running) == 0 || this.throwable != null) {
					this.notifyAll();
				}
			}
		}

		// 异常回调
		public void throwable(Request request, Throwable throwable) {
			synchronized (this) {
				this.throwable = (this.throwable != null ? this.throwable : (ForkJoinInvoker.this.quiet.quiet(request, throwable.getClass()) ? null : throwable));
			}
		}

		public Object value() throws Throwable {
			try {
				// 监听器同步(ForkJoinProcessor)
				synchronized (this) {
					// 等待监听器, 直到计数为0或出现异常或中断
					while (this.running > 0 && this.throwable == null) {
						this.wait();
					}
				}
				return this.valid().join();
			} finally {
				// 释放资源
				this.release();
			}
		}
	}

	private class ForkerRunnable implements Runnable {

		private final ForkJoinProcessor forker;

		private final Request request;

		/**
		 * 返回结果
		 */
		volatile private Object response;

		/**
		 * 执行线程
		 */
		volatile private Thread thread;

		/**
		 * @param forker
		 * @param request
		 */
		private ForkerRunnable(ForkJoinProcessor forker, Request request) {
			super();
			this.request = request;
			this.forker = forker;
		}

		/**
		 * 尝试中断底层未完成AckFuture
		 * 
		 * @return
		 */
		public ForkerRunnable release() {
			if (this.thread != null) {
				this.thread.interrupt();
			}
			return this;
		}

		@Override
		public void run() {
			try {
				// 绑定线程
				this.thread = Thread.currentThread();
				this.response = ForkJoinInvoker.this.delegate.invoke(this.request);
			} catch (Throwable e) {
				// 异常回调
				this.forker.throwable(this.request, e);
			} finally {
				// 解绑线程
				this.thread = null;
				// 递减计数
				this.forker.decrease();
			}
		}
	}
}
