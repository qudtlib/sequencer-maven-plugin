package io.github.qudtlib.maven.seq;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.*;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.repository.RemoteRepository;

@Mojo(name = "run", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class SeqMojo extends AbstractMojo {

    /** The list of plugin executions to run sequentially */
    @Parameter(name = "steps")
    private List<SequenceStep> steps;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Component private BuildPluginManager pluginManager;

    @Component private MavenPluginManager mavenPluginManager;

    @Component private PlexusContainer container;

    @Parameter(defaultValue = "${mojoExecution.executionId}", readonly = true)
    private String mojoExecutionId;

    @Parameter(defaultValue = "${mojoExecution.goal}", readonly = true)
    private String mojoGoal;

    /** Label to display in the plugin's log output */
    @Parameter(defaultValue = "")
    private String label;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (steps == null || steps.isEmpty()) {
            getLog().info("No nested <step> elements defined - Nothing to do.");
            return;
        }
        int index = 0;
        for (SequenceStep sequenceStep : steps) {
            if (sequenceStep.getPluginCoordinates() != null
                    && !sequenceStep.getPluginCoordinates().trim().isEmpty()) {
                resolvePluginCoordinates(sequenceStep);
            }
            index++;
            String currentExecutionId =
                    sequenceStep.getId() != null
                            ? sequenceStep.getId()
                            : mojoExecutionId + "-" + index;

            // Locate the plugin in the project
            Map<String, Plugin> pluginMap = project.getBuild().getPluginsAsMap();
            String key =
                    String.format("%s:%s", sequenceStep.getGroupId(), sequenceStep.getArtifactId());
            Plugin plugin = pluginMap.get(key);
            if (plugin == null) {
                throw new MojoExecutionException(
                        String.format("Plugin %s is not configured in the project", key));
            }
            getLog().debug("Found plugin: " + plugin);

            // Get remote repositories
            List<RemoteRepository> remoteRepos =
                    ((List<ArtifactRepository>) project.getPluginArtifactRepositories())
                            .stream().map(RepositoryUtils::toRepo).collect(Collectors.toList());

            // Resolve plugin descriptor
            PluginDescriptor pluginDescriptor;
            try {
                pluginDescriptor =
                        mavenPluginManager.getPluginDescriptor(
                                plugin, remoteRepos, session.getRepositorySession());
                getLog().debug("Plugin descriptor: " + pluginDescriptor.getId());
            } catch (Exception e) {
                throw new MojoExecutionException(
                        "Failed to resolve plugin descriptor for " + key, e);
            }

            // Get mojo descriptor
            MojoDescriptor mojoDescriptor;
            try {
                mojoDescriptor = pluginDescriptor.getMojo(sequenceStep.getGoal());
                if (mojoDescriptor == null) {
                    throw new MojoNotFoundException(sequenceStep.getGoal(), pluginDescriptor);
                }
                getLog().debug("Mojo descriptor: " + mojoDescriptor.getGoal());
            } catch (Exception e) {
                throw new MojoExecutionException(
                        "Failed to get mojo descriptor for " + sequenceStep.getGoal(), e);
            }

            // Create MojoExecution
            MojoExecution mojoExecution = new MojoExecution(mojoDescriptor, currentExecutionId);
            // Get the default configuration from the MojoDescriptor
            Xpp3Dom defaultConfig =
                    ConfigurationConverter.toXpp3Dom(mojoDescriptor.getMojoConfiguration());

            // Get the user-provided configuration
            Xpp3Dom userConfig = sequenceStep.getConfiguration();

            // Merge default and user configurations
            Xpp3Dom mergedConfig =
                    defaultConfig != null
                            ? Xpp3Dom.mergeXpp3Dom(
                                    userConfig,
                                    defaultConfig) // do normal merge, not profile-style merge
                            : userConfig;

            // Apply the merged configuration
            if (mergedConfig != null && mergedConfig.getChildCount() > 0) {
                getLog().debug(
                                "Applying merged configuration for "
                                        + currentExecutionId
                                        + ": "
                                        + mergedConfig.toString());
                mojoExecution.setConfiguration(mergedConfig);
            } else {
                getLog().debug(
                                "No configuration provided for "
                                        + currentExecutionId
                                        + "; using default mojo configuration");
            }
            String formattedLabel =
                    getLabel() != null && !getLabel().isEmpty() ? "'" + getLabel() + "' " : "";
            getLog().info(
                            String.format(
                                    "---- %s: %s%sstep %d (%s) %s starting",
                                    mojoGoal,
                                    formattedLabel,
                                    sequenceStep.isSkip() ? "SKIPPING " : "",
                                    index,
                                    currentExecutionId,
                                    formatCoordinates(sequenceStep, defaultConfig)));
            long startTime = System.currentTimeMillis();

            // Execute the mojo using the pluginManager
            try {
                if (!sequenceStep.isSkip()) {
                    pluginManager.executeMojo(session, mojoExecution);
                }
            } catch (PluginParameterException e) {
                getLog().error(
                                "Parameter injection failed for "
                                        + sequenceStep.getGoal()
                                        + ": "
                                        + e.getMessage());
                throw new MojoExecutionException("Parameter injection failed", e);
            } catch (Exception e) {
                getLog().error(
                                "Execution failed for "
                                        + sequenceStep.getGoal()
                                        + ": "
                                        + e.getMessage());
                throw new MojoExecutionException(
                        "Failed to execute "
                                + sequenceStep.getArtifactId()
                                + ":"
                                + sequenceStep.getGoal(),
                        e);
            }

            long duration = System.currentTimeMillis() - startTime;
            getLog().info(
                            String.format(
                                    "---- %s: %sstep %d (%s) completed in %s",
                                    mojoGoal,
                                    formattedLabel,
                                    index,
                                    currentExecutionId,
                                    formatDuration(duration)));
        }
    }

    private String formatDuration(long durationMs) {
        Duration duration = Duration.ofMillis(durationMs);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        long millis = duration.toMillisPart();

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0) {
            sb.append(minutes).append("m ");
        }
        if (hours == 0 && minutes == 0 && millis > 0) {
            // Show milliseconds only for durations under 1 minute
            sb.append(seconds).append(".").append(String.format("%03d", millis)).append("s");
        } else {
            sb.append(seconds).append("s");
        }
        return sb.toString().trim();
    }

    private void resolvePluginCoordinates(SequenceStep step) throws MojoExecutionException {
        String coordString = step.getPluginCoordinates().trim();
        String coordinateExecutionId = null;

        if (isPresent(step.getGroupId())
                || isPresent(step.getArtifactId())
                || isPresent(step.getGoal())
                || isPresent(step.getVersion())
                || isPresent(step.getExecutionId())) {
            throw new MojoExecutionException(
                    "Invalid plugin configuration: configure either pluginCoordinates or individual fields (groupId, artifactId, goal, etc.), not both!");
        }

        if (coordString.contains("@")) {
            String[] splitAt = coordString.split("@", 2);
            coordString = splitAt[0].trim();
            coordinateExecutionId = splitAt[1].trim();
        }

        String[] parts = coordString.split(":");
        if (parts.length == 3) {
            step.setGroupId(parts[0].trim());
            step.setArtifactId(parts[1].trim());
            step.setGoal(parts[2].trim());
        } else if (parts.length == 2) {
            String identifier = parts[0].trim();
            String goal = parts[1].trim();
            step.setGoal(goal);

            Set<String> candidateArtifactIds = new HashSet<>();
            candidateArtifactIds.add(identifier);
            if (!identifier.startsWith("maven-")) {
                candidateArtifactIds.add("maven-" + identifier + "-plugin");
            }
            if (!identifier.endsWith("-maven-plugin")) {
                candidateArtifactIds.add(identifier + "-maven-plugin");
            }

            List<Plugin> matchingPlugins = new ArrayList<>();
            for (Object pluginObj : project.getBuild().getPlugins()) {
                Plugin plugin = (Plugin) pluginObj;
                if (candidateArtifactIds.contains(plugin.getArtifactId())) {
                    matchingPlugins.add(plugin);
                }
            }
            if (project.getPluginManagement() != null) {
                for (Plugin plugin : project.getPluginManagement().getPlugins()) {
                    if (candidateArtifactIds.contains(plugin.getArtifactId())) {
                        matchingPlugins.add(plugin);
                    }
                }
            }

            if (matchingPlugins.isEmpty()) {
                throw new MojoExecutionException(
                        "No plugin found in the POM for identifier: " + identifier);
            } else if (matchingPlugins.size() > 1) {
                throw new MojoExecutionException(
                        "Multiple plugins found in the POM for identifier: "
                                + identifier
                                + ". Please use the full groupId:artifactId:goal format to disambiguate.");
            }

            Plugin resolvedPlugin = matchingPlugins.get(0);
            step.setGroupId(resolvedPlugin.getGroupId());
            step.setArtifactId(resolvedPlugin.getArtifactId());
            step.setExecutionId(coordinateExecutionId);
            step.setVersion(resolvedPlugin.getVersion());
        } else {
            throw new MojoExecutionException(
                    "Invalid pluginCoordinates format. Expected: <groupId>:<artifactId>:<goal>[@<executionId>] or <identifier>:<goal>[@<executionId>]");
        }

        if (coordinateExecutionId != null && !coordinateExecutionId.isEmpty()) {
            step.setExecutionId(coordinateExecutionId);
            String key = String.format("%s:%s", step.getGroupId(), step.getArtifactId());
            Plugin plugin = project.getBuild().getPluginsAsMap().get(key);
            if (plugin != null && plugin.getExecutions() != null) {
                Xpp3Dom pluginExecutionConfig = null;
                for (PluginExecution pe : plugin.getExecutions()) {
                    if (coordinateExecutionId.equals(pe.getId())) {
                        pluginExecutionConfig = (Xpp3Dom) pe.getConfiguration();
                        break;
                    }
                }
                if (pluginExecutionConfig != null) {
                    Xpp3Dom merged =
                            Xpp3Dom.mergeXpp3Dom(step.getConfiguration(), pluginExecutionConfig);
                    step.setConfiguration(merged);
                }
            }
        }

        if (!isPresent(step.getVersion())) {
            String key = String.format("%s:%s", step.getGroupId(), step.getArtifactId());
            Plugin plugin = project.getBuild().getPluginsAsMap().get(key);
            if (plugin == null || !isPresent(plugin.getVersion())) {
                throw new MojoExecutionException(
                        "Version not specified in pluginCoordinates and no version found in POM for "
                                + key);
            }
            step.setVersion(plugin.getVersion());
        }
    }

    private String formatCoordinates(SequenceStep config, Xpp3Dom defaultConfig) {
        String groupId = config.getGroupId();
        String artifactId = config.getArtifactId();
        String version = config.getVersion();
        String goal = config.getGoal();

        String shortName = null;
        if (artifactId.startsWith("maven-")
                && artifactId.endsWith("-plugin")
                && artifactId.length() > "maven-".length() + "-plugin".length()) {
            shortName = artifactId.substring(6, artifactId.length() - 7);
        } else if (artifactId.endsWith("-maven-plugin")
                && artifactId.length() > "-maven-plugin".length()) {
            shortName = artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
        }

        String coord;
        if (shortName != null) {
            coord = shortName + ":" + goal;
        } else {
            coord = groupId + ":" + artifactId + ":" + version + ":" + goal;
        }

        if (isPresent(config.getExecutionId())) {
            coord += "@" + config.getExecutionId();
        }

        if (config.getConfiguration() != null && config.getConfiguration().getChildCount() > 0) {
            coord += " (overlay configuration used)";
        }

        return coord;
    }

    private boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    List<SequenceStep> getSteps() {
        return steps;
    }

    public static class SequenceStep {
        @Parameter private String pluginCoordinates;

        @Parameter private String groupId;

        @Parameter private String artifactId;

        @Parameter private String version;

        @Parameter private String goal;

        @Parameter private String executionId;

        @Parameter private String id;

        @Parameter(name = "configuration")
        private PlexusConfiguration configuration;

        @Parameter(defaultValue = "false")
        private boolean skip;

        private Xpp3Dom configurationDom;

        public String getPluginCoordinates() {
            return pluginCoordinates;
        }

        public void setPluginCoordinates(String pluginCoordinates) {
            this.pluginCoordinates = pluginCoordinates;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getGoal() {
            return goal;
        }

        public void setGoal(String goal) {
            this.goal = goal;
        }

        public String getExecutionId() {
            return executionId;
        }

        public void setExecutionId(String executionId) {
            this.executionId = executionId;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public boolean isSkip() {
            return skip;
        }

        public void setSkip(boolean skip) {
            this.skip = skip;
        }

        public PlexusConfiguration getRawConfiguration() {
            return configuration;
        }

        public void setRawConfiguration(PlexusConfiguration configuration) {
            this.configuration = configuration;
            this.configurationDom = null; // Reset cached Xpp3Dom when raw config changes
        }

        public Xpp3Dom getConfiguration() {
            if (configurationDom == null && configuration != null) {
                configurationDom = ConfigurationConverter.toXpp3Dom(configuration);
            }
            return configurationDom;
        }

        public void setConfiguration(Xpp3Dom configuration) {
            this.configurationDom = configuration;
            // PlexusConfiguration can't be set directly from Xpp3Dom; leave raw config as-is
        }

        @Override
        public String toString() {
            return "SequenceStep{pluginCoordinates='"
                    + pluginCoordinates
                    + "', groupId='"
                    + groupId
                    + "', artifactId='"
                    + artifactId
                    + "', version='"
                    + version
                    + "', goal='"
                    + goal
                    + "', executionId='"
                    + executionId
                    + "', id='"
                    + id
                    + "', configuration='"
                    + (configuration != null ? configuration.toString() : "null")
                    + "', skip="
                    + skip
                    + "}";
        }
    }
}
