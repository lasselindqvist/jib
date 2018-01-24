/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.builder;

import com.google.cloud.tools.crepecake.Timer;
import com.google.cloud.tools.crepecake.http.Authorization;
import com.google.cloud.tools.crepecake.image.DuplicateLayerException;
import com.google.cloud.tools.crepecake.image.Image;
import com.google.cloud.tools.crepecake.image.LayerCountMismatchException;
import com.google.cloud.tools.crepecake.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.crepecake.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.crepecake.image.json.JsonToImageTranslator;
import com.google.cloud.tools.crepecake.image.json.ManifestTemplate;
import com.google.cloud.tools.crepecake.image.json.V21ManifestTemplate;
import com.google.cloud.tools.crepecake.image.json.V22ManifestTemplate;
import com.google.cloud.tools.crepecake.json.JsonTemplateMapper;
import com.google.cloud.tools.crepecake.registry.RegistryClient;
import com.google.cloud.tools.crepecake.registry.RegistryException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

class PullBaseImageStep implements Callable<Image> {

  private static final String DESCRIPTION = "Pulling base image manifest";

  private final BuildConfiguration buildConfiguration;
  private final Future<Authorization> pullAuthorizationFuture;

  PullBaseImageStep(
      BuildConfiguration buildConfiguration, Future<Authorization> pullAuthorizationFuture) {
    this.buildConfiguration = buildConfiguration;
    this.pullAuthorizationFuture = pullAuthorizationFuture;
  }

  @Override
  public Image call()
      throws IOException, RegistryException, LayerPropertyNotFoundException,
          DuplicateLayerException, LayerCountMismatchException, ExecutionException,
          InterruptedException {
    try (Timer ignored = new Timer(buildConfiguration.getBuildLogger(), DESCRIPTION)) {
      RegistryClient registryClient =
          new RegistryClient(
              pullAuthorizationFuture.get(),
              buildConfiguration.getBaseImageServerUrl(),
              buildConfiguration.getBaseImageName());

      ManifestTemplate manifestTemplate =
          registryClient.pullManifest(buildConfiguration.getBaseImageTag());

      // TODO: Make schema version be enum.
      switch (manifestTemplate.getSchemaVersion()) {
        case 1:
          V21ManifestTemplate v21ManifestTemplate = (V21ManifestTemplate) manifestTemplate;
          return JsonToImageTranslator.toImage(v21ManifestTemplate);

        case 2:
          V22ManifestTemplate v22ManifestTemplate = (V22ManifestTemplate) manifestTemplate;

          ByteArrayOutputStream containerConfigurationOutputStream = new ByteArrayOutputStream();
          registryClient.pullBlob(
              v22ManifestTemplate.getContainerConfigurationDigest(),
              containerConfigurationOutputStream);
          String containerConfigurationString =
              new String(containerConfigurationOutputStream.toByteArray(), StandardCharsets.UTF_8);

          ContainerConfigurationTemplate containerConfigurationTemplate =
              JsonTemplateMapper.readJson(
                  containerConfigurationString, ContainerConfigurationTemplate.class);
          return JsonToImageTranslator.toImage(v22ManifestTemplate, containerConfigurationTemplate);
      }

      throw new IllegalStateException("Unknown manifest schema version");
    }
  }
}