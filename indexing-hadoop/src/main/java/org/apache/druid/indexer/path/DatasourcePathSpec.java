/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexer.path;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.druid.indexer.HadoopDruidIndexerConfig;
import org.apache.druid.indexer.hadoop.DatasourceIngestionSpec;
import org.apache.druid.indexer.hadoop.DatasourceInputFormat;
import org.apache.druid.indexer.hadoop.WindowedDataSegment;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatasourcePathSpec implements PathSpec
{
  private static final Logger logger = new Logger(DatasourcePathSpec.class);

  public static final String TYPE = "dataSource";

  private final DatasourceIngestionSpec ingestionSpec;
  private final long maxSplitSize;
  private final List<WindowedDataSegment> segments;

  /*
  Note: User would set this flag when they are doing pure re-indexing and would like to have a different
  set of aggregators than the ones used during original indexing.
  Default behavior is to expect same aggregators as used in original data ingestion job to support delta-ingestion
  use case.
   */
  private final boolean useNewAggs;
  private static final String USE_NEW_AGGS_KEY = "useNewAggs";

  @JsonCreator
  public DatasourcePathSpec(
      @JsonProperty("segments") List<WindowedDataSegment> segments,
      @JsonProperty("ingestionSpec") DatasourceIngestionSpec spec,
      @JsonProperty("maxSplitSize") Long maxSplitSize,
      @JsonProperty(USE_NEW_AGGS_KEY) boolean useNewAggs
  )
  {
    this.segments = segments;
    this.ingestionSpec = Preconditions.checkNotNull(spec, "null ingestionSpec");

    if (maxSplitSize == null) {
      this.maxSplitSize = 0;
    } else {
      this.maxSplitSize = maxSplitSize.longValue();
    }

    this.useNewAggs = useNewAggs;
  }

  @JsonProperty
  public boolean isUseNewAggs()
  {
    return useNewAggs;
  }

  @JsonProperty
  public List<WindowedDataSegment> getSegments()
  {
    return segments;
  }

  @JsonProperty
  public DatasourceIngestionSpec getIngestionSpec()
  {
    return ingestionSpec;
  }

  @JsonProperty
  public long getMaxSplitSize()
  {
    return maxSplitSize;
  }

  @Override
  public Job addInputPaths(HadoopDruidIndexerConfig config, Job job) throws IOException
  {
    if (segments == null || segments.isEmpty()) {
      if (ingestionSpec.isIgnoreWhenNoSegments()) {
        logger.warn("No segments found for ingestionSpec [%s]", ingestionSpec);
        return job;
      } else {
        throw new ISE("No segments found for ingestion spec [%s]", ingestionSpec);
      }
    }

    logger.info(
        "Found total [%d] segments for [%s]  in interval [%s]",
        segments.size(),
        ingestionSpec.getDataSource(),
        ingestionSpec.getIntervals()
    );

    DatasourceIngestionSpec updatedIngestionSpec = ingestionSpec;
    if (updatedIngestionSpec.getDimensions() == null) {
      List<String> dims;
      if (config.getParser().getParseSpec().getDimensionsSpec().hasFixedDimensions()) {
        dims = config.getParser().getParseSpec().getDimensionsSpec().getDimensionNames();
      } else {
        Set<String> dimSet = Sets.newHashSet(
            Iterables.concat(
                Iterables.transform(
                    segments,
                    new Function<WindowedDataSegment, Iterable<String>>()
                    {
                      @Override
                      public Iterable<String> apply(WindowedDataSegment dataSegment)
                      {
                        return dataSegment.getSegment().getDimensions();
                      }
                    }
                )
            )
        );
        dims = Lists.newArrayList(
            Sets.difference(
                dimSet,
                config.getParser()
                      .getParseSpec()
                      .getDimensionsSpec()
                      .getDimensionExclusions()
            )
        );
      }
      updatedIngestionSpec = updatedIngestionSpec.withDimensions(dims);
    }

    if (updatedIngestionSpec.getMetrics() == null) {
      Set<String> metrics = new HashSet<>();
      final AggregatorFactory[] cols = config.getSchema().getDataSchema().getAggregators();
      if (cols != null) {
        if (useNewAggs) {
          for (AggregatorFactory col : cols) {
            metrics.addAll(col.requiredFields());
          }
        } else {
          for (AggregatorFactory col : cols) {
            metrics.add(col.getName());
          }
        }

      }
      updatedIngestionSpec = updatedIngestionSpec.withMetrics(Lists.newArrayList(metrics));
    }

    updatedIngestionSpec = updatedIngestionSpec.withQueryGranularity(config.getGranularitySpec().getQueryGranularity());

    // propagate in the transformSpec from the overall job config
    updatedIngestionSpec = updatedIngestionSpec.withTransformSpec(
        config.getSchema().getDataSchema().getTransformSpec()
    );

    DatasourceInputFormat.addDataSource(job.getConfiguration(), updatedIngestionSpec, segments, maxSplitSize);
    MultipleInputs.addInputPath(job, new Path("/dummy/tobe/ignored"), DatasourceInputFormat.class);
    return job;
  }

  public static boolean checkIfReindexingAndIsUseAggEnabled(Map<String, Object> configuredPathSpec)
  {
    return TYPE.equals(configuredPathSpec.get("type")) && Boolean.parseBoolean(configuredPathSpec.getOrDefault(
        USE_NEW_AGGS_KEY,
        false
    ).toString());
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    DatasourcePathSpec that = (DatasourcePathSpec) o;

    if (maxSplitSize != that.maxSplitSize) {
      return false;
    }
    if (!ingestionSpec.equals(that.ingestionSpec)) {
      return false;
    }
    return !(segments != null ? !segments.equals(that.segments) : that.segments != null);

  }

  @Override
  public int hashCode()
  {
    int result = ingestionSpec.hashCode();
    result = 31 * result + (int) (maxSplitSize ^ (maxSplitSize >>> 32));
    result = 31 * result + (segments != null ? segments.hashCode() : 0);
    return result;
  }
}
