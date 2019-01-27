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

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

@SuppressWarnings("unused")
@Mojo(name = "aggregate-report", aggregator = true, requiresDependencyCollection = ResolutionScope.TEST, threadSafe =
        true)
public class AggregateAlignmentReporterMojo extends AbstractAlignmentReporterMojo
{
    @Override
    protected Set<DependencyNode> getDirectDependencies(final ArtifactFilter artifactFilter)
            throws DependencyGraphBuilderException
    {
        Set<Artifact> reactorArtifacts =
                reactorProjects.stream().map(MavenProject::getArtifact).collect(Collectors.toSet());

        Set<Artifact> artifacts = reactorProjects.stream().map(p -> p.getDependencyArtifacts().stream())
                                                 .flatMap(Stream::distinct)
                                                 .filter(a -> !reactorArtifacts.contains(a))
                                                 .collect(Collectors.toSet());

        Set<DependencyNode> all = new HashSet<>();

        for (MavenProject reactorProject : reactorProjects)
        {
            ProjectBuildingRequest buildingRequest =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(reactorProject);

            DependencyNode rootNode =
                    dependencyGraphBuilder.buildDependencyGraph(buildingRequest, artifactFilter, reactorProjects);

            Set<DependencyNode> dns = rootNode.getChildren().stream()
                                              .filter(node -> artifacts.contains(node.getArtifact()))
                                              .collect(Collectors.toSet());
            all.addAll(dns);
        }

        return all;
    }
}
