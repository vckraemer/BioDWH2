package de.unibi.agbi.biodwh2.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.unibi.agbi.biodwh2.core.exceptions.ParserException;
import de.unibi.agbi.biodwh2.core.exceptions.UpdaterException;
import de.unibi.agbi.biodwh2.core.exceptions.UpdaterOnlyManuallyException;
import de.unibi.agbi.biodwh2.core.model.Configuration;
import de.unibi.agbi.biodwh2.core.model.DataSourceMetadata;
import de.unibi.agbi.biodwh2.core.model.Version;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

public class Workspace {
    private static final Logger logger = LoggerFactory.getLogger(Workspace.class);

    public static final int Version = 1;
    private static final String SourcesDirectory = "sources";

    private final String workingDirectory;
    private final Configuration configuration;
    private final List<DataSource> dataSources;

    public Workspace(String workingDirectory) throws IOException {
        this.workingDirectory = workingDirectory;
        createWorkingDirectoryIfNotExists();
        configuration = createOrLoadConfiguration();
        dataSources = resolveUsedDataSources();
    }

    private void createWorkingDirectoryIfNotExists() throws IOException {
        Files.createDirectories(Paths.get(workingDirectory));
        Files.createDirectories(Paths.get(getSourcesDirectory()));
    }

    String getSourcesDirectory() {
        return Paths.get(workingDirectory, SourcesDirectory).toString();
    }

    private Configuration createOrLoadConfiguration() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Path path = Paths.get(workingDirectory, "config.json");
        if (Files.exists(path))
            return objectMapper.readValue(path.toFile(), Configuration.class);
        Configuration configuration = new Configuration();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.writeValue(path.toFile(), configuration);
        return configuration;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    private List<DataSource> resolveUsedDataSources() {
        List<DataSource> dataSources = new ArrayList<>();
        List<Class<DataSource>> availableDataSourceClasses = Factory.getInstance().getImplementations(DataSource.class);
        for (Class<DataSource> dataSourceClass : availableDataSourceClasses) {
            DataSource dataSource = tryInstantiateDataSource(dataSourceClass);
            if (dataSource != null && isDataSourceUsed(dataSource))
                dataSources.add(dataSource);
        }
        return dataSources;
    }

    private DataSource tryInstantiateDataSource(Class<DataSource> dataSourceClass) {
        try {
            return dataSourceClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            logger.error("Failed to instantiate data source '" + dataSourceClass.getName() + "'", e);
        }
        return null;
    }

    private boolean isDataSourceUsed(DataSource dataSource) {
        return configuration.dataSourceIds.contains(dataSource.getId());
    }

    public void checkState() {
        ensureDataSourceDirectoriesExist();
        createOrLoadDataSourcesMetadata();
        Map<String, Boolean> sourcesUptodate = createSourcesUptodate(dataSources);
        String state = dataSourceMetadataToTable(dataSources, sourcesUptodate);
        logger.info(state);
        logger.info((countSourcesUptodate(sourcesUptodate) == dataSources.size()) ? "all source data are up-to-date." :
                    countSourcesUptodate(sourcesUptodate) + "/" + dataSources.size() + " source data are up-to-date.");
    }

    private int countSourcesUptodate(Map<String, Boolean> sourcesUptodate) {
        return Collections.frequency(sourcesUptodate.values(), true);
    }

    private String getLatestVersion(DataSource dataSource) {
        try {
            return dataSource.getUpdater().getNewestVersion().toString();
        } catch (UpdaterException e) {
            logger.error("New version of " + dataSource.getId() + " is not accessible.");
            return "-";
        }
    }



    private Map<String, Boolean> createSourcesUptodate(List<DataSource> dataSources) {
        Map<String, Boolean> sourcesUptodate = new HashMap<>();
        for (DataSource dataSource : dataSources) {
            String currentVersion = dataSource.getMetadata().version.toString();
            String latestVersion = getLatestVersion(dataSource);
            sourcesUptodate.put(dataSource.getId(), currentVersion.equals(latestVersion));
        }
        return sourcesUptodate;
    }

    private String dataSourceMetadataToTable(List<DataSource> dataSources, Map<String, Boolean> sourcesUptodate) {
        String state = "";
        String spacer = StringUtils.repeat("-", 150);
        String heading = String.format("\n%s\n%-15s%-33s%-23s%-25s%-37s%-28s\n%s\n", spacer, "SourceID",
                                       "Version is up-to-date", "Version", "new Version", "Time of latest update",
                                       "Files", spacer);
        for (DataSource dataSource : dataSources) {
            String dataSourceId = dataSource.getId();
            DataSourceMetadata meta = dataSource.getMetadata();
            Boolean isVersionUptodate = sourcesUptodate.get(dataSourceId);
            Version workspaceVersion = meta.version;
            String latestVersion = getLatestVersion(dataSource);
            List<String> existingFiles = meta.sourceFileNames;
            LocalDateTime latestUpdateTime = meta.getLocalUpdateDateTime();
            state = String.format("%s%-23s%-21s%-25s%-25s%-35s%-30s\n", state, dataSourceId, isVersionUptodate,
                                 workspaceVersion, latestVersion, latestUpdateTime, existingFiles);
        }
        return heading + state + spacer;
    }

    private void ensureDataSourceDirectoriesExist() {
        for (DataSource dataSource : dataSources) {
            try {
                dataSource.createDirectoryIfNotExists(this);
            } catch (IOException e) {
                logger.error("Failed to create data source directory for '" + dataSource.getId() + "'", e);
            }
        }
    }

    private void createOrLoadDataSourcesMetadata() {
        for (DataSource dataSource : dataSources) {
            try {
                dataSource.createOrLoadMetadata(this);
            } catch (IOException e) {
                logger.error("Failed to load data source metadata for '" + dataSource.getId() + "'", e);
            }
        }
    }

    public void updateDataSources() {
        ensureDataSourceDirectoriesExist();
        createOrLoadDataSourcesMetadata();
        for (DataSource dataSource : dataSources) {
            logger.info("Processing of data source '" + dataSource.getId() + "' started");
            try {
                boolean updated = dataSource.getUpdater().update(this, dataSource);
                logger.info("\tupdated: " + updated);
            } catch (UpdaterOnlyManuallyException e) {
                logger.error("Data source '" + dataSource.getId() + "' can only be updated manually." +
                             "Download the new version of " + dataSource.getId() +
                             " and use the command line parameter -i or --integrate to add the data" +
                             " to the workspace. \n" +
                             "Help: https://github.com/AstrorEnales/BioDWH2/blob/develop/doc/usage.md");
            } catch (UpdaterException e) {
                logger.error("Failed to update data source '" + dataSource.getId() + "'", e);
            }
            try {
                boolean parsed = dataSource.getParser().parse(this, dataSource);
                logger.info("\tparsed: " + parsed);
            } catch (ParserException e) {
                logger.error("Failed to parse data source '" + dataSource.getId() + "'", e);
            }
            boolean exported = dataSource.getRdfExporter().export(this, dataSource);
            logger.info("\texported: " + exported);
            exported = dataSource.getGraphExporter().export(this, dataSource);
            logger.info("\texported: " + exported);
            logger.info("Processing of data source '" + dataSource.getId() + "' finished");
        }
    }

    public void integrateDataSources(List<String> args) {
        ensureDataSourceDirectoriesExist();
        createOrLoadDataSourcesMetadata();
        if (args.size() > 2) {
            String sourceName = args.get(1);
            String version = args.get(2);
            for (DataSource dataSource : dataSources) {
                if (dataSource.getId().equals(sourceName)) {
                    logger.info("Processing of data source '" + dataSource.getId() + "' started");
                    try {
                        boolean updated = dataSource.getUpdater().integrate(this, dataSource, version);
                        logger.info("\tupdated manually: " + updated);
                    } catch (Exception e) {
                        logger.error("Failed to update data source '" + dataSource.getId() + "'", e);
                    }
                    try {
                        boolean parsed = dataSource.getParser().parse(this, dataSource);
                        logger.info("\tparsed: " + parsed);
                    } catch (ParserException e) {
                        logger.error("Failed to parse data source '" + dataSource.getId() + "'", e);
                    }
                    boolean exported = dataSource.getRdfExporter().export(this, dataSource);
                    logger.info("\texported: " + exported);
                    logger.info("Processing of data source '" + dataSource.getId() + "' finished");
                }
            }
        } else {
            logger.error("Failed to read source name and version from the command line");
        }
    }
}
