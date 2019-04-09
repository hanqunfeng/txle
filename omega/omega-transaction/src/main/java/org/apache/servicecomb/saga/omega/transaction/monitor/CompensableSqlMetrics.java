package org.apache.servicecomb.saga.omega.transaction.monitor;

import java.util.List;

/**
 * Refer to the website "https://github.com/VitaNuova/eclipselinkexporter/blob/master/src/main/java/prometheus/exporter/EclipseLinkStatisticsCollector.java".
 *
 * @author Gannalyo
 * @date 20181024
 */
public class CompensableSqlMetrics extends CommonPrometheusMetrics {

    public CompensableSqlMetrics(String promMetricsPort) {
        super(promMetricsPort);
    }

    // Refer to the website "https://github.com/VitaNuova/eclipselinkexporter/blob/master/src/main/java/prometheus/exporter/EclipseLinkStatisticsCollector.java".
    @Override
    public List<MetricFamilySamples> collect() {
        return null;
    }

    public void startMarkSQLDurationAndCount(String sql, boolean isBizSql) {
        if (!isMonitorSql) return;
        super.startMarkSQLDurationAndCount(sql, isBizSql);
    }

    public void endMarkSQLDuration() {
        super.endMarkSQLDuration();
    }

}
