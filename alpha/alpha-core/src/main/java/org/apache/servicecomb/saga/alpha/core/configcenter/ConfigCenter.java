/*
 * Copyright (c) 2018-2019 ActionTech.
 * License: http://www.apache.org/licenses/LICENSE-2.0 Apache License 2.0 or higher.
 */

package org.apache.servicecomb.saga.alpha.core.configcenter;

import com.google.gson.GsonBuilder;
import org.apache.servicecomb.saga.common.ConfigCenterType;
import org.apache.servicecomb.saga.common.TxleConstants;
import org.springframework.util.MultiValueMap;

import javax.persistence.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * ConfigCenter
 * 1.No config and all of configs except fault-tolerant are enabled by default.
 * 2.Global configs and client's configs can be set in the future.
 * 3.The client's priority is higher than global priority.
 *
 * @author Gannalyo
 * @since 2018/12/3
 */
@Entity
@Table(name = "Config")
public class ConfigCenter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String servicename;
    private String instanceid;
    private String category;
    // 0-normal, 1-historical, 2-dumped
    private int status;
    // 0-do not provide ability, 1-provide ability  全局配置参数，非全局同步且只读：即全局配置是否提供当前配置对应功能，以“是否可手动补偿为例”，如果不提供则全局和非全局均不支持手动补偿功能，如果提供，则优先客户端再全局默认值
    private int ability;
    // 1-globaltx, 2-compensation, 3-autocompensation, 4-bizinfotokafka, 5-txmonitor, 6-alert, 7-schedule, 8-globaltxfaulttolerant, 9-compensationfaulttolerant, 10-autocompensationfaulttolerant, 11-pauseglobaltx,
    // 50-accidentreport, 51-sqlmonitor  if values are less than 50, then configs for server, otherwise configs for client.
    private int type;
    private String value;
    private String remark;
    private Date updatetime;

    private ConfigCenter() {
    }

    public ConfigCenter(String servicename, String instanceid, String category, ConfigCenterStatus status, int ability, ConfigCenterType type, String value, String remark) {
        this.servicename = servicename;
        this.instanceid = instanceid;
        this.category = category;
        this.status = status.toInteger();
        this.ability = ability;
        this.type = type.toInteger();
        this.value = value;
        this.remark = remark;
        this.updatetime = new Date(System.currentTimeMillis());
    }

    public ConfigCenter(MultiValueMap<String, String> mvm) {
        this.servicename = mvm.getFirst("servicename");
        this.instanceid = mvm.getFirst("instanceid");
        this.category = mvm.getFirst("category");
        this.status = toInteger(mvm.getFirst("status"), ConfigCenterStatus.Normal.toInteger());
        this.status = toInteger(mvm.getFirst("ability"), TxleConstants.YES);
        // To return a default value for 'status', but throw exception for 'type'. Because, the default value of the former is suitable and the later's is not good.
        this.type = Integer.parseInt(mvm.getFirst("type"));
        this.value = mvm.getFirst("value");
        this.remark = mvm.getFirst("remark");
        this.updatetime = new Date(System.currentTimeMillis());
    }

    private int toInteger(String value, int defaultValue) {
        try {
            return Integer.valueOf(value, ConfigCenterStatus.Normal.toInteger());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getServicename() {
        return servicename;
    }

    public void setServicename(String servicename) {
        this.servicename = servicename;
    }

    public String getInstanceid() {
        return instanceid;
    }

    public void setInstanceid(String instanceid) {
        this.instanceid = instanceid;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getAbility() {
        return ability;
    }

    public void setAbility(int ability) {
        this.ability = ability;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Date getUpdatetime() {
        return updatetime;
    }

    public void setUpdatetime(Date updatetime) {
        this.updatetime = updatetime;
    }

    public void toJsonString() {
        new GsonBuilder().create().toJson(this);
    }

    public Map<String, Object> toMap(String typeDesc, String statusDesc, String abilityDesc) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> map = new HashMap<>();
        map.put("id", this.id);
        map.put("servicename", this.servicename);
        map.put("instanceid", this.instanceid);
        map.put("category", this.category);
        map.put("type_db", this.type);
        map.put("type", typeDesc);
        map.put("status_db", this.status);
        map.put("status", statusDesc);
        map.put("ability_db", this.ability);
        map.put("ability", abilityDesc);
        map.put("value", this.value);
        map.put("remark", this.remark);
        map.put("updatetime", sdf.format(this.updatetime));
        return map;
    }
}
