package org.arquillian.cube.docker.impl.client.reporter;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Version;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import org.arquillian.cube.docker.impl.client.CubeDockerConfiguration;
import org.arquillian.cube.docker.impl.client.config.CubeContainer;
import org.arquillian.cube.docker.impl.client.config.DockerCompositions;
import org.arquillian.cube.docker.impl.client.config.Link;
import org.arquillian.cube.docker.impl.docker.DockerClientExecutor;
import org.arquillian.cube.docker.impl.reporter.DockerContainerSection;
import org.arquillian.cube.spi.Cube;
import org.arquillian.cube.spi.CubeRegistry;
import org.arquillian.cube.spi.event.lifecycle.AfterAutoStart;
import org.arquillian.reporter.api.builder.Reporter;
import org.arquillian.reporter.api.builder.entry.DataCollectionBuilder;
import org.arquillian.reporter.api.builder.report.ReportBuilder;
import org.arquillian.reporter.api.event.SectionEvent;
import org.arquillian.reporter.api.event.TestMethodSection;
import org.arquillian.reporter.api.model.entry.FileEntry;
import org.arquillian.reporter.api.model.entry.data.DataCollectionEntry;
import org.arquillian.reporter.api.model.entry.data.Label;
import org.arquillian.reporter.config.ReporterConfiguration;
import org.jboss.arquillian.core.api.Event;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.event.suite.After;
import org.jboss.arquillian.test.spi.event.suite.Before;

import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_API_VERSION;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_ARCH;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_COMPOSITION_SCHEMA;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_ENVIRONMENT;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_HOST_INFORMATION;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_KERNEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_OS;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.DOCKER_VERSION;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.LOG_PATH;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.MEMORY_STATISTICS;
import static org.arquillian.cube.docker.impl.client.reporter.DockerEnvironmentReportKey.NETWORK_TOPOLOGY_SCHEMA;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.ADAPTER_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.AFTER_TEST_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.BEFORE_TEST_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.IO_BYTES_READ_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.IO_BYTES_WRITE_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.LIMIT_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.MAX_USAGE_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.RX_BYTES_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.TX_BYTES_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.USAGE_LABEL;
import static org.arquillian.cube.docker.impl.client.reporter.DockerLabels.USE_LABEL;

/**
 * Class that reports generic Docker information like orchestration or docker version.
 */
public class TakeDockerEnvironment {

    private static Logger log = Logger.getLogger(TakeDockerEnvironment.class.getName());
    private static FileEntry EMPTY_SCREENSHOT = new FileEntry((String) null);
    private static CubeStatistics statsBeforeMethod;
    private static CubeStatistics statsAfterMethod;

    @Inject
    Event<SectionEvent> reportEvent;


    public void reportDockerEnvironment(@Observes AfterAutoStart event, CubeDockerConfiguration cubeDockerConfiguration, DockerClientExecutor executor, ReporterConfiguration reporterConfiguration) {

        final ReportBuilder reportBuilder = Reporter.createReport(DOCKER_ENVIRONMENT)
                .addReport(createDockerInfoGroup(executor));

        reportBuilder.addKeyValueEntry(DOCKER_COMPOSITION_SCHEMA, createDockerCompositionSchema(cubeDockerConfiguration, reporterConfiguration));
        reportBuilder.addKeyValueEntry(NETWORK_TOPOLOGY_SCHEMA, createNetworkTopologyGraph(cubeDockerConfiguration, executor, reporterConfiguration));

        reportBuilder
                .inSection(DockerContainerSection.standalone())
                .fire(reportEvent);
    }

    public void captureContainerStatsBeforeTest(@Observes Before before, DockerClientExecutor executor, CubeRegistry cubeRegistry) throws IOException {
        captureStats(executor, cubeRegistry, "before", false);
    }

    public void reportContainerStatsAfterTest(@Observes After after, DockerClientExecutor executor, CubeRegistry cubeRegistry) throws IOException {
        captureStats(executor, cubeRegistry, "after", false);
    }

    public void reportContainerLogs(@Observes org.arquillian.cube.spi.event.lifecycle.BeforeStop beforeStop, DockerClientExecutor executor, ReporterConfiguration reporterConfiguration) throws IOException {
        final String cubeId = beforeStop.getCubeId();
        if (cubeId != null) {
            final File logFile = new File(createContainerLogDirectory(new File(reporterConfiguration.getRootDirectory())), cubeId + ".log");
            final Path rootDir = Paths.get(reporterConfiguration.getRootDirectory());
            final Path relativePath = rootDir.relativize(logFile.toPath());

            executor.copyLog(beforeStop.getCubeId(), false, true, true, true, -1, new FileOutputStream(logFile));

            Reporter.createReport()
                    .addKeyValueEntry(LOG_PATH, new FileEntry(relativePath))
                    .inSection(new DockerContainerSection(cubeId))
                    .fire(reportEvent);
        }
    }

    private DataCollectionEntry createContainerStatsIOGroup(Boolean decimal) {

        DataCollectionBuilder bytesReadBuilder =
            generateDataCollection(TakeDockerEnvironment.statsBeforeMethod.getIoBytesRead(),
                                   TakeDockerEnvironment.statsAfterMethod.getIoBytesRead(), decimal)
                .assignParentLabel(IO_BYTES_READ_LABEL);

        DataCollectionBuilder bytesWriteBuilder =
            generateDataCollection(TakeDockerEnvironment.statsBeforeMethod.getIoBytesWrite(),
                                   TakeDockerEnvironment.statsAfterMethod.getIoBytesWrite(), decimal)
                .assignParentLabel(IO_BYTES_WRITE_LABEL);

        return Reporter.createDataCollection("IO statistics")
            .addDataCollection(bytesReadBuilder, bytesWriteBuilder)
            .build();
    }

    private DataCollectionEntry createContainerStatMemoryGroup(Boolean decimal) {

        DataCollectionBuilder usageBuilder =
            generateDataCollection(TakeDockerEnvironment.statsBeforeMethod.getUsage(),
                                   TakeDockerEnvironment.statsAfterMethod.getUsage(), decimal)
                .assignParentLabel(USAGE_LABEL);

        DataCollectionBuilder maxUsageBuilder =
            generateDataCollection(TakeDockerEnvironment.statsBeforeMethod.getMaxUsage(),
                                   TakeDockerEnvironment.statsAfterMethod.getMaxUsage(), decimal)
                .assignParentLabel(MAX_USAGE_LABEL);

        return Reporter.createDataCollection(MEMORY_STATISTICS)
            .addDataCollection(usageBuilder, maxUsageBuilder)
            .addByteDataItem(TakeDockerEnvironment.statsBeforeMethod.getLimit(), decimal, LIMIT_LABEL)
            .build();
    }

    private DataCollectionEntry createContainerStatNetworksGroup(Boolean decimal) {


        Map<String, Map<String, Long>> networksBeforeTest = TakeDockerEnvironment.statsBeforeMethod.getNetworks();
        Map<String, Map<String, Long>> networksAfterTest = TakeDockerEnvironment.statsBeforeMethod.getNetworks();

        DataCollectionBuilder networkCollection = Reporter.createDataCollection("Networks statistics");

        networksBeforeTest.keySet().stream()
            .filter(adapter -> networksBeforeTest.containsKey(adapter) && networksAfterTest.containsKey(adapter))
            .forEach(adapter -> {

                Label adapterLabel = new Label(adapter).parent(ADAPTER_LABEL);

                Map<String, Long> before = networksBeforeTest.get(adapter);
                Map<String, Long> after = networksAfterTest.get(adapter);

                DataCollectionBuilder rxCollection =
                    generateDataCollection(before.get("rx_bytes"), after.get("rx_bytes"), decimal)
                        .assignParentLabel(RX_BYTES_LABEL);
                DataCollectionBuilder txCollection =
                    generateDataCollection(before.get("tx_bytes"), after.get("tx_bytes"), decimal)
                        .assignParentLabel(TX_BYTES_LABEL);

                networkCollection
                    .addDataCollection(rxCollection, adapterLabel)
                    .addDataCollection(txCollection, adapterLabel);

            });

        return networkCollection.build();
    }

    private DataCollectionBuilder generateDataCollection(Long beforeTest, Long afterTest, Boolean decimal) {
        return Reporter.createDataCollection("")
            .addByteDataItem(beforeTest, decimal, BEFORE_TEST_LABEL)
            .addByteDataItem(afterTest, decimal, AFTER_TEST_LABEL)
            .addByteDataItem(afterTest - beforeTest, decimal, USE_LABEL);
    }

    public void captureStats(DockerClientExecutor executor, CubeRegistry cubeRegistry, String when, Boolean decimal) throws IOException {
        if (executor != null) {
            List<Cube<?>> containers = cubeRegistry.getCubes();
            for (Cube<?> container : containers) {
                String name = container.getId();
                Statistics statistics = executor.statsContainer(name);
                if ("before".equals(when)) {
                    this.statsBeforeMethod = ContainerStatsBuilder.updateStats(statistics);
                } else {
                    this.statsAfterMethod = ContainerStatsBuilder.updateStats(statistics);
                    createEntryAndFire(name, decimal);
                }
            }
        }

    }

    private FileEntry createDockerCompositionSchema(CubeDockerConfiguration cubeDockerConfiguration, ReporterConfiguration reporterConfiguration) {

        final mxGraph graph = new mxGraph();
        final Object parent = graph.getDefaultParent();

        graph.setAutoSizeCells(true);
        graph.getModel().beginUpdate();

        try {

            final DockerCompositions dockerContainersContent = cubeDockerConfiguration.getDockerContainersContent();
            final Map<String, CubeContainer> containers = dockerContainersContent.getContainers();

            final Map<String, Object> insertedVertex = new HashMap<>();

            for (Map.Entry<String, CubeContainer> containerEntry : containers.entrySet()) {
                String containerId = containerEntry.getKey();
                CubeContainer cubeContainer = containerEntry.getValue();

                updateGraph(graph, parent, insertedVertex, containerId, cubeContainer);

            }

        } finally {
            graph.getModel().endUpdate();
        }

        mxIGraphLayout layout = new mxHierarchicalLayout(graph, SwingConstants.WEST);
        layout.execute(graph.getDefaultParent());

        return generateCompositionSchemaImage(graph, reporterConfiguration);
    }

    private FileEntry createNetworkTopologyGraph(CubeDockerConfiguration cubeDockerConfiguration, DockerClientExecutor executor, ReporterConfiguration reporterConfiguration) {

        mxGraph graph = new mxGraph();
        Object parent = graph.getDefaultParent();

        graph.setAutoSizeCells(true);
        graph.getModel().beginUpdate();
        try {
            DockerCompositions dockerCompositions = cubeDockerConfiguration.getDockerContainersContent();
            final Map<String, CubeContainer> containers = dockerCompositions.getContainers();
            final Map<String, Object> insertedVertex = new HashMap<>();
                for (Map.Entry<String, CubeContainer> container: containers.entrySet()) {
                    final String containerId = container.getKey();
                    Object containerName = graph.insertVertex(parent, null, containerId, 0, 0, 80, 30);
                    final CubeContainer cubeContainer = container.getValue();
                    if (!cubeContainer.isManual()) {
                        Set<String> nwList = new HashSet<>();
                        if (cubeContainer.getNetworkMode() != null) {
                            nwList.add(cubeContainer.getNetworkMode());
                        } else {
                            InspectContainerResponse inspect = executor.inspectContainer(containerId);
                            final String defaultNetwork = inspect.getHostConfig().getNetworkMode();
                            nwList.add(defaultNetwork);
                        }
                        if (cubeContainer.getNetworks() != null) {
                            nwList.addAll(cubeContainer.getNetworks());
                        }
                        for (String nw : nwList) {
                            Object nwName = null;
                            if (insertedVertex.containsKey(nw)) {
                                nwName = insertedVertex.get(nw);
                            } else {
                                nwName = graph.insertVertex(parent, null, nw, 0, 0, 60, 20);
                                graph.setCellStyles(mxConstants.STYLE_FILLCOLOR, "#00FF00", new Object[]{nwName});
                            }
                            graph.updateCellSize(nwName);
                            graph.insertEdge(parent, null, nw, containerName, nwName);
                            insertedVertex.put(nw, nwName);
                        }
                    }
                }
            } finally {
            graph.getModel().endUpdate();
        }

        mxIGraphLayout layout = new mxHierarchicalLayout(graph, SwingConstants.WEST);
        layout.execute(graph.getDefaultParent());

        return  generateNetworkTopologyImage(graph, reporterConfiguration);
    }

    private void updateGraph(mxGraph graph, Object parent, Map<String, Object> insertedVertex, String containerId, CubeContainer cubeContainer) {
        if (insertedVertex.containsKey(containerId)) {
            // container is already added, probably because a direct link from another container
            // now we need to add direct links of this one that before were transitive
            Object currentContainer = insertedVertex.get(containerId);
            createDirectLinks(graph, parent, insertedVertex, cubeContainer, currentContainer);

        } else {
            // create new cube and possible direct link (not transitive ones)
            Object currentContainer = graph.insertVertex(parent, null, containerId, 0, 0, 80, 30);
            graph.updateCellSize(currentContainer);
            insertedVertex.put(containerId, currentContainer);

            createDirectLinks(graph, parent, insertedVertex, cubeContainer, currentContainer);
        }
    }

    private void createDirectLinks(mxGraph graph, Object parent, Map<String, Object> insertedVertex, CubeContainer cubeContainer, Object currentContainer) {
        // create relation to all direct links
        if (cubeContainer.getLinks() != null) {
            for (Link link : cubeContainer.getLinks()) {
                final String linkId = link.getName();

                Object linkContainer = null;
                if (insertedVertex.containsKey(linkId)) {
                    linkContainer = insertedVertex.get(linkId);
                } else {
                    linkContainer = graph.insertVertex(parent, null, linkId, 0, 0, 80, 30);
                }

                graph.updateCellSize(currentContainer);
                graph.insertEdge(parent, null, link.getAlias(), currentContainer, linkContainer);
                insertedVertex.put(linkId, linkContainer);
            }
        }
    }


    private FileEntry generateCompositionSchemaImage(mxGraph graph, ReporterConfiguration reporterConfiguration) {

        final File imageFile = new File(createSchemasDirectory(new File(reporterConfiguration.getRootDirectory())), "docker_composition.png");
        try {
            return createScreenshotEntry(imageFile, graph, reporterConfiguration);
        } catch (IOException e) {
            log.log(Level.WARNING, String.format("Docker compositions schema could not be generated because of %s.", e));
        }

        return EMPTY_SCREENSHOT;
    }

    private FileEntry generateNetworkTopologyImage(mxGraph graph, ReporterConfiguration reporterConfiguration) {
        try {
            final File imageFile = new File(createNetworkTopologyDirectory(new File(reporterConfiguration.getRootDirectory())), "docker_network_topology.png");
            return createScreenshotEntry(imageFile, graph, reporterConfiguration);

        } catch (IOException e) {
            log.log(Level.WARNING, String.format("Docker container network toplogy could not be generated because of %s.", e));
        }

        return EMPTY_SCREENSHOT;

    }

    private FileEntry createScreenshotEntry(File imageFile, mxGraph graph, ReporterConfiguration reporterConfiguration) throws IOException {
        final BufferedImage bufferedImage = mxCellRenderer.createBufferedImage(graph, null, 1, Color.WHITE, true, null);

        ImageIO.write(bufferedImage, "PNG", imageFile);

        final Path rootDir = Paths.get(reporterConfiguration.getRootDirectory());
        final Path relativize = rootDir.relativize(imageFile.toPath());

       return new FileEntry(relativize);
    }

    private ReportBuilder createDockerInfoGroup(DockerClientExecutor executor) {
        Version version = executor.dockerHostVersion();

        final ReportBuilder reportBuilder = Reporter.createReport(DOCKER_HOST_INFORMATION)
                .addKeyValueEntry(DOCKER_VERSION, version.getVersion())
                .addKeyValueEntry(DOCKER_OS, version.getOperatingSystem())
                .addKeyValueEntry(DOCKER_KERNEL, version.getKernelVersion())
                .addKeyValueEntry(DOCKER_API_VERSION, version.getApiVersion())
                .addKeyValueEntry(DOCKER_ARCH, version.getArch());

        return reportBuilder;
    }

    private File createSchemasDirectory(File rootDirectory) {
        final Path reportsSchema = Paths.get("reports", "schemas");
        final Path schemasDir = rootDirectory.toPath().resolve(reportsSchema);

        try {
            Files.createDirectories(schemasDir);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Could not created schemas directory at %s", schemasDir));
        }

        return schemasDir.toFile();
    }

    private File createContainerLogDirectory(File rootDirectory) {
        final Path reportsLogs = Paths.get("reports", "logs");
        final Path logsDir = rootDirectory.toPath().resolve(reportsLogs);
        if (Files.notExists(logsDir)) {
            try {

                Files.createDirectories(logsDir);
            } catch (IOException e) {
                throw new IllegalArgumentException(String.format("Could not created logs directory at %s", logsDir));
            }
        }
        return logsDir.toFile();
    }

    private File createNetworkTopologyDirectory(File rootDirectory) {
        final Path reportsNetworks = Paths.get("reports", "networks");
        final Path networksDir = rootDirectory.toPath().resolve(reportsNetworks);

        try {
            Files.createDirectories(networksDir);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Could not created networks directory at %s", networksDir));
        }

        return networksDir.toFile();
    }

    private void createEntryAndFire(String name, Boolean decimal) {

        Reporter
            .createReport(name + " Statistics")
            .addEntries(createContainerStatMemoryGroup(decimal),
                        createContainerStatsIOGroup(decimal),
                        createContainerStatNetworksGroup(decimal))
        .inSection(new TestMethodSection())
        .fire(reportEvent);
    }
}
