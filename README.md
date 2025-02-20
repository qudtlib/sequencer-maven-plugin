# Maven Sequencer Plugin

**Simplify your build by executing multiple Maven plugin goals in a defined, sequential order.**  
The Maven Sequencer Plugin lets you specify a list of plugin executions (with full or shorthand coordinates) and runs them one after the other with all required configuration and dependency injections intact. This means you can chain together plugins—whether standard ones like Spotless or custom plugins—without worrying about wiring issues or configuration defaults being lost.

---

## Quick Start

Add the plugin to your project's POM, and define the sequence of executions you need. For example, to first check code formatting with Spotless and then run an echo plugin, your configuration might look like this:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.github.qudtlib</groupId>
      <artifactId>sequencer-maven-plugin</artifactId>
      <version>1.0-SNAPSHOT</version>
      <executions>
        <execution>
          <id>run-sequence</id>
          <phase>validate</phase>
          <goals>
            <goal>sequence</goal>
          </goals>
          <configuration>
            <executions>
              <!-- Execute Spotless check -->
              <execution>
                <id>check-formatting</id>
                <pluginCoordinates>spotless:check</pluginCoordinates>
              </execution>
              <!-- Execute echo plugin (for demonstration) -->
              <execution>
                <id>echo-message</id>
                <pluginCoordinates>com.github.ekryd.echo-maven-plugin:echo-maven-plugin:echo</pluginCoordinates>
                <configuration>
                  <message>Hello, world!</message>
                </configuration>
              </execution>
            </executions>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

When you run your Maven build (for example, with mvn validate), the Sequencer plugin will:

1. Resolve each plugin's coordinates and merge any default configuration from the plugin descriptor with your custom overlay.
2. Log a clear, concise message showing which plugin execution is running.
3. Execute each plugin goal sequentially, ensuring that all parameters (such as project, baseDir, repositories, etc.) are properly injected by Maven’s container.

Why Use Maven Sequencer Plugin?

* Control Execution Order: Some plugin goals must run in a specific order. The Sequencer enforces that order reliably.
* Simplified Configuration: Use short or full coordinate formats as needed, and overlay configuration without losing default values.
* Transparent Logging: Easily see which plugin (and even which execution) is running, with visual markers if the configuration was modified.
* Robust Integration: Leverages Maven’s own plugin management system, ensuring that target plugins are wired correctly and run as if invoked directly by Maven.

##Detailed Usage
###Defining Executions

Within the <configuration> element of your Sequencer plugin, you define an <executions> list. Each execution may use either:

* Short Form: For standard plugins (e.g. spotless:check), or
* Long Form: For custom or ambiguous plugins (e.g. com.github.ekryd.echo-maven-plugin:echo-maven-plugin:echo).

You can also include an execution id by using the @ syntax (for example, echo:echo@strict) or by configuring it separately.

### Configuration Merging

The plugin reads the default configuration from the target plugin’s descriptor and merges it with your user-specified overlay. If any overlay is used, this fact is reflected in the log output. This merging ensures that all required parameters (such as baseDir, buildDir, and others) are properly set.

### Logging

Before each execution, the plugin logs the effective coordinate for the target plugin:

1. Short form if the plugin follows common naming conventions.
2. Long form if necessary.
3. An appended execution id (if provided) and a note if overlay configuration is used.

## Requirements

* Maven Version: Tested with Maven 3.9.8
* JDK: Java 17 or later

## Troubleshooting

### Missing Defaults / Wiring Issues:
* If you encounter errors regarding missing or null parameters (for example, a required parameter like “level” in the echo plugin), verify that your overlay configuration is not inadvertently overriding the plugin descriptor defaults. Use Maven’s debug logging (mvn -X) to inspect how configurations are merged.

* Dependency Conflicts: Run mvn help:effective-pom and mvn dependency:tree to ensure that your dependency versions are consistent with Maven 3.9.8.

## Conclusion

The Maven Sequencer Plugin offers a straightforward way to run multiple Maven plugin goals in a controlled sequence, preserving all the wiring and default injections you’d normally expect in a standard Maven lifecycle. Its flexible coordinate formatting and transparent configuration merging help keep your build process clear and manageable.

If you have questions or run into issues, please open an issue on GitHub.

Happy building!