package io.github.qudtlib.maven.sequencer;

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

@Mojo(name = "sequence", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class SequencerMojo extends AbstractMojo {

    @Parameter(name = "executions")
    private List<ExecutionConfig> executions;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Component private BuildPluginManager pluginManager;

    @Component private MavenPluginManager mavenPluginManager;

    // Inject the Plexus container so we can look up internal components.
    @Component private PlexusContainer container;

    @Parameter(defaultValue = "${mojoExecution.executionId}", readonly = true)
    private String mojoExecutionId;

    @Parameter(defaultValue = "${mojoExecution.goal}", readonly = true)
    private String mojoGoal;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().debug("Raw executions list: " + executions);
        if (executions == null || executions.isEmpty()) {
            getLog().info("No executions defined. Nothing to do.");
            return;
        }
        int index = 0;
        for (ExecutionConfig config : executions) {
            if (config.getPluginCoordinates() != null
                    && !config.getPluginCoordinates().trim().isEmpty()) {
                resolvePluginCoordinates(config);
            }
            index++;
            String currentExecutionId =
                    config.getId() != null ? config.getId() : mojoExecutionId + "-" + index;

            // Locate the plugin in the project
            Map<String, Plugin> pluginMap = project.getBuild().getPluginsAsMap();
            String key = String.format("%s:%s", config.getGroupId(), config.getArtifactId());
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
                mojoDescriptor = pluginDescriptor.getMojo(config.getGoal());
                if (mojoDescriptor == null) {
                    throw new MojoNotFoundException(config.getGoal(), pluginDescriptor);
                }
                getLog().debug("Mojo descriptor: " + mojoDescriptor.getGoal());
            } catch (Exception e) {
                throw new MojoExecutionException(
                        "Failed to get mojo descriptor for " + config.getGoal(), e);
            }

            // Create MojoExecution
            MojoExecution mojoExecution = new MojoExecution(mojoDescriptor, currentExecutionId);
            // Get the default configuration from the MojoDescriptor
            Xpp3Dom defaultConfig =
                    Xpp3DomUtils.ConfigurationConverter.toXpp3Dom(
                            mojoDescriptor.getMojoConfiguration());

            // Get the user-provided configuration,  merged with the config corresponding to the
            // executionId, if present
            Xpp3Dom userConfig = config.getConfiguration();

            // Merge default and user configurations
            Xpp3Dom mergedConfig =
                    defaultConfig != null
                            ? Xpp3Dom.mergeXpp3Dom(userConfig, defaultConfig)
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

            getLog().info(
                            String.format(
                                    "---- %s: step %d (%s): %s",
                                    mojoGoal,
                                    index,
                                    currentExecutionId,
                                    formatCoordinates(config, defaultConfig)));

            // Execute the mojo using the pluginManager
            try {
                pluginManager.executeMojo(session, mojoExecution);
            } catch (PluginParameterException e) {
                getLog().error(
                                "Parameter injection failed for "
                                        + config.getGoal()
                                        + ": "
                                        + e.getMessage());
                throw new MojoExecutionException("Parameter injection failed", e);
            } catch (Exception e) {
                getLog().error("Execution failed for " + config.getGoal() + ": " + e.getMessage());
                throw new MojoExecutionException(
                        "Failed to execute " + config.getArtifactId() + ":" + config.getGoal(), e);
            }
        }
    }

    private void resolvePluginCoordinates(ExecutionConfig config) throws MojoExecutionException {
        String coordString = config.getPluginCoordinates().trim();
        String coordinateExecutionId = null;

        if (isPresent(config.getGroupId())
                || isPresent(config.getArtifactId())
                || isPresent(config.getGoal())
                || isPresent(config.getVersion())
                || isPresent(config.getExecutionId())) {
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
            config.setGroupId(parts[0].trim());
            config.setArtifactId(parts[1].trim());
            config.setGoal(parts[2].trim());
        } else if (parts.length == 2) {
            String identifier = parts[0].trim();
            String goal = parts[1].trim();
            config.setGoal(goal);

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
            config.setGroupId(resolvedPlugin.getGroupId());
            config.setArtifactId(resolvedPlugin.getArtifactId());
            config.setExecutionId(coordinateExecutionId);
            config.setVersion(resolvedPlugin.getVersion());
        } else {
            throw new MojoExecutionException(
                    "Invalid pluginCoordinates format. Expected: <groupId>:<artifactId>:<goal>[@<executionId>] or <identifier>:<goal>[@<executionId>]");
        }

        if (coordinateExecutionId != null && !coordinateExecutionId.isEmpty()) {
            config.setExecutionId(coordinateExecutionId);
            String key = String.format("%s:%s", config.getGroupId(), config.getArtifactId());
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
                            Xpp3Dom.mergeXpp3Dom(config.getConfiguration(), pluginExecutionConfig);
                    config.setConfiguration(merged);
                }
            } else {
                config.setConfiguration(config.convertToXpp3Dom(config.getRawConfiguration()));
            }
        }

        if (!isPresent(config.getVersion())) {
            String key = String.format("%s:%s", config.getGroupId(), config.getArtifactId());
            Plugin plugin = project.getBuild().getPluginsAsMap().get(key);
            if (plugin == null || !isPresent(plugin.getVersion())) {
                throw new MojoExecutionException(
                        "Version not specified in pluginCoordinates and no version found in POM for "
                                + key);
            }
            config.setVersion(plugin.getVersion());
        }
    }

    private String formatCoordinates(ExecutionConfig config, Xpp3Dom defaultConfig) {
        String groupId = config.getGroupId();
        String artifactId = config.getArtifactId();
        String version = config.getVersion();
        String goal = config.getGoal();

        // Try to derive a short name from artifactId if it follows the conventions.
        String shortName = null;
        if (artifactId.startsWith("maven-")
                && artifactId.endsWith("-plugin")
                && artifactId.length() > "maven-".length() + "-plugin".length()) {
            shortName = artifactId.substring(6, artifactId.length() - 7);
        } else if (artifactId.endsWith("-maven-plugin")
                && artifactId.length() > "-maven-plugin".length()) {
            shortName = artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
        }

        // We'll use the short form only if the groupId is one of the well-known ones.

        String coord;
        if (shortName != null) {
            coord = shortName + ":" + goal;
        } else {
            coord = groupId + ":" + artifactId + ":" + version + ":" + goal;
        }

        // Append the executionId if one was specified.
        if (isPresent(config.getExecutionId())) {
            coord += "@" + config.getExecutionId();
        }

        // If the user provided a configuration (i.e. something modified compared to the default),
        // then mark the coordinate as modified.
        if (config.getRawConfiguration() != null && !config.getRawConfiguration().isEmpty()) {
            coord += " (overlay configuration used)";
        }

        return coord;
    }

    private boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    List<ExecutionConfig> getExecutions() {
        return executions;
    }

    public static class ExecutionConfig {
        @Parameter private String pluginCoordinates;

        @Parameter private String groupId;

        @Parameter private String artifactId;

        @Parameter private String version;

        @Parameter private String goal;

        @Parameter private String executionId;

        @Parameter private String id;

        @Parameter private Map<String, Object> configuration;

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

        public Map<String, Object> getRawConfiguration() {
            return configuration;
        }

        public void setRawConfiguration(Map<String, Object> configuration) {
            this.configuration = configuration;
        }

        public Xpp3Dom getConfiguration() {
            if (configurationDom == null && configuration != null) {
                configurationDom = convertToXpp3Dom(configuration);
            }
            return configurationDom;
        }

        public void setConfiguration(Xpp3Dom configuration) {
            this.configurationDom = configuration;
        }

        private Xpp3Dom convertToXpp3Dom(Map<String, Object> rawConfig) {
            Xpp3Dom dom = new Xpp3Dom("configuration");
            for (Map.Entry<String, Object> entry : rawConfig.entrySet()) {
                Xpp3Dom child = new Xpp3Dom(entry.getKey());
                child.setValue(entry.getValue().toString());
                dom.addChild(child);
            }
            return dom;
        }

        @Override
        public String toString() {
            return "ExecutionConfig{pluginCoordinates='"
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
                    + configuration
                    + "'}";
        }
    }
}

class Xpp3DomUtils {

    public static class ConfigurationConverter {
        public static Xpp3Dom toXpp3Dom(PlexusConfiguration configuration) {
            if (configuration == null) {
                return null;
            }
            Xpp3Dom dom = new Xpp3Dom(configuration.getName());
            // Set the value if any.
            String value = configuration.getValue(null);
            if (value != null) {
                dom.setValue(value);
            }
            // Copy attributes.
            String[] attributeNames = configuration.getAttributeNames();
            if (attributeNames != null) {
                for (String attr : attributeNames) {
                    String attrValue = configuration.getAttribute(attr, null);
                    if (attrValue != null) {
                        dom.setAttribute(attr, attrValue);
                    }
                }
            }
            // Recursively convert children.
            int childCount = configuration.getChildCount();
            for (int i = 0; i < childCount; i++) {
                PlexusConfiguration child = configuration.getChild(i);
                Xpp3Dom childDom = toXpp3Dom(child);
                dom.addChild(childDom);
            }
            return dom;
        }
    }
}
