/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.container_orchestrator;

import com.fasterxml.jackson.core.type.TypeReference;
import io.airbyte.commons.features.EnvVariableFeatureFlags;
import io.airbyte.commons.features.FeatureFlags;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.logging.LoggingHelper;
import io.airbyte.commons.logging.MdcScope;
import io.airbyte.commons.temporal.TemporalUtils;
import io.airbyte.commons.temporal.sync.OrchestratorConstants;
import io.airbyte.config.Configs;
import io.airbyte.config.EnvConfigs;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.workers.WorkerConfigs;
import io.airbyte.workers.process.AsyncKubePodStatus;
import io.airbyte.workers.process.AsyncOrchestratorPodProcess;
import io.airbyte.workers.process.DockerProcessFactory;
import io.airbyte.workers.process.KubePodInfo;
import io.airbyte.workers.process.KubePodProcess;
import io.airbyte.workers.process.KubePortManagerSingleton;
import io.airbyte.workers.process.KubeProcessFactory;
import io.airbyte.workers.process.ProcessFactory;
import io.airbyte.workers.storage.StateClients;
import io.airbyte.workers.sync.DbtLauncherWorker;
import io.airbyte.workers.sync.NormalizationLauncherWorker;
import io.airbyte.workers.sync.ReplicationLauncherWorker;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micronaut.runtime.Micronaut;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the application responsible for launching containers and handling all message
 * passing for replication, normalization, and dbt. Also, the current version relies on a heartbeat
 * from a Temporal worker. This will also be removed in the future so this can run fully async.
 * <p>
 * This application retrieves most of its configuration from copied files from the calling Temporal
 * worker.
 * <p>
 * This app uses default logging which is directly captured by the calling Temporal worker. In the
 * future this will need to independently interact with cloud storage.
 */
@SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.DoNotTerminateVM"})
public class Application {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final int MAX_SECONDS_TO_WAIT_FOR_FILE_COPY = 60;

  // TODO Move the following to configuration once converted to a Micronaut service

  // IMPORTANT: Changing the storage location will orphan already existing kube pods when the new
  // version is deployed!
  private static final Path STATE_STORAGE_PREFIX = Path.of("/state");
  private static final Integer KUBE_HEARTBEAT_PORT = 9000;

  private final String application;
  private final Map<String, String> envMap;
  private final JobRunConfig jobRunConfig;
  private final KubePodInfo kubePodInfo;
  private final Configs configs;
  private final FeatureFlags featureFlags;

  public Application(
                     final String application,
                     final Map<String, String> envMap,
                     final JobRunConfig jobRunConfig,
                     final KubePodInfo kubePodInfo,
                     final FeatureFlags featureFlags) {
    this.application = application;
    this.envMap = envMap;
    this.jobRunConfig = jobRunConfig;
    this.kubePodInfo = kubePodInfo;
    this.configs = new EnvConfigs(envMap);
    this.featureFlags = featureFlags;
  }

  /**
   * Handles state updates (including writing failures) and running the job orchestrator. As much of
   * the initialization as possible should go in here, so it's logged properly and the state storage
   * is updated appropriately.
   */
  private void runInternal(final AsyncStateManager asyncStateManager) {
    try {
      asyncStateManager.write(kubePodInfo, AsyncKubePodStatus.INITIALIZING);

      final WorkerConfigs workerConfigs = new WorkerConfigs(configs);
      final ProcessFactory processFactory = getProcessBuilderFactory(configs, workerConfigs);
      final JobOrchestrator<?> jobOrchestrator = getJobOrchestrator(configs, workerConfigs,
          processFactory, application, featureFlags);

      if (jobOrchestrator == null) {
        throw new IllegalStateException(
            "Could not find job orchestrator for application: " + application);
      }

      asyncStateManager.write(kubePodInfo, AsyncKubePodStatus.RUNNING);

      final Optional<String> output = jobOrchestrator.runJob();

      asyncStateManager.write(kubePodInfo, AsyncKubePodStatus.SUCCEEDED, output.orElse(""));

      // required to kill clients with thread pools
      System.exit(0);
    } catch (final Throwable t) {
      log.error("Killing orchestrator because of an Exception", t);
      asyncStateManager.write(kubePodInfo, AsyncKubePodStatus.FAILED);
      System.exit(1);
    }
  }

  /**
   * Configures logging/mdc scope, and creates all objects necessary to handle state updates.
   * Everything else is delegated to {@link Application#runInternal}.
   */
  public void run() {
    configureLogging();

    // set mdc scope for the remaining execution
    try (final var mdcScope = new MdcScope.Builder()
        .setLogPrefix(application)
        .setPrefixColor(LoggingHelper.Color.CYAN_BACKGROUND)
        .build()) {

      // IMPORTANT: Changing the storage location will orphan already existing kube pods when the new
      // version is deployed!
      final var documentStoreClient = StateClients.create(configs.getStateStorageCloudConfigs(),
          STATE_STORAGE_PREFIX);
      final var asyncStateManager = new AsyncStateManager(documentStoreClient);

      runInternal(asyncStateManager);
    }
  }

  public static void main(final String[] args) {
    final var ctx = Micronaut.run(Application.class, args);
    log.info("stopping 2!!!");
    ctx.close();

    try {
      // otherwise the pod hangs on closing
      // Signal.handle(new Signal("TERM"), sig -> {
      // log.error("Received termination signal, failing...");
      // System.exit(1);
      // });

      // wait for config files to be copied
      final var successFile = Path.of(KubePodProcess.CONFIG_DIR, KubePodProcess.SUCCESS_FILE_NAME);
      int secondsWaited = 0;

      while (!successFile.toFile().exists() && secondsWaited < MAX_SECONDS_TO_WAIT_FOR_FILE_COPY) {
        log.info("Waiting for config file transfers to complete...");
        Thread.sleep(1000);
        secondsWaited++;
      }

      if (!successFile.toFile().exists()) {
        log.error("Config files did not transfer within the maximum amount of time ({} seconds)!",
            MAX_SECONDS_TO_WAIT_FOR_FILE_COPY);
        System.exit(1);
      }

      final var applicationName = Files.readString(
          Path.of(KubePodProcess.CONFIG_DIR, OrchestratorConstants.INIT_FILE_APPLICATION));
      final var envMap = Jsons.deserialize(
          Path.of(KubePodProcess.CONFIG_DIR, OrchestratorConstants.INIT_FILE_ENV_MAP).toFile(),
          new TypeReference<Map<String, String>>() {});
      final var jobRunConfig = Jsons.deserialize(
          Path.of(KubePodProcess.CONFIG_DIR, OrchestratorConstants.INIT_FILE_JOB_RUN_CONFIG)
              .toFile(),
          JobRunConfig.class);
      final var kubePodInfo = Jsons.deserialize(
          Path.of(KubePodProcess.CONFIG_DIR, AsyncOrchestratorPodProcess.KUBE_POD_INFO).toFile(),
          KubePodInfo.class);
      final FeatureFlags featureFlags = new EnvVariableFeatureFlags();

      final var app = new Application(applicationName, envMap, jobRunConfig, kubePodInfo,
          featureFlags);
      app.run();
    } catch (final Throwable t) {
      log.error("Orchestrator failed...", t);
      // otherwise the pod hangs on closing
      System.exit(1);
    }
  }

  private void configureLogging() {
    OrchestratorConstants.ENV_VARS_TO_TRANSFER.stream()
        .filter(envMap::containsKey)
        .forEach(envVar -> System.setProperty(envVar, envMap.get(envVar)));

    // make sure the new configuration is picked up
    final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    ctx.reconfigure();

    LogClientSingleton.getInstance().setJobMdc(
        configs.getWorkerEnvironment(),
        configs.getLogConfigs(),
        TemporalUtils.getJobRoot(
            configs.getWorkspaceRoot(), jobRunConfig.getJobId(), jobRunConfig.getAttemptId()));
  }

  private static JobOrchestrator<?> getJobOrchestrator(final Configs configs,
                                                       final WorkerConfigs workerConfigs,
                                                       final ProcessFactory processFactory,
                                                       final String application,
                                                       final FeatureFlags featureFlags) {
    return switch (application) {
      case ReplicationLauncherWorker.REPLICATION -> new ReplicationJobOrchestrator(configs, processFactory, featureFlags);
      case NormalizationLauncherWorker.NORMALIZATION -> new NormalizationJobOrchestrator(configs, processFactory);
      case DbtLauncherWorker.DBT -> new DbtJobOrchestrator(configs, workerConfigs, processFactory);
      case AsyncOrchestratorPodProcess.NO_OP -> new NoOpOrchestrator();
      default -> null;
    };
  }

  /**
   * Creates a process builder factory that will be used to create connector containers/pods.
   */
  private static ProcessFactory getProcessBuilderFactory(final Configs configs,
                                                         final WorkerConfigs workerConfigs)
      throws IOException {
    if (configs.getWorkerEnvironment() == Configs.WorkerEnvironment.KUBERNETES) {
      final KubernetesClient fabricClient = new DefaultKubernetesClient();
      final String localIp = InetAddress.getLocalHost().getHostAddress();
      // TODO move port to configuration
      final String kubeHeartbeatUrl = localIp + ":" + KUBE_HEARTBEAT_PORT;
      log.info("Using Kubernetes namespace: {}", configs.getJobKubeNamespace());

      // this needs to have two ports for the source and two ports for the destination (all four must be
      // exposed)
      KubePortManagerSingleton.init(OrchestratorConstants.PORTS);

      return new KubeProcessFactory(workerConfigs,
          configs.getJobKubeNamespace(),
          fabricClient,
          kubeHeartbeatUrl,
          false);
    } else {
      return new DockerProcessFactory(
          workerConfigs,
          configs.getWorkspaceRoot(),
          configs.getWorkspaceDockerMount(),
          configs.getLocalDockerMount(),
          configs.getDockerNetwork());
    }
  }

}
