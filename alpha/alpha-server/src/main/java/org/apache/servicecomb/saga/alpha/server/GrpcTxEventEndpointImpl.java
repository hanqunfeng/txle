/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package org.apache.servicecomb.saga.alpha.server;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.apache.servicecomb.saga.alpha.core.OmegaCallback;
import org.apache.servicecomb.saga.alpha.core.TxConsistentService;
import org.apache.servicecomb.saga.alpha.core.TxEvent;
import org.apache.servicecomb.saga.alpha.core.accidenthandling.AccidentHandleType;
import org.apache.servicecomb.saga.alpha.core.accidenthandling.AccidentHandling;
import org.apache.servicecomb.saga.alpha.core.accidenthandling.IAccidentHandlingService;
import org.apache.servicecomb.saga.alpha.core.configcenter.IConfigCenterService;
import org.apache.servicecomb.saga.alpha.core.kafka.KafkaMessage;
import org.apache.servicecomb.saga.common.ConfigCenterType;
import org.apache.servicecomb.saga.common.EventType;
import org.apache.servicecomb.saga.common.TxleConstants;
import org.apache.servicecomb.saga.pack.contract.grpc.*;
import org.apache.servicecomb.saga.pack.contract.grpc.TxEventServiceGrpc.TxEventServiceImplBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.lang.invoke.MethodHandles;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyMap;

class GrpcTxEventEndpointImpl extends TxEventServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final KryoPool pool = new KryoPool.Builder(() -> new Kryo()).softReferences().build();

    private final TxConsistentService txConsistentService;
    private final IConfigCenterService dbDegradationConfigService;

    private final Map<String, Map<String, OmegaCallback>> omegaCallbacks;

    private final IAccidentHandlingService accidentHandlingService;

    GrpcTxEventEndpointImpl(TxConsistentService txConsistentService,
                            Map<String, Map<String, OmegaCallback>> omegaCallbacks, IConfigCenterService dbDegradationConfigService, IAccidentHandlingService accidentHandlingService) {
        this.txConsistentService = txConsistentService;
        this.omegaCallbacks = omegaCallbacks;
        this.dbDegradationConfigService = dbDegradationConfigService;
        this.accidentHandlingService = accidentHandlingService;
    }

    @Override
    public void onConnected(GrpcServiceConfig request, StreamObserver<GrpcCompensateCommand> responseObserver) {
        omegaCallbacks
                .computeIfAbsent(request.getServiceName(), key -> new ConcurrentHashMap<>())
                .put(request.getInstanceId(), new GrpcOmegaCallback(responseObserver));
    }

    // TODO: 2018/1/5 connect is async and disconnect is sync, meaning callback may not be registered on disconnected
    @Override
    public void onDisconnected(GrpcServiceConfig request, StreamObserver<GrpcAck> responseObserver) {
        try {
            OmegaCallback callback = omegaCallbacks.getOrDefault(request.getServiceName(), emptyMap())
                    .remove(request.getInstanceId());

            if (callback != null) {
                callback.disconnect();
            }
        } catch (Exception e) {
            LOG.error("Encountered an exception when trying to disconnect.", e);
        } finally {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(GrpcAck.newBuilder().setAborted(false).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void onTxEvent(GrpcTxEvent message, StreamObserver<GrpcAck> responseObserver) {
        if (TxleConstants.SPECIAL_KEY.equals(message.getCategory())) {
            fetchLocalTxIdOfEndedGlobalTx(message, responseObserver);
            return;
        }

        // check global tx, compensation, auto-compensation. All of configs except fault-tolerant are enabled by default.
        if (!isEnabledTx(message, responseObserver)) {
            return;
        }

        handleSupportTxPause(message, responseObserver);
    }

    private boolean isEnabledTx(GrpcTxEvent message, StreamObserver<GrpcAck> responseObserver) {
        boolean result = true;
        try {
            if (EventType.SagaStartedEvent.name().equals(message.getType())) {
                result = dbDegradationConfigService.isEnabledConfig(message.getInstanceId(), message.getCategory(), ConfigCenterType.GlobalTx);
            } else if (EventType.TxStartedEvent.name().equals(message.getType())) {
                result = dbDegradationConfigService.isEnabledConfig(message.getInstanceId(), message.getCategory(), ConfigCenterType.GlobalTx);
                if (result) {// If the global transaction was not enabled, then two child transactions were regarded as disabled.
                    if (!TxleConstants.AUTO_COMPENSABLE_METHOD.equals(message.getCompensationMethod())) {
                        result = dbDegradationConfigService.isEnabledConfig(message.getInstanceId(), message.getCategory(), ConfigCenterType.Compensation);
                    } else if (TxleConstants.AUTO_COMPENSABLE_METHOD.equals(message.getCompensationMethod())) {
                        result = dbDegradationConfigService.isEnabledConfig(message.getInstanceId(), message.getCategory(), ConfigCenterType.AutoCompensation);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'isEnabledConfig'.", e);
        }

        if (!result) {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(GrpcAck.newBuilder().setAborted(false).setIsEnabledTx(result).build());
            responseObserver.onCompleted();
        }
        return result;
    }

    private void handleSupportTxPause(GrpcTxEvent message, StreamObserver<GrpcAck> responseObserver) {
        GrpcAck grpcAck = null;// To use temporary variables as much as possible for saving memory, not static variables.
        try {
            int result = 0;
            try {
                result = txConsistentService.handleSupportTxPause(new TxEvent(
                        message.getServiceName(),
                        message.getInstanceId(),
                        new Date(),
                        message.getGlobalTxId(),
                        message.getLocalTxId(),
                        message.getParentTxId().isEmpty() ? null : message.getParentTxId(),
                        message.getType(),
                        message.getCompensationMethod(),
                        message.getTimeout(),
                        message.getRetryMethod(),
                        message.getRetries(),
                        message.getCategory(),
                        message.getPayloads().toByteArray()
                ));
            } catch (Exception e) {
            }

            if (result > 0) {
                grpcAck = GrpcAck.newBuilder().setAborted(false).setIsEnabledTx(true).build();
            } else if (result < 0) {
                grpcAck = GrpcAck.newBuilder().setAborted(true).setIsEnabledTx(true).build();
            } else {
                grpcAck = GrpcAck.newBuilder().setAborted(false).setIsEnabledTx(true).setPaused(true).build();
            }
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'handleSupportTxPause'.", e);
        } finally {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(grpcAck);
            responseObserver.onCompleted();
        }
    }

    private void fetchLocalTxIdOfEndedGlobalTx(GrpcTxEvent message, StreamObserver<GrpcAck> responseObserver) {
        ByteString payloads = null;
        try {
            // Reasons for using the Kryo serialization tool are: 1.Do not change the TxEvent's structure. 2.To decrease data size for saving I/O. 3.Kryo has a high-performance computing power.
            // Disadvantages for using Kryo: Have to do serialize and deserialize, their processes will cost any performance, but just a little (very fast), and it is more cheap than I/O.
            Object[] payloadArr = deserialize(message.getPayloads().toByteArray());
            Set<String> localTxIdSet = new HashSet<>();
            for (int i = 0; i < payloadArr.length; i++) {
                localTxIdSet.add(payloadArr[i].toString());
            }
            Set<String> localTxIdOfEndedGlobalTx = txConsistentService.fetchLocalTxIdOfEndedGlobalTx(localTxIdSet);
            payloads = ByteString.copyFrom(serialize(localTxIdOfEndedGlobalTx.toArray()));
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'fetchLocalTxIdOfEndedGlobalTx'.", e);
        } finally {
            // message.toBuilder().setPayloads(payloads);// Could not set payloads to the original object.
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(GrpcAck.newBuilder().setAborted(false).setLocalTxIds(payloads).build());
            responseObserver.onCompleted();
        }
    }

    private byte[] serialize(Object[] objects) {
        Output output = new Output(4096, -1);
        Kryo kryo = pool.borrow();
        kryo.writeObjectOrNull(output, objects, Object[].class);
        pool.release(kryo);
        return output.toBytes();
    }

    private Object[] deserialize(byte[] message) {
        try {
            Input input = new Input(new ByteArrayInputStream(message));
            Kryo kryo = pool.borrow();
            Object[] objects = kryo.readObjectOrNull(input, Object[].class);
            pool.release(kryo);
            return objects;
        } catch (KryoException e) {
            throw new RuntimeException("Unable to deserialize message", e);
        }
    }

    @Override
    public void onMessage(GrpcMessage message, StreamObserver<GrpcMessageAck> responseObserver) {
        GrpcMessageAck MSG_ACK_TRUE = null;
        GrpcMessageAck MSG_ACK_FALSE = null;
        boolean result = false;
        try {
            MSG_ACK_TRUE = GrpcMessageAck.newBuilder().setStatus(true).build();
            MSG_ACK_FALSE = GrpcMessageAck.newBuilder().setStatus(false).build();
            result = txConsistentService.saveKafkaMessage(new KafkaMessage(message.getGlobaltxid(), message.getLocaltxid(), message.getDbdrivername(), message.getDburl(), message.getDbusername(), message.getTablename(), message.getOperation(), message.getIds()));
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'onMessage'.", e);
        } finally {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(result ? MSG_ACK_TRUE : MSG_ACK_FALSE);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void onAccident(GrpcAccident accident, StreamObserver<GrpcAccidentAck> responseObserver) {
        GrpcAccidentAck MSG_ACK_TRUE = null;
        GrpcAccidentAck MSG_ACK_FALSE = null;
        boolean result = false;
        try {
            MSG_ACK_TRUE = GrpcAccidentAck.newBuilder().setStatus(true).build();
            MSG_ACK_FALSE = GrpcAccidentAck.newBuilder().setStatus(false).build();
            AccidentHandling accidentHandling = new AccidentHandling(accident.getServicename(), accident.getInstanceid(), accident.getGlobaltxid(), accident.getLocaltxid(), AccidentHandleType.convertTypeFromValue(accident.getType()), accident.getBizinfo(), accident.getRemark());
            // To report accident to Accident Platform.
            result = accidentHandlingService.reportMsgToAccidentPlatform(accidentHandling.toJsonString());
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'onAccident'.", e);
        } finally {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(result ? MSG_ACK_TRUE : MSG_ACK_FALSE);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void onReadConfig(GrpcConfig config, StreamObserver<GrpcConfigAck> responseObserver) {
        boolean isEnabledConfig = false;
        try {
            String instanceId = null, category = null;
            if (config.getInstanceId() != null) instanceId = config.getInstanceId();
            if (config.getCategory() != null) category = config.getCategory();

            isEnabledConfig = dbDegradationConfigService.isEnabledConfig(instanceId, category, ConfigCenterType.convertTypeFromValue(config.getType()));
        } catch (Exception e) {
            LOG.error("Encountered an exception when executing method 'onReadConfig'.", e);
        } finally {
            // 保证下面两行代码被执行，若grpc服务端程序执行完成却没有执行下面两行代码，则将会报错误【io.grpc.StatusRuntimeException: UNKNOWN】 By Gannalyo
            responseObserver.onNext(GrpcConfigAck.newBuilder().setStatus(isEnabledConfig).build());
            responseObserver.onCompleted();
        }
    }
}
