package com.github.k_wall;/*
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.AndArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;

/**
 * This plugin tests a project's dependencies for 'version alignment' and produces a simple text based report.
 *
 * <p>Based ideas and code from the Maven Dependency Plugin project.</p>
 */
public abstract class AbstractAlignmentReporterMojo extends AbstractMojo
{
    private static final Comparator<Artifact> ARTIFACT_COMPARATOR = Comparator.comparing(Artifact::getGroupId)
            .thenComparing(Artifact::getArtifactId);
    private static final Comparator<DependencyNode> DEPENDENCY_COMPARATOR = Comparator.comparing(
            DependencyNode::getArtifact,
            ARTIFACT_COMPARATOR);
    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    protected List<MavenProject> reactorProjects;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    protected DependencyGraphBuilder dependencyGraphBuilder;

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
     * A flag to fail the build if alignment errors are detected
     */
    @Parameter(property = "failOnUnalignedDependencies", defaultValue = "false")
    private boolean failOnUnalignedDependencies;
    /**
     * Skip plugin execution completely.
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;
    /**
     * Dependencies with have a version that satisfy this pattern are considered aligned.
     */
    @Parameter(property = "alignmentPattern", required = true)
    private Pattern alignmentPattern;
    /**
     * A comma-separated list of artifacts to filter from the serialized dependency tree, or <code>null</code> not to
     * filter any artifacts from the dependency tree. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     *
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard.
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @see StrictPatternExcludesArtifactFilter
     */
    @Parameter( property = "excludes" )
    private String excludes;

    private ArtifactFilter scopeFilter;

    private static void write(String string, File file)
            throws IOException
    {
        write(string, file, true);
    }

    private static void write(String string, File file, boolean append)
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
    private static void log(String string, Log log)
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

        scopeFilter = createScopeResolvingArtifactFilter();
        ArtifactFilter excludeFilter = createExcludeFilter();

        Set<DependencyNode> directDependencies = getDirectDependencies(new AndArtifactFilter(Arrays.asList(scopeFilter, excludeFilter)));

        Set<Artifact> dependencyArtifacts = directDependencies.stream()
                                                              .map(DependencyNode::getArtifact)
                                                              .collect(Collectors.toSet());

        List<Artifact> alignedDirect = dependencyArtifacts.stream()
                                                          .filter(artifact -> alignmentPattern.matcher(artifact.getVersion())
                                                                                              .find())
                                                          .sorted(ARTIFACT_COMPARATOR)
                                                          .collect(Collectors.toList());

        List<Artifact> unalignedDirect = dependencyArtifacts.stream()
                                                            .filter(artifact -> !alignmentPattern.matcher(artifact.getVersion())
                                                                                                 .find())
                                                            .sorted(ARTIFACT_COMPARATOR)
                                                            .collect(Collectors.toList());

        List<DependencyNode> alignedDirectDeps = directDependencies.stream()
                                                            .filter(dn -> alignedDirect.contains(dn.getArtifact()))
                                                            .collect(Collectors.toList());

        DependencyNodeFilter excludeDependencyFilter = new ArtifactDependencyNodeFilter(excludeFilter);
        Set<Artifact> unalignedTransitives = getArtifactsWithUnalignedTransitives(alignedDirectDeps, excludeDependencyFilter);

        try
        {
            String alignedDirectStr = reportDirectDependencies(alignedDirect, "Aligned");
            String unalignedDirectStr = reportDirectDependencies(unalignedDirect, "Unaligned");

            String unalignedTransitivesStr = reportUnalignedTransitiveDependenciesSummary(unalignedTransitives);
            String unalignedTransitiveDetail = reportUnalignedTransitiveDependencyDetail(alignedDirectDeps, excludeDependencyFilter);

            if (outputFile != null)
            {
                String projectTitle = getProjectTitle();

                write(projectTitle, outputFile, this.appendOutput);
                write(alignedDirectStr, outputFile);
                write(unalignedDirectStr, outputFile);
                write(unalignedTransitivesStr, outputFile);
                write(unalignedTransitiveDetail, outputFile);

                getLog().info(String.format("Wrote alignment report tree to: %s", outputFile));
            }
            else
            {
                log(alignedDirectStr, getLog());
                log(unalignedDirectStr, getLog());
                log(unalignedTransitivesStr, getLog());
                log(unalignedTransitiveDetail, getLog());
            }
        }
        catch (IOException exception)
        {
            throw new MojoExecutionException("Cannot serialise project dependency graph", exception);
        }

        if (failOnUnalignedDependencies)
        {
            StringBuilder failureMessages = new StringBuilder();

            if (!unalignedDirect.isEmpty())
            {
                failureMessages.append(String.format("There %s %d unaligned direct dependenc%s",
                                                     unalignedDirect.size() == 1 ? "is" : "are",
                                                     unalignedDirect.size(),
                                                     unalignedDirect.size() == 1 ? "y" : "ies"));
            }

            if (!unalignedTransitives.isEmpty())
            {
                failureMessages.append(failureMessages.length() > 0 ? " and there" : "There");

                failureMessages.append(String.format(
                        " %s %d aligned direct dependenc%s with at least one unaligned transitive dependency",
                        unalignedTransitives.size() == 1 ? "is" : "are",
                        unalignedTransitives.size(),
                        unalignedTransitives.size() == 1 ? "y" : "ies"));
            }
            else if (failureMessages.length() > 0)
            {
                failureMessages.append(".");
            }

            if (failureMessages.length() > 0)
            {
                throw new MojoFailureException(failureMessages.toString());
            }
        }
    }

    protected Set<DependencyNode> getDirectDependencies(MavenProject reactorProject, ArtifactFilter artifactFilter)
            throws MojoExecutionException  {

        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        Artifact projectTestJar = new org.apache.maven.project.artifact.ProjectArtifact(reactorProject);
        projectTestJar.setScope("test");

        if (scopeFilter.include(projectTestJar)) {
            buildingRequest.setProject(reactorProject);
        } else {
            /*
             * When test-scoped dependencies are not scanned, create a proxy/shim project
             * that depends on the actual project. The graph for the project will be
             * generated, resulting in a tree that does not include the test dependencies
             * and accurately lists the resolved dependencies for non-test artifacts in the
             * tree. Without this proxy/shim, when a dependency is pulled by both a test and
             * a non-test artifact in the tree and there also exists a conflict between them
             * (e.g. same artifact, different version), the artifact may not appear in the
             * tree under the non-test dependency.
             */
            Dependency projectDependency = new Dependency();
            projectDependency.setGroupId(reactorProject.getGroupId());
            projectDependency.setArtifactId(reactorProject.getArtifactId());
            projectDependency.setVersion(reactorProject.getVersion());
            projectDependency.setType(reactorProject.getArtifact().getType());

            MavenProject proxy = new MavenProject();
            proxy.setArtifact(new org.apache.maven.project.artifact.ProjectArtifact(reactorProject));
            proxy.getModel().setDependencyManagement(reactorProject.getDependencyManagement());
            proxy.getDependencies().add(projectDependency);
            buildingRequest.setProject(proxy);
        }

        buildingRequest.setResolveDependencies(true);

        Set<Artifact> projectArtifacts = reactorProjects.stream().map(MavenProject::getArtifact).collect(Collectors.toSet());
        DependencyNode projectRoot;

        try {
            projectRoot = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter);
        } catch (DependencyGraphBuilderException e) {
            throw new MojoExecutionException("Cannot build project dependency graph", e);
        }

        Stream<DependencyNode> dependencyStream = projectRoot
                .getChildren()
                .stream();

        if (!scopeFilter.include(projectTestJar)) {
            /*
             * If a proxy project was used, we must map dependencies from an additional level
             * down in the graph.
             */
            dependencyStream = dependencyStream.flatMap(node -> node.getChildren().stream());
        }

        Set<DependencyNode> dependencies = dependencyStream
                .filter(node -> !projectArtifacts.contains(node.getArtifact()))
                .collect(Collectors.toSet());

        if (getLog().isDebugEnabled()) {
            for (DependencyNode dependency : dependencies.stream().sorted(DEPENDENCY_COMPARATOR).collect(Collectors.toList())) {
                getLog().debug(String.format("Project %s, found direct dependency %s", reactorProject.getArtifact(), dependency.getArtifact()));
            }
        }

        return dependencies;
    }

    /**
     * Returns the set of direct dependencies that are to be considered by the report.
     *
     * @param artifactFilter filter
     */
    protected abstract Set<DependencyNode> getDirectDependencies(final ArtifactFilter artifactFilter)
            throws MojoExecutionException;

    private String reportDirectDependencies(final List<Artifact> list, final String prefix) throws IOException
    {

        try (StringWriter out = new StringWriter(); PrintWriter writer = new PrintWriter(out))
        {
            String title =
                    String.format("%d %s direct dependenc%s", list.size(), prefix, list.size() == 1 ? "y" : "ies");
            writer.println(title);
            writer.println("-".repeat(title.length()));
            list.forEach(artifact -> writer.println(String.format("%s - %s", prefix, artifact)));
            writer.println();
            return out.toString();
        }
    }

    private String reportUnalignedTransitiveDependenciesSummary(final Set<Artifact> summary)
            throws IOException
    {
        try (StringWriter out = new StringWriter();
             PrintWriter writer = new PrintWriter(out))
        {
            if (!summary.isEmpty())
            {

                String title = "Summary - Aligned direct dependencies with unaligned transitive dependencies";
                writer.println(title);
                writer.println("-".repeat(title.length()));

                summary.stream()
                       .sorted(ARTIFACT_COMPARATOR)
                       .forEach(a -> writer.println(String.format("Incompletely aligned - %s", a)));

                writer.println();
            }
            return out.toString();
        }
    }

    private Set<Artifact> getArtifactsWithUnalignedTransitives(final List<DependencyNode> alignedDirectDeps,
                                                               final DependencyNodeFilter nodeFilter)
    {
        Set<Artifact> summary = new HashSet<>();

        alignedDirectDeps.forEach(node -> {
            node.accept(new FilteringDependencyNodeVisitor(new DependencyNodeVisitor()
            {
                Deque<Artifact> deque = new ArrayDeque<>();

                @Override
                public boolean visit(final DependencyNode dependencyNode)
                {
                    Artifact artifact = dependencyNode.getArtifact();
                    deque.addLast(artifact);
                    return true;
                }

                @Override
                public boolean endVisit(final DependencyNode dependencyNode)
                {
                    Artifact artifact = dependencyNode.getArtifact();
                    if (!alignmentPattern.matcher(artifact.getVersion()).find())
                    {
                        Artifact head = deque.getFirst();
                        summary.add(head);
                    }
                    deque.removeLast();
                    return true;
                }
            }, nodeFilter));
        });
        return summary;
    }

    private String reportUnalignedTransitiveDependencyDetail(final List<DependencyNode> alignedNodes,
                                                             final DependencyNodeFilter nodeFilter) throws IOException
    {
        List<List<Artifact>> unalignedDeps = new ArrayList<>();

        alignedNodes.forEach(node -> createTransitiveDependenciesAlignmentReportForNode(unalignedDeps, node, nodeFilter));

        try (StringWriter out = new StringWriter(); PrintWriter writer = new PrintWriter(out))
        {

            if (!unalignedDeps.isEmpty())
            {
                String title = "Detail - Aligned direct dependencies with unaligned transitive dependencies";
                writer.println(title);
                writer.println("-".repeat(title.length()));

                unalignedDeps.stream()
                    .distinct()
                    .sorted((e1, e2) -> ARTIFACT_COMPARATOR.compare(e1.get(0), e2.get(0)))
                    .forEach(x -> {

                    writer.print("Unaligned transitive - ");
                    Iterator<Artifact> itr = x.iterator();
                    while (itr.hasNext())
                    {
                        Artifact a = itr.next();
                        writer.append(a.toString());

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

    private void createTransitiveDependenciesAlignmentReportForNode(final List<List<Artifact>> deps,
                                                                    final DependencyNode node,
                                                                    final DependencyNodeFilter nodeFilter)
    {
        node.accept(new FilteringDependencyNodeVisitor(new DependencyNodeVisitor()
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
                if (!AbstractAlignmentReporterMojo.this.alignmentPattern.matcher(leaf.getVersion()).find())
                {
                    deps.add(new ArrayList<>(stack));
                }
                stack.pop();
                return true;
            }
        }, nodeFilter));
    }

    private String getProjectTitle()
    {
        String name = project.getName();
        String projectEyeCatcher = "=".repeat(name.length());

        StringWriter writer = new StringWriter();

        writer.append(String.format("%s%n", projectEyeCatcher));
        writer.append(String.format("%s%n", name));
        writer.append(String.format("%s%n%n", projectEyeCatcher));

        return writer.toString();
    }

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

    public void setAlignmentPattern(final String alignmentPattern)
    {
        this.alignmentPattern = Pattern.compile(alignmentPattern);
    }

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
            getLog().debug(String.format("+ Resolving dependency tree for scope '%s'", scope));

            filter = new ScopeArtifactFilter(scope);
        }
        else
        {
            filter = artifact -> true;
        }

        return filter;
    }

    private ArtifactFilter createExcludeFilter()
    {
        ArtifactFilter filter;
        // filter excludes
        if ( excludes != null )
        {
            List<String> patterns = Arrays.asList( excludes.split( "," ) );

            getLog().debug(String.format("+ Filtering dependency tree by artifact exclude patterns: %s", patterns));
            filter = new StrictPatternExcludesArtifactFilter( patterns );
        }
        else
        {
            filter = artifact -> true;
        }
        return filter;
    }
}
