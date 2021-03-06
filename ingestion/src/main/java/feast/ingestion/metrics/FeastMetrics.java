/*
 * Copyright 2018 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package feast.ingestion.metrics;

import feast.types.FeatureRowProto;
import lombok.AllArgsConstructor;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.joda.time.DateTime;
import feast.ingestion.model.Features;
import feast.ingestion.model.Values;
import feast.ingestion.util.DateUtil;
import feast.types.FeatureProto.Feature;
import feast.types.FeatureRowExtendedProto.FeatureRowExtended;
import feast.types.FeatureRowProto.FeatureRow;
import feast.types.GranularityProto.Granularity;
import feast.types.GranularityProto.Granularity.Enum;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class FeastMetrics {
  public static final String METRICS_ENTITY_NAME = "metrics";
  public static final String METRICS_FEATURE_JOB_ID = "metrics.hour.job_id";
  public static final String METRICS_FEATURE_NAMESPACE = "metrics.hour.namespace";
  public static final String METRICS_FEATURE_STEP = "metrics.hour.step";
  public static final String METRICS_FEATURE_NAME = "metrics.hour.name";
  public static final String METRICS_FEATURE_ATTEMPTED = "metrics.hour.attempted";
  public static final String FEAST_NAMESPACE = "feast";

  private FeastMetrics() {}

  private static void inc(String name) {
    Metrics.counter(FeastMetrics.FEAST_NAMESPACE, name).inc();
  }

  public static void update(String name, long value) {
    Metrics.distribution(FeastMetrics.FEAST_NAMESPACE, name).update(value);
  }

  public static void inc(FeatureRow row, String suffix) {
    inc("row:" + suffix);
    inc(String.format("entity:%s:%s", row.getEntityName(), suffix));
    for (Feature feature : row.getFeaturesList()) {
      inc(String.format("feature:%s:%s", feature.getId(), suffix));
    }
  }

  public static IncrRowExtendedFunc incrDoFn(String suffix) {
    return new IncrRowExtendedFunc(suffix);
  }

  public static CalculateLagMetricFunc lagUpdateDoFn() {
    return new CalculateLagMetricFunc();
  }

  /**
   * Create a feature row from Metrics.
   * The granularity is unrelated to the metrics themselves, and simply indicates the
   * granularity at which to store and overwrite the metrics downstream.
   *
   * @param counter
   * @param jobName
   * @param granularity
   * @return
   */
  public static FeatureRow makeFeatureRow(
      MetricResult<Long> counter, String jobName, Granularity.Enum granularity) {
    String jobId = jobName;
    String namespace = counter.getName().getNamespace();
    String step = counter.getStep();
    String name = counter.getName().getName();
    Long attempted = counter.getAttempted();

    String entityId = String.join(":", new String[] {jobId, namespace, step, name});

    return FeatureRow.newBuilder()
        .setEntityName(METRICS_ENTITY_NAME)
        .setEntityKey(entityId)
        .setGranularity(granularity)
        .setEventTimestamp(DateUtil.toTimestamp(DateTime.now()))
        .addFeatures(Features.of(METRICS_FEATURE_JOB_ID, Values.ofString(jobId)))
        .addFeatures(Features.of(METRICS_FEATURE_NAMESPACE, Values.ofString(namespace)))
        .addFeatures(Features.of(METRICS_FEATURE_STEP, Values.ofString(step)))
        .addFeatures(Features.of(METRICS_FEATURE_NAME, Values.ofString(name)))
        .addFeatures(Features.of(METRICS_FEATURE_ATTEMPTED, Values.ofInt64(attempted)))
        .build();
  }

  @AllArgsConstructor
  public static class IncrRowExtendedFunc extends DoFn<FeatureRowExtended, FeatureRowExtended> {
    private String suffix;

    @ProcessElement
    public void processElement(
        @Element FeatureRowExtended element, OutputReceiver<FeatureRowExtended> out) {
      inc(element.getRow(), suffix);
      out.output(element);
    }
  }

  @AllArgsConstructor
  public static class CalculateLagMetricFunc extends DoFn<FeatureRowExtended, FeatureRowExtended> {
    @ProcessElement
    public void processElement(@Element FeatureRowExtended element, OutputReceiver<FeatureRowExtended> out) {
      FeatureRowProto.FeatureRow row = element.getRow();
      com.google.protobuf.Timestamp eventTimestamp = row.getEventTimestamp();
      Instant now = Instant.now();
      com.google.protobuf.Timestamp roundedCurrentTimestamp =
              DateUtil.roundToGranularity(
                      com.google.protobuf.Timestamp.newBuilder()
                              .setSeconds(now.getEpochSecond())
                              .setNanos(now.getNano())
                              .build(),
                      row.getGranularity());
      long lagSeconds = roundedCurrentTimestamp.getSeconds() - eventTimestamp.getSeconds();
      FeastMetrics.update("row:lag", lagSeconds);
      out.output(element);
    }
  }
}
