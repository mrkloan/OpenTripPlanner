package org.opentripplanner.graph_builder;

import static org.opentripplanner.datastore.api.FileType.DEM;
import static org.opentripplanner.datastore.api.FileType.GTFS;
import static org.opentripplanner.datastore.api.FileType.NETEX;
import static org.opentripplanner.datastore.api.FileType.OSM;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.module.configure.GraphBuilderFactory;
import org.opentripplanner.graph_builder.services.ned.ElevationGridCoverageFactory;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.config.BuildConfig;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.OTPFeature;
import org.opentripplanner.util.OtpAppException;
import org.opentripplanner.util.time.DurationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This makes a Graph out of various inputs like GTFS and OSM. It is modular: GraphBuilderModules
 * are placed in a list and run in sequence.
 */
public class GraphBuilder implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(GraphBuilder.class);

  private final List<GraphBuilderModule> graphBuilderModules = new ArrayList<>();
  private final Graph graph;
  private final TransitModel transitModel;
  private final DataImportIssueStore issueStore;

  private boolean hasTransitData = false;

  public GraphBuilder(
    @Nonnull Graph baseGraph,
    @Nonnull TransitModel transitModel,
    @Nonnull DataImportIssueStore issueStore
  ) {
    this.graph = baseGraph;
    this.transitModel = transitModel;
    this.issueStore = issueStore;
  }

  /**
   * Factory method to create and configure a GraphBuilder with all the appropriate modules to build
   * a graph from the given data source and configuration directory.
   */
  public static GraphBuilder create(
    BuildConfig config,
    GraphBuilderDataSources dataSources,
    Graph graph,
    TransitModel transitModel,
    boolean loadStreetGraph,
    boolean saveStreetGraph
  ) {
    //DaggerGraphBuilderFactory appFactory = GraphBuilderFactoryDa
    boolean hasOsm = dataSources.has(OSM);
    boolean hasGtfs = dataSources.has(GTFS);
    boolean hasNetex = dataSources.has(NETEX);
    boolean hasTransitData = hasGtfs || hasNetex;

    var factory = GraphBuilderFactory
      .of()
      .withConfig(config)
      .withGraph(graph)
      .withTransitModel(transitModel)
      .withIssueStore()
      .build();

    var graphBuilder = factory.createGraphBuilder();

    graphBuilder.hasTransitData = hasTransitData;
    transitModel.initTimeZone(config.timeZone);

    if (hasOsm) {
      graphBuilder.addModule(factory.createOpenStreetMapModule(dataSources.get(OSM)));
    }

    if (hasGtfs) {
      graphBuilder.addModule(factory.createGtfsModule(dataSources.get(GTFS)));
    }

    if (hasNetex) {
      graphBuilder.addModule(factory.createNetexModule(dataSources.get(NETEX)));
    }

    if (hasTransitData && transitModel.getAgencyTimeZones().size() > 1) {
      graphBuilder.addModule(factory.createTimeZoneAdjusterModule());
    }

    if (hasTransitData && (hasOsm || graphBuilder.graph.hasStreets)) {
      if (config.matchBusRoutesToStreets) {
        graphBuilder.addModule(factory.createBusRouteStreetMatcher());
      }
      graphBuilder.addModule(factory.createOsmBoardingLocationsModule());
    }

    // This module is outside the hasGTFS conditional block because it also links things like bike rental
    // which need to be handled even when there's no transit.
    graphBuilder.addModule(factory.createStreetLinkerModule());

    // Prune graph connectivity islands after transit stop linking, so that pruning can take into account
    // existence of stops in islands. If an island has a stop, it actually may be a real island and should
    // not be removed quite as easily
    if ((hasOsm && !saveStreetGraph) || loadStreetGraph) {
      graphBuilder.addModule(factory.createPruneNoThruIslands());
    }

    // Load elevation data and apply it to the streets.
    // We want to do run this module after loading the OSM street network but before finding transfers.
    List<ElevationGridCoverageFactory> elevationGridCoverageFactories = new ArrayList<>();
    if (config.elevationBucket != null) {
      elevationGridCoverageFactories.add(
        factory.createNedElevationFactory(dataSources.getCacheDirectory())
      );
    } else if (dataSources.has(DEM)) {
      // Load the elevation from a file in the graph inputs directory
      for (DataSource demSource : dataSources.get(DEM)) {
        elevationGridCoverageFactories.add(factory.createGeotiffGridCoverageFactoryImpl(demSource));
      }
    }
    // Refactoring this class, it was made clear that this allows for adding multiple elevation
    // modules to the same graph builder. We do not actually know if this is supported by the
    // ElevationModule class.
    for (ElevationGridCoverageFactory it : elevationGridCoverageFactories) {
      graphBuilder.addModule(factory.createElevationModule(it, dataSources.getCacheDirectory()));
    }

    if (hasTransitData) {
      // Add links to flex areas after the streets has been split, so that also the split edges are connected
      if (OTPFeature.FlexRouting.isOn()) {
        graphBuilder.addModule(factory.createFlexLocationsToStreetEdgesMapper());
      }

      // This module will use streets or straight line distance depending on whether OSM data is found in the graph.
      graphBuilder.addModule(factory.createDirectTransferGenerator());

      // Analyze routing between stops to generate report
      if (OTPFeature.TransferAnalyzer.isOn()) {
        graphBuilder.addModule(factory.createDirectTransferAnalyzer());
      }
    }

    if (loadStreetGraph || hasOsm) {
      graphBuilder.addModule(factory.createGraphCoherencyCheckerModule());
    }

    if (config.dataImportReport) {
      graphBuilder.addModule(factory.createDataImportIssuesToHTML(dataSources.getBuildReportDir()));
    }

    if (OTPFeature.DataOverlay.isOn()) {
      graphBuilder.addModuleOptional(factory.createDataOverlayFactory());
    }

    return graphBuilder;
  }

  public void run() {
    // Record how long it takes to build the graph, purely for informational purposes.
    long startTime = System.currentTimeMillis();

    // Check all graph builder inputs, and fail fast to avoid waiting until the build process
    // advances.
    for (GraphBuilderModule builder : graphBuilderModules) {
      builder.checkInputs();
    }

    for (GraphBuilderModule load : graphBuilderModules) {
      load.buildGraph();
    }

    issueStore.summarize();
    validate();

    long endTime = System.currentTimeMillis();
    LOG.info(
      "Graph building took {}.",
      DurationUtils.durationToStr(Duration.ofMillis(endTime - startTime))
    );
    LOG.info("Main graph size: |V|={} |E|={}", graph.countVertices(), graph.countEdges());
  }

  private void addModule(GraphBuilderModule module) {
    graphBuilderModules.add(module);
  }

  private void addModuleOptional(GraphBuilderModule module) {
    if (module != null) {
      graphBuilderModules.add(module);
    }
  }

  private boolean hasTransitData() {
    return hasTransitData;
  }

  /**
   * Validates the build. Currently, only checks if the graph has transit data if any transit data
   * sets were included in the build. If all transit data gets filtered out due to transit period
   * configuration, for example, then this function will throw a {@link OtpAppException}.
   */
  private void validate() {
    if (hasTransitData() && !transitModel.hasTransit()) {
      throw new OtpAppException(
        "The provided transit data have no trips within the configured transit " +
        "service period. See build config 'transitServiceStart' and " +
        "'transitServiceEnd'"
      );
    }
  }
}
