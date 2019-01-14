

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

/**
 * TODO: A failure flag that controls build failure if there are > 0 unaligned dependencies, also perhaps
 * distinguish between unaligned direct and unaligned transitive.
 * <p>
 * TODO: A support for an exludes file allows dependencies with unaligned dependencies to be ignored.
 */
@Mojo(name = "report", requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
public class AlignmentReporterMojo
        extends AbstractMojo
{
    private static final Comparator<Artifact> ARTIFACT_COMPARATOR = Comparator.comparing(artifact ->
                                                                                                 String.format("%s:%s",
                                                                                                               artifact.getGroupId(),
                                                                                                               artifact.getArtifactId()));

    // fields -----------------------------------------------------------------

    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    /**
     * If specified, this parameter will cause the dependency tree to be written to the path specified, instead of
     * writing to the console.
     */
    @Parameter(property = "outputFile")
    private File outputFile;

    /**
     * The scope to filter by when resolving the dependency tree, or <code>null</code> to include dependencies from all
     * scopes.
     */
    @Parameter(property = "scope")
    private String scope;

    /**
     * Whether to append outputs into the output file or overwrite it.
     */
    @Parameter(property = "appendOutput", defaultValue = "false")
    private boolean appendOutput;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    /**
     * Dependencies with have a version that satisfy this pattern are considered aligned.
     */
    @Parameter(property = "alignmentPattern", required = true)
    private Pattern alignmentPattern;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute()
            throws MojoExecutionException, MojoFailureException
    {
        if (isSkip())
        {
            getLog().info("Skipping plugin execution");
            return;
        }

        try
        {
            ArtifactFilter artifactFilter = createScopeResolvingArtifactFilter();

            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

            buildingRequest.setProject(project);

            Set<Artifact> reactorArtifacts =
                    reactorProjects.stream().map(p -> p.getArtifact()).collect(Collectors.toSet());

            Set<Artifact> dependencyArtifacts = getProject().getDependencyArtifacts()
                                                            .stream()
                                                            .filter(a -> !reactorArtifacts.contains(a))
                                                            .collect(Collectors.toSet());

            List<Artifact> alignedDirect = dependencyArtifacts.stream()
                                                              .filter(artifactFilter::include)
                                                              .filter(artifact -> alignmentPattern.matcher(artifact.getVersion())
                                                                                                  .find())

                                                              .sorted(ARTIFACT_COMPARATOR)
                                                              .collect(Collectors.toList());

            List<Artifact> unalignedDirect = dependencyArtifacts.stream()
                                                                .filter(artifactFilter::include)
                                                                .filter(artifact -> !alignmentPattern.matcher(artifact.getVersion())
                                                                                                     .find())
                                                                .sorted(ARTIFACT_COMPARATOR)
                                                                .collect(Collectors.toList());

            String alignedDirectStr = reportDirectDependencies(alignedDirect, "Aligned direct");

            String unalignedDirectStr = reportDirectDependencies(unalignedDirect, "Unaligned direct");

            DependencyNode rootNode =
                    dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter, reactorProjects);

            List<DependencyNode> alignedDirectDeps = rootNode.getChildren().stream()
                                                             .filter(node -> alignedDirect
                                                                     .contains(node.getArtifact()))
                                                             .collect(Collectors.toList());

            String alignedDirectWithUnalignedDeps =
                    reportAlignedDirectDependenciesWithUnalignedDependencySummary(alignedDirectDeps);

            String transitiveAlignment = reportUnalignedTransitiveDependencyDetail(alignedDirectDeps);

            if (outputFile != null)
            {
                String projectTitle = getProjectTitle();

                write(projectTitle, outputFile, this.appendOutput, getLog());
                write(alignedDirectStr, outputFile, true, getLog());
                write(unalignedDirectStr, outputFile, true, getLog());
                write(alignedDirectWithUnalignedDeps, outputFile, true, getLog());
                write(transitiveAlignment, outputFile, true, getLog());

                getLog().info(String.format("Wrote alignment report tree to: %s", outputFile));
            }
            else
            {
                log(alignedDirectStr, getLog());
                log(unalignedDirectStr, getLog());
                log(alignedDirectWithUnalignedDeps, getLog());
                log(transitiveAlignment, getLog());
            }
        }
        catch (DependencyGraphBuilderException exception)
        {
            throw new MojoExecutionException("Cannot build project dependency graph", exception);
        }
        catch (IOException exception)
        {
            throw new MojoExecutionException("Cannot serialise project dependency graph", exception);
        }
    }

    private String reportDirectDependencies(final List<Artifact> list, final String prefix) throws IOException
    {

        try (StringWriter out = new StringWriter(); PrintWriter writer = new PrintWriter(out))
        {

            String title =
                    String.format("%d %s direct dependenc%s", list.size(), prefix, list.size() == 1 ? "y" : "ies");
            writer.println(title);
            writer.println(StringUtils.repeat("-", title.length()));
            list.forEach(artifact -> {
                writer.println(String.format("%s - %s:%s:%s",
                                             prefix,
                                             artifact.getGroupId(),
                                             artifact.getArtifactId(),
                                             artifact.getVersion()));
            });
            writer.println();
            return out.toString();
        }
    }

    private String reportAlignedDirectDependenciesWithUnalignedDependencySummary(final List<DependencyNode> alignedDirectDeps)
            throws IOException
    {
        Map<DependencyNode, AtomicInteger> summary = new HashMap<>();

        alignedDirectDeps.forEach(node -> {

            node.accept(new DependencyNodeVisitor()
            {
                @Override
                public boolean visit(final DependencyNode dependencyNode)
                {
                    return true;
                }

                @Override
                public boolean endVisit(final DependencyNode dependencyNode)
                {
                    Artifact leaf = dependencyNode.getArtifact();
                    if (!AlignmentReporterMojo.this.alignmentPattern.matcher(leaf.getVersion()).find())
                    {

                        AtomicInteger counter = summary.computeIfAbsent(node, k -> new AtomicInteger());
                        counter.incrementAndGet();
                    }
                    return true;
                }
            });
        });

        ;
        try (StringWriter out = new StringWriter();
             PrintWriter writer = new PrintWriter(out))
        {
            if (!summary.isEmpty())
            {

                String title = "Summary - Aligned direct dependencies with unaligned transitive dependencies";
                writer.println(title);
                writer.println(StringUtils.repeat("-", title.length()));

                summary.entrySet().stream().forEach(
                        e -> {
                            Artifact a = e.getKey().getArtifact();
                            writer.println(
                                    String.format("Incompletely aligned - %s:%s:%s",
                                                  a.getGroupId(),
                                                  a.getArtifactId(),
                                                  a.getVersion()));
                        });

                writer.println();
            }
            return out.toString();
        }
    }

    private String reportUnalignedTransitiveDependencyDetail(final List<DependencyNode> alignedNodes) throws IOException
    {
        List<Deque<Artifact>> unalignedDeps = new ArrayList<>();

        alignedNodes.forEach(node -> createTransitiveDependenciesAlignmentReportForNode(unalignedDeps, node));

        try (StringWriter out = new StringWriter(); PrintWriter writer = new PrintWriter(out))
        {

            if (!unalignedDeps.isEmpty())
            {
                String title = "Detail - Aligned direct dependencies with unaligned transitive dependencies";
                writer.println(title);
                writer.println(StringUtils.repeat("-", title.length()));

                unalignedDeps.forEach(x -> {

                    writer.print("Unaligned transitive - ");
                    Iterator<Artifact> itr = x.iterator();
                    while (itr.hasNext())
                    {
                        Artifact a = itr.next();
                        writer.append(String.format("%s:%s:%s", a.getGroupId(), a.getArtifactId(), a.getVersion()));

                        if (itr.hasNext())
                        {
                            writer.print(" <- ");
                        }
                    }

                    writer.println();
                });

                writer.println();
            }

            return out.toString();
        }
    }

    private void createTransitiveDependenciesAlignmentReportForNode(final List<Deque<Artifact>> deps,
                                                                    final DependencyNode node)
    {
        node.accept(new DependencyNodeVisitor()
        {
            Deque<Artifact> stack = new ArrayDeque<>();

            @Override
            public boolean visit(final DependencyNode dependencyNode)
            {
                stack.push(dependencyNode.getArtifact());
                return true;
            }

            @Override
            public boolean endVisit(final DependencyNode dependencyNode)
            {
                Artifact leaf = dependencyNode.getArtifact();
                Deque<Artifact> copy = new ArrayDeque<>(stack);
                if (!AlignmentReporterMojo.this.alignmentPattern.matcher(leaf.getVersion()).find())
                {
                    deps.add(copy);
                }
                stack.pop();
                return true;
            }
        });
    }

    private String getProjectTitle()
    {
        String name = project.getName();
        String projectEyeCatcher = StringUtils.repeat("=", name.length());

        StringWriter writer = new StringWriter();

        writer.append(String.format("%s%n", projectEyeCatcher));
        writer.append(String.format("%s%n", name));
        writer.append(String.format("%s%n%n", projectEyeCatcher));

        return writer.toString();
    }

    // public methods ---------------------------------------------------------

    /**
     * Gets the Maven project used by this mojo.
     *
     * @return the Maven project
     */
    public MavenProject getProject()
    {
        return project;
    }

    /**
     * @return {@link #skip}
     */
    public boolean isSkip()
    {
        return skip;
    }


    @SuppressWarnings("unused")
    public void setAlignmentPattern(final String alignmentPattern)
    {
        this.alignmentPattern = Pattern.compile(alignmentPattern);
    }

    // private methods --------------------------------------------------------

    /**
     * Gets the artifact filter to use when resolving the dependency tree.
     *
     * @return the artifact filter
     */
    private ArtifactFilter createScopeResolvingArtifactFilter()
    {
        ArtifactFilter filter;

        // filter scope
        if (scope != null)
        {
            getLog().debug("+ Resolving dependency tree for scope '" + scope + "'");

            filter = new ScopeArtifactFilter(scope);
        }
        else
        {
            filter = artifact -> true;
        }

        return filter;
    }

    private static synchronized void write(String string, File file, boolean append, Log log)
            throws IOException
    {
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file, append))
        {
            writer.write(string);
        }
    }

    /**
     * Writes the specified string to the log at info level.
     *
     * @param string the string to write
     * @param log    where to log information.
     * @throws IOException if an I/O error occurs
     */
    private static synchronized void log(String string, Log log)
            throws IOException
    {
        try (BufferedReader reader = new BufferedReader(new StringReader(string)))
        {

            String line;

            while ((line = reader.readLine()) != null)
            {
                log.info(line);
            }
        }
    }
}
