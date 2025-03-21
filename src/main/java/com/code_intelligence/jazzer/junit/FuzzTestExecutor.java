/*
 * Copyright 2024 Code Intelligence GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.code_intelligence.jazzer.junit;

import static com.code_intelligence.jazzer.junit.ApiStatsHolder.printApiStats;
import static com.code_intelligence.jazzer.junit.Utils.durationStringToSeconds;
import static com.code_intelligence.jazzer.junit.Utils.generatedCorpusPath;
import static com.code_intelligence.jazzer.junit.Utils.inputsDirectoryResourcePath;
import static com.code_intelligence.jazzer.junit.Utils.inputsDirectorySourcePath;

import com.code_intelligence.jazzer.agent.AgentInstaller;
import com.code_intelligence.jazzer.driver.FuzzTargetHolder;
import com.code_intelligence.jazzer.driver.FuzzTargetRunner;
import com.code_intelligence.jazzer.driver.Opt;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.platform.commons.support.AnnotationSupport;

class FuzzTestExecutor {
  private static final AtomicBoolean hasBeenPrepared = new AtomicBoolean();
  private static final AtomicBoolean agentInstalled = new AtomicBoolean(false);

  private final List<String> libFuzzerArgs;
  private final Optional<Path> javaSeedsDir;

  private FuzzTestExecutor(List<String> libFuzzerArgs, Optional<Path> javaSeedsDir) {
    this.libFuzzerArgs = libFuzzerArgs;
    this.javaSeedsDir = javaSeedsDir;
  }

  public static FuzzTestExecutor prepare(
      ExtensionContext context, String maxDuration, long maxRuns, Optional<Path> dictionaryPath)
      throws IOException {
    if (!hasBeenPrepared.compareAndSet(false, true)) {
      throw new FuzzTestConfigurationError(
          "FuzzTestExecutor#prepare can only be called once per test run");
    }

    List<ArgumentsSource> allSources =
        AnnotationSupport.findRepeatableAnnotations(
            context.getRequiredTestMethod(), ArgumentsSource.class);
    // Non-empty as it always contains FuzzingArgumentsProvider.
    ArgumentsSource lastSource = allSources.get(allSources.size() - 1);
    // Ensure that our ArgumentsProviders run last so that we can record all the seeds generated by
    // user-provided ones.
    if (lastSource.value().getPackage() != FuzzTestExecutor.class.getPackage()) {
      throw new FuzzTestConfigurationError(
          "@FuzzTest must be the last annotation on a fuzz test,"
              + " but it came after the (meta-)annotation "
              + lastSource);
    }

    List<String> originalLibFuzzerArgs = Utils.getLibFuzzerArgs(context);
    String argv0 = originalLibFuzzerArgs.isEmpty() ? "fake_argv0" : originalLibFuzzerArgs.remove(0);

    ArrayList<String> libFuzzerArgs = new ArrayList<>();
    libFuzzerArgs.add(argv0);

    // Add passed in corpus directories (and files) at the beginning of the arguments list.
    // libFuzzer uses the first directory to store discovered inputs, whereas all others are
    // only used to provide additional seeds and aren't written into.
    List<String> corpusFilesOrDirs = Utils.getCorpusFilesOrDirs(context);
    originalLibFuzzerArgs.removeAll(corpusFilesOrDirs);
    libFuzzerArgs.addAll(corpusFilesOrDirs);

    // When reproducing individual inputs, we must not add any corpus directories to the command
    // line or libFuzzer will fail with "Not a directory: ...; exiting".
    Optional<Path> javaSeedsDir;
    if (!corpusFilesOrDirs.isEmpty()
        && corpusFilesOrDirs.stream().map(Paths::get).allMatch(Files::isRegularFile)) {
      javaSeedsDir = Optional.empty();
    } else {
      // Only create the default generated corpus directory if it is used as the generated corpus
      // directory, i.e., if the user didn't provide any custom corpus directories that come before
      // it on the libFuzzer command line.
      boolean createDefaultGeneratedCorpusDir = corpusFilesOrDirs.isEmpty();
      javaSeedsDir =
          Optional.of(addInputAndSeedDirs(context, libFuzzerArgs, createDefaultGeneratedCorpusDir));
    }

    dictionaryPath.ifPresent(s -> libFuzzerArgs.add("-dict=" + s));

    libFuzzerArgs.add("-max_total_time=" + durationStringToSeconds(maxDuration));
    if (maxRuns > 0) {
      libFuzzerArgs.add("-runs=" + maxRuns);
    }
    // Disable libFuzzer's out of memory detection: It is only useful for native library fuzzing,
    // which we don't support without our native driver, and leads to false positives where it picks
    // up IntelliJ's memory usage.
    libFuzzerArgs.add("-rss_limit_mb=0");
    if (Utils.permissivelyParseBoolean(
        context.getConfigurationParameter("jazzer.valueprofile").orElse("false"))) {
      libFuzzerArgs.add("-use_value_profile=1");
    }

    translateJUnitTimeoutToLibFuzzerFlag(context).ifPresent(libFuzzerArgs::add);

    // Prefer original libFuzzerArgs set via command line by appending them last.
    libFuzzerArgs.addAll(originalLibFuzzerArgs);

    return new FuzzTestExecutor(libFuzzerArgs, javaSeedsDir);
  }

  private static Optional<String> translateJUnitTimeoutToLibFuzzerFlag(ExtensionContext context) {
    return Stream.<Supplier<Optional<Long>>>of(
            () ->
                AnnotationSupport.findAnnotation(context.getRequiredTestMethod(), Timeout.class)
                    .map(timeout -> timeout.unit().toSeconds(timeout.value())),
            () ->
                AnnotationSupport.findAnnotation(context.getRequiredTestClass(), Timeout.class)
                    .map(timeout -> timeout.unit().toSeconds(timeout.value())),
            () ->
                context.getConfigurationParameter(
                    "junit.jupiter.execution.timeout.testtemplate.method.default",
                    Utils::parseJUnitTimeoutValueToSeconds),
            () ->
                context.getConfigurationParameter(
                    "junit.jupiter.execution.timeout.testable.method.default",
                    Utils::parseJUnitTimeoutValueToSeconds),
            () ->
                context.getConfigurationParameter(
                    "junit.jupiter.execution.timeout.default",
                    Utils::parseJUnitTimeoutValueToSeconds))
        .map(Supplier::get)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst()
        .map(timeoutSeconds -> String.format("-timeout=%d", timeoutSeconds));
  }

  /**
   * Discovers and adds the directories for the generated corpus, Java seed corpus and findings to
   * the libFuzzer command line.
   *
   * @return the temporary Java seed corpus directory
   */
  private static Path addInputAndSeedDirs(
      ExtensionContext context, List<String> libFuzzerArgs, boolean createDefaultGeneratedCorpusDir)
      throws IOException {
    Class<?> fuzzTestClass = context.getRequiredTestClass();
    Method fuzzTestMethod = context.getRequiredTestMethod();

    Path baseDir =
        Paths.get(context.getConfigurationParameter("jazzer.internal.basedir").orElse(""))
            .toAbsolutePath();

    // Use the specified corpus dir, if given, otherwise store the generated corpus in a per-class
    // directory under the project root.
    // The path is specified relative to the current working directory, which with JUnit is the
    // project directory.
    Path generatedCorpusDir = baseDir.resolve(generatedCorpusPath(fuzzTestClass, fuzzTestMethod));
    if (createDefaultGeneratedCorpusDir) {
      Files.createDirectories(generatedCorpusDir);
    }
    if (Files.exists(generatedCorpusDir)) {
      String absoluteCorpusDir = generatedCorpusDir.toAbsolutePath().toString();

      // Even if support for long paths (+260 characters) is enabled on Windows,
      // libFuzzer does not work properly. This can be circumvented by prepending "\\?\" to the
      // path,
      // see:
      // https://learn.microsoft.com/en-us/windows/win32/fileio/maximum-file-path-limitation?tabs=registry
      // Error message: "GetFileAttributesA() failed for <path> (Error code: 3)."
      // https://github.com/llvm/llvm-project/blob/release/17.x/compiler-rt/lib/fuzzer/FuzzerIOWindows.cpp#L65
      if (Utils.isWindows()) {
        absoluteCorpusDir = "\\\\?\\" + absoluteCorpusDir;
      }

      libFuzzerArgs.add(absoluteCorpusDir);
    }

    // We can only emit findings into the source tree version of the inputs directory, not e.g. the
    // copy under Maven's target directory. If it doesn't exist, collect the inputs in the current
    // working directory, which is usually the project's source root.
    Optional<Path> findingsDirectory =
        inputsDirectorySourcePath(fuzzTestClass, fuzzTestMethod, baseDir);
    if (!findingsDirectory.isPresent()) {
      context.publishReportEntry(
          String.format(
              "Collecting crashing inputs in the project root directory.\nIf you want to keep them "
                  + "organized by fuzz test and automatically run them as regression tests with "
                  + "JUnit Jupiter, create a test resource directory called '%s' in package '%s' "
                  + "and move the files there.",
              inputsDirectoryResourcePath(fuzzTestClass, fuzzTestMethod),
              fuzzTestClass.getPackage().getName()));
    }

    // We prefer the inputs directory on the classpath, if it exists, as that is more reliable than
    // heuristically looking into the source tree based on the current working directory.
    Optional<Path> inputsDirectory;
    URL inputsDirectoryUrl =
        fuzzTestClass.getResource(inputsDirectoryResourcePath(fuzzTestClass, fuzzTestMethod));
    if (inputsDirectoryUrl != null && "file".equals(inputsDirectoryUrl.getProtocol())) {
      // The inputs directory is a regular directory on disk (i.e., the test is not run from a
      // JAR).
      try {
        // Using inputsDirectoryUrl.getFile() fails on Windows.
        inputsDirectory = Optional.of(Paths.get(inputsDirectoryUrl.toURI()));
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    } else {
      if (inputsDirectoryUrl != null && !findingsDirectory.isPresent()) {
        context.publishReportEntry(
            "When running Jazzer fuzz tests from a JAR rather than class files, the inputs "
                + "directory isn't used unless it is located under src/test/resources/...");
      }
      inputsDirectory = findingsDirectory;
    }

    // From the second positional argument on, files and directories are used as seeds but not
    // modified.
    inputsDirectory.ifPresent(dir -> libFuzzerArgs.add(dir.toAbsolutePath().toString()));
    Path javaSeedsDir = Files.createTempDirectory("jazzer-java-seeds");
    libFuzzerArgs.add(javaSeedsDir.toAbsolutePath().toString());
    libFuzzerArgs.add(
        String.format(
            "-artifact_prefix=%s%c",
            findingsDirectory.orElse(baseDir).toAbsolutePath(), File.separatorChar));
    return javaSeedsDir;
  }

  static void configureAndInstallAgent(
      ExtensionContext extensionContext,
      String maxDuration,
      long maxExecutions,
      Optional<Path> dictionaryPath)
      throws IOException {
    if (!agentInstalled.compareAndSet(false, true)) {
      return;
    }
    if (Utils.isFuzzing(extensionContext)) {
      FuzzTestExecutor executor =
          prepare(extensionContext, maxDuration, maxExecutions, dictionaryPath);
      extensionContext.getRoot().getStore(Namespace.GLOBAL).put(FuzzTestExecutor.class, executor);
      AgentConfigurator.forFuzzing(extensionContext);
    } else {
      AgentConfigurator.forRegressionTest(extensionContext);
    }
    AgentInstaller.install(Opt.hooks.get());
  }

  static FuzzTestExecutor fromContext(ExtensionContext extensionContext) {
    return extensionContext
        .getRoot()
        .getStore(Namespace.GLOBAL)
        .get(FuzzTestExecutor.class, FuzzTestExecutor.class);
  }

  public void addSeed(byte[] bytes) throws IOException {
    if (!javaSeedsDir.isPresent()) {
      return;
    }

    Path tmpSeed = Files.createTempFile(javaSeedsDir.get(), "tmp-seed-", null);
    Files.write(tmpSeed, bytes);

    byte[] hash;
    try {
      hash = MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // Always available.
      throw new IllegalStateException(e);
    }
    // Case-insensitive file systems lose at most one bit of entropy per character, that is, the
    // resulting file name still encodes more than 200 bits of entropy.
    String basename = "seed-" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    Path seed = javaSeedsDir.get().resolve(basename);
    Files.move(tmpSeed, seed, StandardCopyOption.REPLACE_EXISTING);
  }

  public Optional<Throwable> execute(
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext,
      Lifecycle lifecycle) {
    FuzzTargetHolder.fuzzTarget =
        new FuzzTargetHolder.FuzzTarget(
            invocationContext.getExecutable(),
            JUnitLifecycleMethodsInvoker.of(extensionContext, lifecycle));

    AtomicReference<Throwable> atomicFinding = new AtomicReference<>();
    try {
      // Non-fatal findings (with --keep_going) are logged by FuzzTargetRunner.
      AtomicInteger counter = new AtomicInteger(0);
      BiPredicate<byte[], Throwable> predicate =
          (a, b) -> {
            if (counter.incrementAndGet() == Opt.keepGoing.get()) atomicFinding.set(b);
            return Opt.keepGoing.get() != 0 && counter.get() >= Opt.keepGoing.get();
          };
      FuzzTargetRunner.registerFatalFindingDeterminatorForJUnit(predicate);
    } catch (Throwable throwable) {
      // Exception during initialization of FuzzTargetRunner, e.g. unsupported
      // parameter type in fuzz target method. Rethrow as FuzzTestConfigurationError.
      Throwable cause = throwable.getCause();
      if (throwable instanceof ExceptionInInitializerError && cause instanceof RuntimeException) {
        throw new FuzzTestConfigurationError("Error initializing FuzzTargetRunner ", cause);
      }
      throw throwable;
    }

    ApiStatsHolder.apiStats = new ApiStatsInterval();

    int exitCode = FuzzTargetRunner.startLibFuzzer(libFuzzerArgs);
    javaSeedsDir.ifPresent(FuzzTestExecutor::deleteJavaSeedsDir);
    Throwable finding = atomicFinding.get();

    // Print the API stats after the test has finished, so that we get the most recent API stats.
    printApiStats();

    if (finding != null) {
      return Optional.of(new FuzzTestFindingException(finding));
    } else if (exitCode != 0) {
      return Optional.of(
          new ExitCodeException("Jazzer exited with exit code " + exitCode, exitCode));
    } else {
      return Optional.empty();
    }
  }

  private static void deleteJavaSeedsDir(Path javaSeedsDir) {
    // The directory only consists of files, which we need to delete before deleting the directory
    // itself.
    try (Stream<Path> entries = Files.list(javaSeedsDir)) {
      entries.forEach(FuzzTestExecutor::deleteIgnoringErrors);
    } catch (IOException ignored) {
    }
    deleteIgnoringErrors(javaSeedsDir);
  }

  private static void deleteIgnoringErrors(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }
}
