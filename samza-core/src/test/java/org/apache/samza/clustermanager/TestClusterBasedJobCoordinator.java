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

package org.apache.samza.clustermanager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.apache.samza.Partition;
import org.apache.samza.SamzaException;
import org.apache.samza.config.Config;
import org.apache.samza.config.JobConfig;
import org.apache.samza.config.MapConfig;
import org.apache.samza.coordinator.StreamPartitionCountMonitor;
import org.apache.samza.coordinator.stream.CoordinatorStreamSystemProducer;
import org.apache.samza.coordinator.stream.MockCoordinatorStreamSystemFactory;
import org.apache.samza.metrics.MetricsRegistry;
import org.apache.samza.startpoint.StartpointManager;
import org.apache.samza.system.MockSystemFactory;
import org.apache.samza.system.SystemStream;
import org.apache.samza.system.SystemStreamPartition;
import org.apache.samza.util.CoordinatorStreamUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.exceptions.base.MockitoException;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ClusterBasedJobCoordinator}
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(CoordinatorStreamUtil.class)
public class TestClusterBasedJobCoordinator {

  Map<String, String> configMap;

  @Before
  public void setUp() {
    configMap = new HashMap<>();
    configMap.put("job.name", "test-job");
    configMap.put("job.coordinator.system", "kafka");
    configMap.put("task.inputs", "kafka.topic1");
    configMap.put("systems.kafka.samza.factory", "org.apache.samza.system.MockSystemFactory");
    configMap.put("samza.cluster-manager.factory", "org.apache.samza.clustermanager.MockClusterResourceManagerFactory");
    configMap.put("job.coordinator.monitor-partition-change.frequency.ms", "1");

    MockSystemFactory.MSG_QUEUES.put(new SystemStreamPartition("kafka", "topic1", new Partition(0)), new ArrayList<>());
    MockSystemFactory.MSG_QUEUES.put(new SystemStreamPartition("kafka", "__samza_coordinator_test-job_1", new Partition(0)), new ArrayList<>());
    MockCoordinatorStreamSystemFactory.enableMockConsumerCache();
    PowerMockito.mockStatic(CoordinatorStreamUtil.class);
    when(CoordinatorStreamUtil.getCoordinatorSystemFactory(anyObject())).thenReturn(
        new MockCoordinatorStreamSystemFactory());
    when(CoordinatorStreamUtil.getCoordinatorSystemStream(anyObject())).thenReturn(new SystemStream("kafka", "test"));
    when(CoordinatorStreamUtil.getCoordinatorStreamName(anyObject(), anyObject())).thenReturn("test");
  }

  @After
  public void tearDown() {
    MockSystemFactory.MSG_QUEUES.clear();
  }

  @Test
  public void testPartitionCountMonitorWithDurableStates() {
    configMap.put("stores.mystore.changelog", "mychangelog");
    configMap.put(JobConfig.JOB_CONTAINER_COUNT(), "1");
    when(CoordinatorStreamUtil.readConfigFromCoordinatorStream(anyObject())).thenReturn(new MapConfig(configMap));
    Config config = new MapConfig(configMap);

    // mimic job runner code to write the config to coordinator stream
    CoordinatorStreamSystemProducer producer = new CoordinatorStreamSystemProducer(config, mock(MetricsRegistry.class));
    producer.writeConfig("test-job", config);

    ClusterBasedJobCoordinator clusterCoordinator = new ClusterBasedJobCoordinator(config);

    // change the input system stream metadata
    MockSystemFactory.MSG_QUEUES.put(new SystemStreamPartition("kafka", "topic1", new Partition(1)), new ArrayList<>());

    StreamPartitionCountMonitor monitor = clusterCoordinator.getPartitionMonitor();
    monitor.updatePartitionCountMetric();
    assertEquals(clusterCoordinator.getAppStatus(), SamzaApplicationState.SamzaAppStatus.FAILED);
  }

  @Test
  public void testPartitionCountMonitorWithoutDurableStates() {
    configMap.put(JobConfig.JOB_CONTAINER_COUNT(), "1");
    when(CoordinatorStreamUtil.readConfigFromCoordinatorStream(anyObject())).thenReturn(new MapConfig(configMap));
    Config config = new MapConfig(configMap);

    // mimic job runner code to write the config to coordinator stream
    CoordinatorStreamSystemProducer producer = new CoordinatorStreamSystemProducer(config, mock(MetricsRegistry.class));
    producer.writeConfig("test-job", config);

    ClusterBasedJobCoordinator clusterCoordinator = new ClusterBasedJobCoordinator(config);

    // change the input system stream metadata
    MockSystemFactory.MSG_QUEUES.put(new SystemStreamPartition("kafka", "topic1", new Partition(1)), new ArrayList<>());

    StreamPartitionCountMonitor monitor = clusterCoordinator.getPartitionMonitor();
    monitor.updatePartitionCountMetric();
    assertEquals(clusterCoordinator.getAppStatus(), SamzaApplicationState.SamzaAppStatus.UNDEFINED);
  }

  @Test
  public void testVerifyStartpointManagerFanOut() throws IOException {
    configMap.put(JobConfig.JOB_CONTAINER_COUNT(), "1");
    configMap.put("job.jmx.enabled", "false");
    when(CoordinatorStreamUtil.readConfigFromCoordinatorStream(anyObject())).thenReturn(new MapConfig(configMap));
    Config config = new MapConfig(configMap);
    MockitoException stopException = new MockitoException("Stop");

    ClusterBasedJobCoordinator clusterCoordinator = Mockito.spy(new ClusterBasedJobCoordinator(config));
    ContainerProcessManager mockContainerProcessManager = mock(ContainerProcessManager.class);
    doReturn(true).when(mockContainerProcessManager).shouldShutdown();
    StartpointManager mockStartpointManager = mock(StartpointManager.class);

    // Stop ClusterBasedJobCoordinator#run after stop() method by throwing an exception to stop the run loop.
    // ClusterBasedJobCoordinator will need to be refactored for better mock support.
    doThrow(stopException).when(mockStartpointManager).stop();

    doReturn(mockContainerProcessManager).when(clusterCoordinator)
        .createContainerProcessManager(getClass().getClassLoader());
    doReturn(mockStartpointManager).when(clusterCoordinator).createStartpointManager();
    try {
      clusterCoordinator.run();
    } catch (SamzaException ex) {
      assertEquals(stopException, ex.getCause());
      verify(mockStartpointManager).start();
      verify(mockStartpointManager).fanOut(any());
      verify(mockStartpointManager).stop();
      return;
    }
    fail("Expected run() method to stop after StartpointManager#stop()");
  }
}
