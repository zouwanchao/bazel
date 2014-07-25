// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.ExecutorInitException;
import com.google.devtools.build.lib.actions.TestExecException;
import com.google.devtools.build.lib.blaze.BlazeRuntime;
import com.google.devtools.build.lib.buildtool.BuildRequest.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.buildtool.buildevent.BuildStartingEvent;
import com.google.devtools.build.lib.buildtool.buildevent.TestFilteringCompleteEvent;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.LoadingFailedException;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner.Callback;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner.LoadingResult;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.ExitCausingException;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.view.BuildInfoEvent;
import com.google.devtools.build.lib.view.BuildView;
import com.google.devtools.build.lib.view.BuildView.AnalysisResult;
import com.google.devtools.build.lib.view.ConfigurationsCreatedEvent;
import com.google.devtools.build.lib.view.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.view.ConfiguredTarget;
import com.google.devtools.build.lib.view.LicensesProvider;
import com.google.devtools.build.lib.view.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.view.MakeEnvironmentEvent;
import com.google.devtools.build.lib.view.ViewCreationFailedException;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.view.config.BuildConfigurationKey;
import com.google.devtools.build.lib.view.config.BuildOptions;
import com.google.devtools.build.lib.view.config.DefaultsPackage;
import com.google.devtools.build.lib.view.config.InvalidConfigurationException;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Provides the bulk of the implementation of the 'blaze build' command.
 *
 * <p>The various concrete build command classes handle the command options and request
 * setup, then delegate the handling of the request (the building of targets) to this class.
 *
 * <p>The main entry point is {@link #buildTargets}.
 *
 * <p>This class is always instantiated and managed as a singleton, being constructed and held by
 * {@link BlazeRuntime}. This is so multiple kinds of build commands can share this single
 * instance.
 *
 * <p>Most of analysis is handled in {@link BuildView}, and execution in {@link ExecutionTool}.
 */
public class BuildTool {

  private static final Logger LOG = Logger.getLogger(BuildTool.class.getName());

  protected final BlazeRuntime runtime;

  /**
   * Constructs a BuildTool.
   *
   * @param runtime a reference to the blaze runtime.
   */
  public BuildTool(BlazeRuntime runtime) {
    this.runtime = runtime;
  }

  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>Callers must ensure that {@link #stopRequest} is called after this method, even if it
   * throws.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets
   * fields of the request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *        options; during this method's execution, the actualTargets and successfulTargets fields
   *        of the request object are populated
   * @param result the build result that is the mutable result of this build
   * @param validator target validator
   */
  public void buildTargets(BuildRequest request, BuildResult result, TargetValidator validator)
      throws BuildFailedException, LocalEnvironmentException,
             InterruptedException, ViewCreationFailedException,
             TargetParsingException, LoadingFailedException, ExecutorInitException,
             ExitCausingException, InvalidConfigurationException, TestExecException {
    validateOptions(request);
    BuildOptions buildOptions = runtime.createBuildOptions(request);
    // Sync the package manager before sending the BuildStartingEvent in runLoadingPhase()
    runtime.setupPackageCache(request.getPackageCacheOptions(),
        DefaultsPackage.getDefaultsPackageContent(buildOptions));

    ExecutionTool executionTool = null;
    LoadingResult loadingResult = null;
    BuildConfigurationCollection configurations = null;
    try {
      getEventBus().post(new BuildStartingEvent(runtime.getOutputFileSystem(), request));
      LOG.info("Build identifier: " + request.getId());
      executionTool = new ExecutionTool(runtime, request);
      if (needsExecutionPhase(request.getBuildOptions())) {
        // Initialize the execution tool early if we need it. This hides the latency of setting up
        // the execution backends.
        executionTool.init();
      }

      // Loading phase.
      loadingResult = runLoadingPhase(request, validator);

      // Create the build configurations.
      if (!request.getMultiCpus().isEmpty()) {
        getReporter().warn(null,
            "The --experimental_multi_cpu option is _very_ experimental and only intended for "
            + "internal testing at this time. If you do not work on the build tool, then you "
            + "should stop now!");
        if (!"build".equals(request.getCommandName()) && !"test".equals(request.getCommandName())) {
          throw new InvalidConfigurationException(
              "The experimental setting to select multiple CPUs is only supported for 'build' and "
              + "'test' right now!");
        }
      }
      configurations = getConfigurations(
          runtime.getBuildConfigurationKey(buildOptions, request.getMultiCpus()),
          request.getViewOptions().keepGoing);

      getEventBus().post(new ConfigurationsCreatedEvent(configurations));
      runtime.throwPendingException();
      if (configurations.getTargetConfigurations().size() == 1) {
        // TODO(bazel-team): This is not optimal - we retain backwards compatibility in the case
        // where there's only a single configuration, but we don't send an event in the multi-config
        // case. Can we do better? [multi-config]
        getEventBus().post(new MakeEnvironmentEvent(
            configurations.getTargetConfigurations().get(0).getMakeEnvironment()));
      }
      LOG.info("Configurations created");

      // Analysis phase.
      AnalysisResult analysisResult = runAnalysisPhase(request, loadingResult, configurations);
      result.setActualTargets(analysisResult.getTargetsToBuild());
      result.setTestTargets(analysisResult.getTargetsToTest());

      reportTargets(analysisResult);

      // Execution phase.
      runExecutionPhase(request, loadingResult, analysisResult, result, executionTool,
          configurations);

      String delayedErrorMsg = analysisResult.getError();
      if (delayedErrorMsg != null) {
        throw new BuildFailedException(delayedErrorMsg);
      }
    } catch (RuntimeException e) {
      // Print an error message for unchecked runtime exceptions. This does not concern Error
      // subclasses such as OutOfMemoryError.
      request.getOutErr().printErrLn("Unhandled exception thrown during build; message: " +
          e.getMessage());
      throw e;
    } finally {
      if (executionTool != null) {
        executionTool.shutdown();
      }
      // The workspace status actions will not run with certain flags, or if an error
      // occurs early in the build. Tell a lie so that the event is not missing.
      // If multiple build_info events are sent, only the first is kept, so this does not harm
      // successful runs (which use the workspace status action).
      getEventBus().post(new BuildInfoEvent(
          runtime.getworkspaceStatusActionFactory().createDummyWorkspaceStatus()));
    }

    if (loadingResult != null && loadingResult.hasTargetPatternError()) {
      throw new BuildFailedException("execution phase successful, but there were errors " +
                                     "parsing the target pattern");
    }
  }

  private void reportExceptionError(Exception e) {
    if (e.getMessage() != null) {
      getReporter().error(null, e.getMessage());
    }
  }
  /**
   * The crux of the build system. Builds the targets specified in the request using the specified
   * Executor.
   *
   * <p>Performs loading, analysis and execution for the specified set of targets, honoring the
   * configuration options in the BuildRequest. Returns normally iff successful, throws an exception
   * otherwise.
   *
   * <p>The caller is responsible for setting up and syncing the package cache.
   *
   * <p>During this function's execution, the actualTargets and successfulTargets
   * fields of the request object are set.
   *
   * @param request the build request that this build tool is servicing, which specifies various
   *        options; during this method's execution, the actualTargets and successfulTargets fields
   *        of the request object are populated
   * @param validator target validator
   * @return the result as a {@link BuildResult} object
   */
  public BuildResult processRequest(BuildRequest request, TargetValidator validator) {
    BuildResult result = new BuildResult(request.getStartTime());
    runtime.getEventBus().register(result);
    Throwable catastrophe = null;
    ExitCode exitCode = ExitCode.BLAZE_INTERNAL_ERROR;
    try {
      buildTargets(request, result, validator);
      exitCode = ExitCode.SUCCESS;
    } catch (BuildFailedException e) {
      if (e.isErrorAlreadyShown()) {
        // The actual error has already been reported by the Builder.
      } else {
        reportExceptionError(e);
      }
      if (e.isCatastrophic()) {
        result.setCatastrophe();
      }
      exitCode = ExitCode.BUILD_FAILURE;
    } catch (InterruptedException e) {
      exitCode = ExitCode.INTERRUPTED;
      getReporter().error(null, "build interrupted");
      getEventBus().post(new BuildInterruptedEvent());
    } catch (TargetParsingException | LoadingFailedException | ViewCreationFailedException e) {
      exitCode = ExitCode.PARSING_FAILURE;
      reportExceptionError(e);
    } catch (TestExecException e) {
      // ExitCode.SUCCESS means that build was successful. Real return code of program
      // is going to be calculated in TestCommand.doTest().
      exitCode = ExitCode.SUCCESS;
      reportExceptionError(e);
    } catch (InvalidConfigurationException e) {
      exitCode = ExitCode.COMMAND_LINE_ERROR;
      reportExceptionError(e);
    } catch (ExitCausingException e) {
      exitCode = e.getExitCode();
      reportExceptionError(e);
      result.setCatastrophe();
    } catch (Throwable throwable) {
      catastrophe = throwable;
      Throwables.propagate(throwable);
    } finally {
      stopRequest(request, result, catastrophe, exitCode);
    }

    return result;
  }

  protected final BuildConfigurationCollection getConfigurations(
      BuildConfigurationKey key, boolean keepGoing)
      throws InvalidConfigurationException, InterruptedException {
    SkyframeExecutor executor = runtime.getSkyframeExecutor();
    // TODO(bazel-team): consider a possibility of moving ConfigurationFactory construction into
    // skyframe.
    return executor.createConfigurations(keepGoing, runtime.getConfigurationFactory(), key);
  }

  @VisibleForTesting
  protected final LoadingResult runLoadingPhase(final BuildRequest request,
                                                final TargetValidator validator)
          throws LoadingFailedException, TargetParsingException, InterruptedException,
          ExitCausingException {
    Profiler.instance().markPhase(ProfilePhase.LOAD);
    runtime.throwPendingException();

    final boolean keepGoing = request.getViewOptions().keepGoing;

    Callback callback = new Callback() {
      @Override
      public void notifyTargets(Collection<Target> targets) throws LoadingFailedException {
        if (validator != null) {
          validator.validateTargets(targets, keepGoing);
        }
      }

      @Override
      public void notifyVisitedPackages(Set<PathFragment> visitedPackages) {
        runtime.getSkyframeExecutor().updateLoadedPackageSet(visitedPackages);
      }
    };

    LoadingResult result = runtime.getLoadingPhaseRunner().execute(getReporter(),
        getEventBus(), request.getTargets(), request.getLoadingOptions(),
        runtime.createBuildOptions(request).getAllLabels(), keepGoing,
        request.shouldRunTests(), callback);
    runtime.throwPendingException();
    return result;
  }

  private void runExecutionPhase(BuildRequest request, LoadingResult loadingResult,
      AnalysisResult analysisResult, BuildResult result, @Nullable ExecutionTool executionTool,
      BuildConfigurationCollection configurations)
      throws TestExecException, BuildFailedException, InterruptedException, ExitCausingException,
      ViewCreationFailedException {
    try {
      if (needsExecutionPhase(request.getBuildOptions())) {
        executionTool.executeBuild(loadingResult, analysisResult, result,
            runtime.getSkyframeExecutor(), configurations);
      }
    } finally {
      if (runtime.getSkyframeExecutor() != null) {
        runtime.getSkyframeExecutor().deleteOldNodes();
      }
    }
  }

  /**
   * Performs the initial phases 0-2 of the build: Setup, Loading and Analysis.
   * <p>
   * Postcondition: On success, populates the BuildRequest's set of targets to
   * build.
   *
   * @return null if loading / analysis phases were successful; a useful error
   *         message if loading or analysis phase errors were encountered and
   *         request.keepGoing.
   * @throws InterruptedException if the current thread was interrupted.
   * @throws ViewCreationFailedException if analysis failed for any reason.
   */
  private AnalysisResult runAnalysisPhase(BuildRequest request, LoadingResult loadingResult,
      BuildConfigurationCollection configurations)
      throws InterruptedException, ViewCreationFailedException {
    Stopwatch timer = Stopwatch.createStarted();
    if (!request.getBuildOptions().performAnalysisPhase) {
      getReporter().progress(null, "Loading complete.");
      LOG.info("No analysis requested, so finished");
      return AnalysisResult.EMPTY;
    }

    getReporter().progress(null, "Loading complete.  Analyzing...");
    Profiler.instance().markPhase(ProfilePhase.ANALYZE);

    AnalysisResult analysisResult = getView().update(request.getId(), loadingResult, configurations,
        request.getViewOptions(), request.getTopLevelArtifactContext(), getReporter(),
        getEventBus());

    // TODO(bazel-team): Merge these into one event.
    getEventBus().post(new AnalysisPhaseCompleteEvent(analysisResult.getTargetsToBuild(),
        getView().getTargetsVisited(), timer.stop().elapsed(TimeUnit.MILLISECONDS)));
    getEventBus().post(new TestFilteringCompleteEvent(analysisResult.getTargetsToBuild(),
        analysisResult.getTargetsToTest()));

    for (BuildConfiguration targetConfiguration : configurations.getTargetConfigurations()) {
      if (request.shouldRunTests()) {
        // If blaze was asked to run tests, check whether we know that the host machine can run
        // them, and give a warning if not.
        if (!targetConfiguration.canRunOn(runtime.getHostMachineSpecification())) {
          // We say "may fail", because they might be interpreted programs.  Still,
          // not the most sensible use-case.
          getReporter().warn(null, "Your system, " + runtime.getHostMachineSpecification() + ", is "
              + "not known to be capable of running binaries for "
              + targetConfiguration.getMnemonic());
        }
      }
    }

    // Check licenses.
    // We check licenses if the first target configuration has license checking enabled. Right now,
    // it is not possible to have multiple target configurations with different settings for this
    // flag, which allows us to take this short cut.
    boolean checkLicenses = configurations.getTargetConfigurations().get(0).checkLicenses();
    if (checkLicenses) {
      Profiler.instance().markPhase(ProfilePhase.LICENSE);
      validateLicensingForTargets(analysisResult.getTargetsToBuild(),
          request.getViewOptions().keepGoing);
    }

    // Free memory by removing cache entries that aren't going to be needed.
    if (request.getViewOptions().discardAnalysisCache) {
      getView().clearAnalysisCache(analysisResult.getTargetsToBuild());
    }

    return analysisResult;
  }

  private static boolean needsExecutionPhase(BuildRequestOptions options) {
    if (!options.performAnalysisPhase) {
      return false;
    }

    return options.performExecutionPhase ||
           options.dumpTargets != null ||
           options.dumpMakefile ||
           options.dumpActionGraph;
  }

  /**
   * Stops processing the specified request.
   *
   * <p>This logs the build result, cleans up and stops the clock.
   *
   * @param request the build request that this build tool is servicing
   * @param crash Any unexpected RuntimeException or Error. May be null
   * @param exitCondition A suggested exit condition from either the build logic or
   *        a thrown exception somewhere along the way.
   */
  public void stopRequest(BuildRequest request, BuildResult result, Throwable crash,
      ExitCode exitCondition) {
    Preconditions.checkState((crash == null) || (exitCondition != ExitCode.SUCCESS));
    result.setUnhandledThrowable(crash);
    result.setExitCondition(exitCondition);
    // The stop time has to be captured before we send the BuildCompleteEvent.
    result.setStopTime(runtime.getClock().currentTimeMillis());
    getEventBus().post(new BuildCompleteEvent(request, result));
  }

  private void reportTargets(AnalysisResult analysisResult) {
    Collection<ConfiguredTarget> targetsToBuild = analysisResult.getTargetsToBuild();
    Collection<ConfiguredTarget> targetsToTest = analysisResult.getTargetsToTest();
    if (targetsToTest != null) {
      int testCount = targetsToTest.size();
      int targetCount = targetsToBuild.size() - testCount;
      if (targetCount == 0) {
        getReporter().info(null, "Found "
            + testCount + (testCount == 1 ? " test target..." : " test targets..."));
      } else {
        getReporter().info(null, "Found "
            + targetCount + (targetCount == 1 ? " target and " : " targets and ")
            + testCount + (testCount == 1 ? " test target..." : " test targets..."));
      }
    } else {
      int targetCount = targetsToBuild.size();
      getReporter().info(null, "Found "
          + targetCount + (targetCount == 1 ? " target..." : " targets..."));
    }
  }

  /**
   * Validates the options for this BuildRequest.
   *
   * <p>Issues warnings for the use of deprecated options, and warnings or errors for any option
   * settings that conflict.
   */
  @VisibleForTesting
  public void validateOptions(BuildRequest request) throws InvalidConfigurationException {
    for (String issue : request.validateOptions()) {
      getReporter().warn(null, issue);
    }
  }

  /**
   * Takes a set of configured targets, and checks if the distribution methods
   * declared for the targets are compatible with the constraints imposed by
   * their prerequisites' licenses.
   *
   * @param configuredTargets the targets to check
   * @param keepGoing if false, and a licensing error is encountered, both
   *        generates an error message on the reporter, <em>and</em> throws an
   *        exception. If true, then just generates a message on the reporter.
   * @throws ViewCreationFailedException if the license checking failed (and not
   *         --keep_going)
   */
  private void validateLicensingForTargets(Iterable<ConfiguredTarget> configuredTargets,
      boolean keepGoing) throws ViewCreationFailedException {
    for (ConfiguredTarget configuredTarget : configuredTargets) {
      final Target target = configuredTarget.getTarget();

      if (TargetUtils.isTestRule(target)) {
        continue;  // Tests are exempt from license checking
      }

      final Set<DistributionType> distribs = target.getDistributions();
      BuildConfiguration config = configuredTarget.getConfiguration();
      boolean staticallyLinked = (config != null) && config.performsStaticLink();
      staticallyLinked |= (config != null) && (target instanceof Rule)
          && ((Rule) target).getRuleClassObject().hasAttr("linkopts", Type.STRING_LIST)
          && ConfiguredAttributeMapper.of((Rule) target, configuredTarget.getConfiguration())
              .get("linkopts", Type.STRING_LIST).contains("-static");

      LicensesProvider provider = configuredTarget.getProvider(LicensesProvider.class);
      if (provider != null) {
        NestedSet<TargetLicense> licenses = provider.getTransitiveLicenses();
        for (TargetLicense targetLicense : licenses) {
          if (!targetLicense.getLicense().checkCompatibility(
              distribs, target, targetLicense.getLabel(), getReporter(), staticallyLinked)) {
            if (!keepGoing) {
              throw new ViewCreationFailedException("Build aborted due to licensing error");
            }
          }
        }
      } else if (configuredTarget.getTarget() instanceof InputFile) {
        // Input file targets do not provide licenses because they do not
        // depend on the rule where their license is taken from. This is usually
        // not a problem, because the transitive collection of licenses always
        // hits the rule they come from, except when the input file is a
        // top-level target. Thus, we need to handle that case specially here.
        //
        // See FileTarget#getLicense for more information about the handling of
        // license issues with File targets.
        License license = configuredTarget.getTarget().getLicense();
        if (!license.checkCompatibility(distribs, target, configuredTarget.getLabel(),
            getReporter(), staticallyLinked)) {
          if (!keepGoing) {
            throw new ViewCreationFailedException("Build aborted due to licensing error");
          }
       }
      }
    }
  }

  public BuildView getView() {
    return runtime.getView();
  }

  private Reporter getReporter() {
    return runtime.getReporter();
  }

  private EventBus getEventBus() {
    return runtime.getEventBus();
  }
}
