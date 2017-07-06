package org.skywalking.apm.agent.core.remote;

import io.grpc.ManagedChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.skywalking.apm.agent.core.boot.BootService;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.conf.RemoteDownstreamConfig;
import org.skywalking.apm.agent.core.context.TracingContext;
import org.skywalking.apm.agent.core.context.TracingContextListener;
import org.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.skywalking.apm.network.proto.Application;
import org.skywalking.apm.network.proto.ApplicationInstance;
import org.skywalking.apm.network.proto.ApplicationInstanceHeartbeat;
import org.skywalking.apm.network.proto.ApplicationInstanceMapping;
import org.skywalking.apm.network.proto.ApplicationInstanceRecover;
import org.skywalking.apm.network.proto.ApplicationMapping;
import org.skywalking.apm.network.proto.ApplicationRegisterServiceGrpc;
import org.skywalking.apm.network.proto.InstanceDiscoveryServiceGrpc;

import static org.skywalking.apm.agent.core.remote.GRPCChannelStatus.CONNECTED;

/**
 * @author wusheng
 */
public class ApplicationRegisterClient implements BootService, GRPCChannelListener, Runnable, TracingContextListener {
    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
    private volatile ApplicationRegisterServiceGrpc.ApplicationRegisterServiceBlockingStub applicationRegisterServiceBlockingStub;
    private volatile InstanceDiscoveryServiceGrpc.InstanceDiscoveryServiceBlockingStub instanceDiscoveryServiceBlockingStub;
    private volatile ScheduledFuture<?> applicationRegisterFuture;
    private volatile boolean needRegisterRecover = false;
    private volatile long lastSegmentTime = -1;

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        if (CONNECTED.equals(status)) {
            ManagedChannel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getManagedChannel();
            if (RemoteDownstreamConfig.Agent.APPLICATION_ID == DictionaryUtil.nullValue()) {
                applicationRegisterServiceBlockingStub = ApplicationRegisterServiceGrpc.newBlockingStub(channel);
            } else {
                instanceDiscoveryServiceBlockingStub = InstanceDiscoveryServiceGrpc.newBlockingStub(channel);
                if (RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID != DictionaryUtil.nullValue()) {
                    needRegisterRecover = true;
                }
            }
        } else {
            applicationRegisterServiceBlockingStub = null;
        }
        this.status = status;
    }

    @Override
    public void beforeBoot() throws Throwable {
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
    }

    @Override
    public void boot() throws Throwable {
        applicationRegisterFuture = Executors
            .newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(this, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    public void afterBoot() throws Throwable {
        TracingContext.ListenerManager.add(this);
    }

    @Override
    public void run() {
        if (CONNECTED.equals(status)) {
            if (RemoteDownstreamConfig.Agent.APPLICATION_ID == DictionaryUtil.nullValue()) {
                if (applicationRegisterServiceBlockingStub != null) {
                    ApplicationMapping applicationMapping = applicationRegisterServiceBlockingStub.register(
                        Application.newBuilder().addApplicationCode(Config.Agent.APPLICATION_CODE).build());
                    if (applicationMapping.getApplicationCount() > 0) {
                        RemoteDownstreamConfig.Agent.APPLICATION_ID = applicationMapping.getApplication(0).getValue();
                    }
                }
            } else {
                if (RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID == DictionaryUtil.nullValue()) {
                    if (instanceDiscoveryServiceBlockingStub != null) {
                        ApplicationInstanceMapping instanceMapping = instanceDiscoveryServiceBlockingStub.register(ApplicationInstance.newBuilder()
                            .setApplicationId(RemoteDownstreamConfig.Agent.APPLICATION_ID)
                            .setRegisterTime(System.currentTimeMillis())
                            .build());
                        if (instanceMapping.getApplicationInstanceId() != DictionaryUtil.nullValue()) {
                            RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID
                                = instanceMapping.getApplicationInstanceId();
                        }
                    }
                } else {
                    if (needRegisterRecover) {
                        instanceDiscoveryServiceBlockingStub.registerRecover(ApplicationInstanceRecover.newBuilder()
                            .setApplicationId(RemoteDownstreamConfig.Agent.APPLICATION_ID)
                            .setApplicationInstanceId(RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID)
                            .setRegisterTime(System.currentTimeMillis())
                            .build());
                    } else {
                        if (lastSegmentTime - System.currentTimeMillis() > 60 * 1000) {
                            instanceDiscoveryServiceBlockingStub.heartbeat(ApplicationInstanceHeartbeat.newBuilder()
                                .setApplicationInstanceId(RemoteDownstreamConfig.Agent.APPLICATION_INSTANCE_ID)
                                .setHeartbeatTime(System.currentTimeMillis())
                                .build());
                        }
                    }
                }
            }
        }
    }

    @Override
    public void afterFinished(TraceSegment traceSegment) {
        lastSegmentTime = System.currentTimeMillis();
    }
}
