package com.beetle.framework.business.service.client;

import com.beetle.framework.AppProperties;
import com.beetle.framework.business.service.common.AsyncMethodCallback;
import com.beetle.framework.business.service.common.RpcConst;
import com.beetle.framework.business.service.common.RpcRequest;
import com.beetle.framework.business.service.common.RpcResponse;
import com.beetle.framework.log.AppLogger;
import org.jboss.netty.channel.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class RpcClientHandler extends SimpleChannelUpstreamHandler {
	private static final AppLogger logger = AppLogger
			.getInstance(RpcClientHandler.class);
	private volatile Channel channel;
	private final BlockingQueue<RpcResponse> resultQueue;
	private final int timeout;
	private volatile boolean invokeFlag;

	public RpcClientHandler() {
		super();
		resultQueue = new LinkedBlockingQueue<RpcResponse>();
		timeout = AppProperties.getAsInt("rpc_client_invoke_max_waitForTime",
				1000 * 60 * 10);
		this.invokeFlag = false;
	}

	public void asyncInvoke(final RpcRequest req) {
		channel.write(req);
	}

	public RpcResponse invoke(final RpcRequest req) {
		invokeFlag = true;
		try {
			return docall(req);
		} finally {
			invokeFlag = false;
		}
	}

	private RpcResponse docall(final RpcRequest req) {
		channel.write(req);
		RpcResponse res;
		// resultQueue.clear();??
		boolean interrupted = false;
		for (;;) {
			try {
				// res = resultQueue.take();
				res = resultQueue.poll(timeout, TimeUnit.MILLISECONDS);
				if (res == null) {
					res = new RpcResponse();
					res.setReturnFlag(RpcConst.ERR_CODE_CLIENT_INVOKE_TIMEOUT_EXCEPTION);
					res.setReturnMsg("client invoke timeout[" + timeout + "ms]");
					channel.close();
				}
				break;
			} catch (InterruptedException e) {
				channel.close();// 超时关闭链路，以防服务端执行完毕后通过此通过返回
				interrupted = true;
			} finally {
				resultQueue.clear();// 以防有垃圾
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		return res;
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
			throws Exception {
		this.channel = e.getChannel();
		super.channelOpen(ctx, e);
	}

	@Override
	public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e)
			throws Exception {
		if (e instanceof ChannelStateEvent
				&& ((ChannelStateEvent) e).getState() != ChannelState.INTEREST_OPS) {
			if (logger.isDebugEnabled()) {
				logger.debug(e.toString());
			}
		}
		super.handleUpstream(ctx, e);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
			throws Exception {
		if (logger.isDebugEnabled()) {
			logger.debug("message:{}", e.getMessage());
		}
		// logger.info(e.getMessage());
		if (e.getMessage() instanceof RpcResponse) {
			// ServiceClient.putResultIntoCache((RpcResponse) e.getMessage());
			RpcResponse rrs = (RpcResponse) e.getMessage();
			if (!rrs.isAsync()) {
				boolean f = resultQueue.offer(rrs);
				if (logger.isDebugEnabled()) {
					logger.debug("insert into queue state:{}", f);
				}
			} else {
				if (rrs.getResult() != null) {
					@SuppressWarnings("rawtypes")
					AsyncMethodCallback amcbObj = (AsyncMethodCallback) rrs
							.getResult();
					logger.debug("callback work:{}", amcbObj);
					if (rrs.getReturnFlag() < 0) {
						amcbObj.onError(rrs.getReturnFlag(),
								rrs.getReturnMsg(),
								(Throwable) rrs.getException());
					} else {
						amcbObj.onComplete(amcbObj.getResult());
					}
				}
			}
		} else {
			// ctx.getChannel().write(req);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
			throws Exception {
		logger.error("Unexpected exception from downstream.{}", e.getCause());
		try {
			if (resultQueue.isEmpty() && invokeFlag) {
				RpcResponse res = new RpcResponse();
				res.setReturnFlag(RpcConst.ERR_CODE_REMOTE_CALL_EXCEPTION);
				res.setReturnMsg(logger.getStackTraceInfo(e.getCause()));
				// res.setException(e.getCause());
				boolean f = resultQueue.offer(res);
				if (logger.isDebugEnabled()) {
					logger.debug(
							"insert exception response into queue state:{}", f);
				}
			}
		} finally {
			e.getChannel().close();
		}
	}

}
