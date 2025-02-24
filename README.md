# Seq Maven Plugin
A Maven plugin for running plugin executions sequentially with flexible configuration.

## What It Does

The seq-maven-plugin allows you to define and execute a sequence of Maven plugin goals in a specific order. It’s perfect for scenarios where you need to orchestrate multiple plugin executions with custom configurations, ensuring they run one after another.

## Key features

* Run any Maven plugin goal sequentially.
* Configure steps with plugin coordinates or shorthand identifiers.
* Merge default and custom configurations.
* Skip steps conditionally.
* Detailed logging with execution timing.

## How to Use It

### Installation

Add the plugin to your pom.xml:
```xml

<plugin>
    <groupId>io.github.qudtlib</groupId>
    <artifactId>seq-maven-plugin</artifactId>
    <version>[choose a version]</version>
</plugin>
```

### Example Configuration

Add an execution block to run plugin goals sequentially:
```xml

<plugin>
    <groupId>io.github.qudtlib</groupId>
    <artifactId>seq-maven-plugin</artifactId>
    <version>1.1-SNAPSHOT</version>
    <executions>
        <execution>
            <id>run-sequence</id>
            <phase>package</phase>
            <goals>
                <goal>run</goal>
            </goals>
            <configuration>
                <label>My Sequence</label>
                <steps>
                    <step>
                        <pluginCoordinates>spotless:check</pluginCoordinates>
                    </step>
                    <step>
                        <pluginCoordinates>org.apache.maven.plugins:maven-compiler-plugin:compile</pluginCoordinates>
                        <configuration>
                            <source>17</source>
                            <target>17</target>
                        </configuration>
                    </step>
                </steps>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Running It

Execute the plugin with:
```bash

mvn seq:run
```

Or bind it to a lifecycle phase (e.g., package) as shown above and run:
```bash

mvn package
```

### Configuration Options

    <steps>: List of <step> elements to execute.
    <pluginCoordinates>: Format as groupId:artifactId:goal[@executionId] or shorthand identifier:goal.
    <configuration>: Custom configuration for the step (merged with defaults).
    <skip>: Set to true to skip a step (default: false).
    <label>: Optional label for log output.

# License
Licensed under the Apache License 2.0 (LICENSE).