package com.beetle.framework.business.common.tst.aop;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.beetle.framework.AppRuntimeException;
import com.beetle.framework.business.command.CommandException;
import com.beetle.framework.business.command.CommandExecutor;
import com.beetle.framework.business.command.CommandImp;
import com.beetle.framework.business.service.server.ServiceConfig;
import com.beetle.framework.business.service.server.ServiceConfig.ServiceDef;
import com.beetle.framework.business.service.server.ServiceConfig.ServiceDef.MethodEx;
import com.beetle.framework.resource.dic.aop.AopInterceptor;
import com.beetle.framework.util.cache.ConcurrentCache;
import com.beetle.framework.util.cache.ICache;

public class ServiceTransactionAopInterceptor extends AopInterceptor {
	private final static ICache KFC = new ConcurrentCache(1334);
	private final String interfacename;

	public ServiceTransactionAopInterceptor(final String interfacename) {
		this.interfacename = interfacename;
	}

	private static Object dealWithTransaction(Method method, Object[] args,
			Object imp) throws Throwable {
		Cmd cmd = new Cmd();
		cmd.setArgs(args);
		cmd.setMethod(method);
		cmd.setImpObj(imp);
		cmd = (Cmd) CommandExecutor.executeWithTransaction(cmd,
				CommandExecutor.COMMON_EXECUTE);
		if (cmd.getReturnFlag() < 0) {
			if (cmd.getPlus() != null)
				throw (Throwable) cmd.getPlus();
			throw new AppRuntimeException(cmd.getReturnFlag(),
					cmd.getReturnMsg());
		}
		return cmd.getResult();
	}

	private Object invoke(Object proxy, Method method, Object[] args)
			throws Throwable {
		ServiceDef sd = ServiceConfig.lookup(this.interfacename);
		if (sd == null) {
			throw new AppRuntimeException("not found service[" + interfacename
					+ "] define in ServiceConfig.xml");
		}
		Object imp = sd.getServiceImpInstanceRef();
		if (imp == null) {
			throw new AppRuntimeException("create service imp instance err!");
		}
		final MethodEx mex = sd.getMethodEx(method.getName(),
				method.getParameterTypes());
		String kfc = (String) KFC.get(Thread.currentThread());
		if (kfc == null) {
			try {
				KFC.put(Thread.currentThread(), "ysc@20090521");
				return doMethod(method, args, imp, mex);
			} finally {
				KFC.remove(Thread.currentThread());
			}
		} else {
			if (mex.isWithSynchronized()) {
				synchronized (mex) {
					return method.invoke(imp, args);
				}
			} else {
				return method.invoke(imp, args);
			}
		}
	}

	private Object doMethod(Method method, Object[] args, Object imp,
			final MethodEx mex) throws Throwable {
		try {
			if (mex.isWithTransaction()) {
				if (mex.isWithSynchronized()) {
					synchronized (mex) {
						return dealWithTransaction(method, args, imp);
					}
				} else {
					return dealWithTransaction(method, args, imp);
				}
			} else {
				if (mex.isWithSynchronized()) {
					synchronized (mex) {
						return method.invoke(imp, args);
					}
				} else {
					return method.invoke(imp, args);
				}
			}
		} catch (Throwable t) {
			if (t instanceof InvocationTargetException) {
				InvocationTargetException tt = (InvocationTargetException) t;
				throw tt.getTargetException();
			} else {
				throw t;
			}
		}
	}

	private static class Cmd extends CommandImp {
		private static final long serialVersionUID = 1L;
		private transient Method method;
		private Object[] args;
		private Object impObj;
		private Object result;

		public Cmd() {
			super();
		}

		public Object getResult() {
			return result;
		}

		public void setImpObj(Object impObj) {
			this.impObj = impObj;
		}

		public void setMethod(Method method) {
			this.method = method;
		}

		public void setArgs(Object[] args) {
			this.args = args;
		}

		@Override
		public void process() throws CommandException {
			try {
				this.result = method.invoke(impObj, args);
			} catch (Exception e) {
				CommandException ce = new CommandException(e);
				if (e instanceof java.lang.reflect.InvocationTargetException) {
					ce.setPlus(((java.lang.reflect.InvocationTargetException) e)
							.getTargetException());
				}
				throw ce;
			}
		}

	}

	@Override
	protected boolean interrupt() {
		return true;
	}

	@Override
	protected Object interruptResult(Object proxy, Method method, Object[] args)
			throws Throwable {
		return this.invoke(proxy, method, args);
	}

	@Override
	protected void before(Method method, Object[] args) throws Throwable {
	}

	@Override
	protected void after(Object returnValue, Method method, Object[] args)
			throws Throwable {
	}

}
