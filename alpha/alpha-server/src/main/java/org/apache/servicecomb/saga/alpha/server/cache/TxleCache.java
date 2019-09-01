/*
 * Copyright (c) 2018-2019 ActionTech.
 * based on code by ServiceComb Pack CopyrightHolder Copyright (C) 2018,
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.alpha.server.cache;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.agent.model.Service;
import org.apache.servicecomb.saga.alpha.core.cache.ITxleCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Gannalyo
 * @since 2019/8/29
 */
public class TxleCache implements ITxleCache {
    private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final ConcurrentHashMap<String, Boolean> configCache = new ConcurrentHashMap<>();
    // Store the identifies of global transaction when they have been suspended only. Do not use the 'configCache' variable so that free up memory for this variable in an even better fashion.
    private final ConcurrentHashMap<String, Boolean> txSuspendStatusCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> txAbortStatusCache = new ConcurrentHashMap<>();

    @Autowired
    private ConsulClient consulClient;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${spring.server.port:8090}")
    private int serverPort;

    public ConcurrentHashMap<String, Boolean> getConfigCache() {
        return configCache;
    }

    public ConcurrentHashMap<String, Boolean> getTxSuspendStatusCache() {
        return txSuspendStatusCache;
    }

    public ConcurrentHashMap<String, Boolean> getTxAbortStatusCache() {
        return txAbortStatusCache;
    }

    public void putForDistributedConfigCache(String key, Boolean value) {
        refreshDistributedCache(key, value.toString(), "/putConfigCache");
    }

    public void putForDistributedTxSuspendStatusCache(String key, Boolean value) {
        refreshDistributedCache(key, value.toString(), "/putTxSuspendStatusCache");
    }

    public void putForDistributedTxAbortStatusCache(String key, Boolean value) {
        refreshDistributedCache(key, value.toString(), "/putTxAbortStatusCache");
    }

    public void removeForDistributedConfigCache(String key) {
        refreshDistributedCache(key, "", "/removeConfigCache");
    }

    public void removeForDistributedTxSuspendStatusCache(String key) {
        refreshDistributedCache(key, "", "/removeTxSuspendStatusCache");
    }

    public void removeForDistributedTxAbortStatusCache(String key) {
        refreshDistributedCache(key, "", "/removeTxAbortStatusCache");
    }

    public void refreshDistributedCache(String cacheKey, String cacheValue, String restApi) {
        try {
            Response<Map<String, Service>> agentServices = consulClient.getAgentServices();
            if (agentServices != null) {
                Map<String, Service> serviceMap = agentServices.getValue();
                if (serviceMap != null && !serviceMap.isEmpty()) {
                    String currentHostPort = InetAddress.getLocalHost().getHostName() + ":" + serverPort;
                    Set<String> ipPortSet = new HashSet<>();
                    serviceMap.keySet().forEach(key -> {
                        Service service = serviceMap.get(key);
                        String ipPort = service.getAddress() + ":" + service.getPort();
                        if (!ipPortSet.contains(ipPort)) {
                            ipPortSet.add(ipPort);

                            if (currentHostPort.equals(ipPort)) {
                                callLocalFunction(restApi, cacheKey, cacheValue);
                            } else {
                                HttpEntity<String> entity = new HttpEntity<>(cacheKey + "," + cacheValue);
                                log.error("Calling http://" + ipPort + restApi);
                                restTemplate.exchange("http://" + ipPort + restApi, HttpMethod.POST, entity, Boolean.class);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.error("Failed to execute method 'refreshDistributedCache', restApi [{}], cacheKey [{}], cacheValue [{}].", restApi, cacheKey, cacheValue, e);
        }
    }

    private void callLocalFunction(String function, String cacheKey, String cacheValue) {
        if ("/putConfigCache".equals(function)) {
            putConfigCache(cacheKey + "," + cacheValue);
        } else if ("/putTxSuspendStatusCache".equals(function)) {
            putTxSuspendStatusCache(cacheKey + "," + cacheValue);
        } else if ("/putTxAbortStatusCache".equals(function)) {
            putTxAbortStatusCache(cacheKey + "," + cacheValue);
        } else if ("/removeConfigCache".equals(function)) {
            removeConfigCache(cacheKey + "," + cacheValue);
        } else if ("/removeTxSuspendStatusCache".equals(function)) {
            removeTxSuspendStatusCache(cacheKey + "," + cacheValue);
        } else if ("/removeTxAbortStatusCache".equals(function)) {
            removeTxAbortStatusCache(cacheKey + "," + cacheValue);
        }
    }

    private void putConfigCache(String cacheKV) {
        if (cacheKV != null) {
            String[] arrKV = cacheKV.split(",");
            configCache.put(arrKV[0], Boolean.valueOf(arrKV[1]));
        }
    }

    private void removeConfigCache(String cacheKV) {
        if (cacheKV != null) {
            configCache.remove(cacheKV.split(",")[0]);
            if (configCache.isEmpty()) {
                configCache.clear();
            }
        }
    }

    private void putTxSuspendStatusCache(String cacheKV) {
        if (cacheKV != null) {
            String[] arrKV = cacheKV.split(",");
            txSuspendStatusCache.put(arrKV[0], Boolean.valueOf(arrKV[1]));
        }
    }

    private void removeTxSuspendStatusCache(String cacheKV) {
        if (cacheKV != null) {
            txSuspendStatusCache.remove(cacheKV.split(",")[0]);
            if (txSuspendStatusCache.isEmpty()) {
                txSuspendStatusCache.clear();
            }
        }
    }

    private void putTxAbortStatusCache(String cacheKV) {
        if (cacheKV != null) {
            String[] arrKV = cacheKV.split(",");
            txAbortStatusCache.put(arrKV[0], Boolean.valueOf(arrKV[1]));
        }
    }

    private void removeTxAbortStatusCache(String cacheKV) {
        if (cacheKV != null) {
            txAbortStatusCache.remove(cacheKV.split(",")[0]);
            if (txAbortStatusCache.isEmpty()) {
                txAbortStatusCache.clear();
            }
        }
    }

}