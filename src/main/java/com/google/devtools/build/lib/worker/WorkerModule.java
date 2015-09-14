// Copyright 2015 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionContextConsumer;
import com.google.devtools.build.lib.actions.ActionContextProvider;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsBase;

import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;

import java.io.IOException;

/**
 * A module that adds the WorkerActionContextProvider to the available action context providers.
 */
public class WorkerModule extends BlazeModule {
  private BlazeRuntime blazeRuntime;
  private BuildRequest buildRequest;
  private WorkerPool workers;
  private boolean verbose;

  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return command.builds()
        ? ImmutableList.<Class<? extends OptionsBase>>of(WorkerOptions.class)
        : ImmutableList.<Class<? extends OptionsBase>>of();
  }

  @Override
  public void beforeCommand(BlazeRuntime blazeRuntime, Command command) {
    this.blazeRuntime = Preconditions.checkNotNull(blazeRuntime);
    blazeRuntime.getEventBus().register(this);

    if (workers == null) {
      Path logDir = blazeRuntime.getOutputBase().getRelative("worker-logs");
      try {
        logDir.createDirectory();
      } catch (IOException e) {
        blazeRuntime
            .getReporter()
            .handle(Event.error("Could not create directory for worker logs: " + logDir));
      }

      GenericKeyedObjectPoolConfig config = new GenericKeyedObjectPoolConfig();
      config.setTimeBetweenEvictionRunsMillis(10 * 1000);

      workers = new WorkerPool(new WorkerFactory(), config);
      workers.setReporter(blazeRuntime.getReporter());
      workers.setLogDirectory(logDir);
    }
  }

  @Subscribe
  public void buildStarting(BuildStartingEvent event) {
    Preconditions.checkNotNull(workers);

    this.buildRequest = event.getRequest();

    WorkerOptions options = buildRequest.getOptions(WorkerOptions.class);
    workers.setMaxTotalPerKey(options.workerMaxInstances);
    workers.setMaxIdlePerKey(options.workerMaxInstances);
    workers.setMinIdlePerKey(options.workerMaxInstances);
    workers.setVerbose(options.workerVerbose);
    this.verbose = options.workerVerbose;
  }

  @Override
  public Iterable<ActionContextProvider> getActionContextProviders() {
    Preconditions.checkNotNull(blazeRuntime);
    Preconditions.checkNotNull(buildRequest);
    Preconditions.checkNotNull(workers);

    return ImmutableList.<ActionContextProvider>of(
        new WorkerActionContextProvider(
            blazeRuntime, buildRequest, workers, blazeRuntime.getEventBus()));
  }

  @Override
  public Iterable<ActionContextConsumer> getActionContextConsumers() {
    return ImmutableList.<ActionContextConsumer>of(new WorkerActionContextConsumer());
  }

  @Subscribe
  public void buildComplete(BuildCompleteEvent event) {
    if (workers != null && buildRequest.getOptions(WorkerOptions.class).workerQuitAfterBuild) {
      if (verbose) {
        blazeRuntime
            .getReporter()
            .handle(Event.info("Build completed, shutting down worker pool..."));
      }
      workers.close();
      workers = null;
    }
  }

  // Kill workers on Ctrl-C to quickly end the interrupted build.
  // TODO(philwo) - make sure that this actually *kills* the workers and not just politely waits
  // for them to finish.
  @Subscribe
  public void buildInterrupted(BuildInterruptedEvent event) {
    if (workers != null) {
      if (verbose) {
        blazeRuntime
            .getReporter()
            .handle(Event.info("Build interrupted, shutting down worker pool..."));
      }
      workers.close();
      workers = null;
    }
  }

  @Override
  public void afterCommand() {
    this.blazeRuntime = null;
    this.buildRequest = null;
  }
}