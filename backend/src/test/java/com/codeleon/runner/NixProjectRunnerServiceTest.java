package com.codeleon.runner;

import com.codeleon.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NixProjectRunnerServiceTest {

    private final NixProjectRunnerService service = new NixProjectRunnerService(
            new NixRunnerProperties(
                    true,
                    "nixos/nix:2.24.11",
                    "codeleon-nix-store-test",
                    "codeleon-maven-cache-test",
                    "codeleon-npm-cache-test",
                    "codeleon-pip-cache-test",
                    30_000,
                    512,
                    0.5,
                    64,
                    8_192
            )
    );

    @Test
    void detectsProjectFlakeAndUsesCustomCommand() {
        NixProjectRunnerService.ProjectRunSpec spec = service.detect(new ProjectRunRequest(
                "nix flake check",
                List.of(file("flake.nix", "{}"))
        ));

        assertEquals(NixProjectRunnerService.ProjectEnvironment.NIX_FLAKE, spec.environment());
        assertTrue(spec.projectFlake());
        assertEquals("nix flake check", spec.command());
    }

    @Test
    void detectsMavenProjectAndDefaultsToTests() {
        NixProjectRunnerService.ProjectRunSpec spec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("pom.xml", "<project></project>"), file("src/main/java/App.java", "class App {}"))
        ));

        assertEquals(NixProjectRunnerService.ProjectEnvironment.JAVA_MAVEN, spec.environment());
        assertEquals("mvn test", spec.command());
    }

    @Test
    void detectsNodeProjectAndChoosesBestPackageScript() {
        NixProjectRunnerService.ProjectRunSpec testSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("package.json", "{\"scripts\":{\"test\":\"vitest\",\"build\":\"vite build\"}}"))
        ));
        NixProjectRunnerService.ProjectRunSpec buildSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("package.json", "{\"scripts\":{\"build\":\"vite build\"}}"))
        ));
        NixProjectRunnerService.ProjectRunSpec lockfileSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(
                        file("package.json", "{\"scripts\":{\"test\":\"vitest\"}}"),
                        file("package-lock.json", "{}")
                )
        ));
        NixProjectRunnerService.ProjectRunSpec installSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("package.json", "{\"scripts\":{\"contest\":\"node check.js\"}}"))
        ));

        assertEquals(NixProjectRunnerService.ProjectEnvironment.NODE, testSpec.environment());
        assertEquals("npm install && npm test", testSpec.command());
        assertEquals("npm install && npm run build", buildSpec.command());
        assertEquals("npm ci && npm test", lockfileSpec.command());
        assertEquals("npm install", installSpec.command());
    }

    @Test
    void detectsPythonProjectAndChoosesTestsOrMain() {
        NixProjectRunnerService.ProjectRunSpec testSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("requirements.txt", "pytest"), file("tests/test_app.py", "def test_ok(): pass"))
        ));
        NixProjectRunnerService.ProjectRunSpec mainSpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("pyproject.toml", "[project]\nname='demo'"), file("main.py", "print('hi')"))
        ));
        NixProjectRunnerService.ProjectRunSpec toolOnlySpec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("pyproject.toml", "[tool.pytest.ini_options]"), file("main.py", "print('hi')"))
        ));

        assertEquals(NixProjectRunnerService.ProjectEnvironment.PYTHON, testSpec.environment());
        assertEquals("python -m venv .codeleon-venv && . .codeleon-venv/bin/activate && python -m pip install -r requirements.txt && python -m pip install pytest && python -m pytest", testSpec.command());
        assertEquals("python -m venv .codeleon-venv && . .codeleon-venv/bin/activate && python -m pip install . && python main.py", mainSpec.command());
        assertEquals("python main.py", toolOnlySpec.command());
    }

    @Test
    void generatesToolchainFlakesForDetectedEnvironments() {
        String javaFlake = service.generatedFlake(NixProjectRunnerService.ProjectEnvironment.JAVA_MAVEN);

        assertTrue(javaFlake.contains("systems = [ \"x86_64-linux\" \"aarch64-linux\" ]"));
        assertTrue(javaFlake.contains("forAllSystems"));
        assertTrue(javaFlake.contains("jdk21_headless maven"));
        assertTrue(service.generatedFlake(NixProjectRunnerService.ProjectEnvironment.NODE).contains("nodejs_20 python312 gnumake gcc pkg-config"));
        assertTrue(service.generatedFlake(NixProjectRunnerService.ProjectEnvironment.PYTHON).contains("python312 python312Packages.pip python312Packages.pytest gcc pkg-config"));
        assertTrue(service.generatedFlakeLock().contains("50ab793786d9de88ee30ec4e4c24fb4236fc2674"));
    }

    @Test
    void projectRunResultIncludesResolvedMetadata() {
        NixProjectRunnerService.ProjectRunSpec spec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("pom.xml", "<project></project>"))
        ));

        ProjectRunResult result = ProjectRunResult.from(
                new RunResult("ok", "", 0, 10L, false),
                spec,
                1,
                testProperties()
        );

        assertEquals("Generated Java/Maven", result.environment());
        assertEquals("mvn test", result.command());
        assertTrue(result.generatedEnvironment());
        assertEquals(1, result.fileCount());
        assertEquals(30_000, result.timeoutMs());
        assertEquals("nixos/nix:2.24.11", result.runnerImage());
        assertTrue(result.cacheVolumes().contains("codeleon-nix-store-test"));
        assertTrue(result.cacheVolumes().contains("codeleon-maven-cache-test"));
    }

    @Test
    void detectionReturnsNonRunnableInsteadOfThrowingForStatusUi() {
        ProjectRunDetection detection = service.detectRunnable(new ProjectRunRequest(
                null,
                List.of(file("README.md", "hello"))
        ));

        assertEquals(false, detection.runnable());
        assertEquals("No Nix-compatible project environment detected", detection.message());
    }

    @Test
    void buildsDockerCommandWithSandboxLimitsAndWorkspace() {
        NixProjectRunnerService.ProjectRunSpec spec = service.detect(new ProjectRunRequest(
                null,
                List.of(file("pom.xml", "<project></project>"))
        ));
        List<String> command = service.buildDockerCommand(
                "codeleon-nix-test",
                Path.of("C:/tmp/codeleon-nix-workspace"),
                spec
        );

        assertTrue(command.contains("--memory=512m"));
        assertTrue(command.contains("--memory-swap=512m"));
        assertTrue(command.contains("--cpus=0.5"));
        assertTrue(command.contains("--pids-limit=64"));
        assertTrue(command.contains("--security-opt=no-new-privileges"));
        assertTrue(command.contains("codeleon-nix-store-test:/nix"));
        assertTrue(command.contains("codeleon-maven-cache-test:/root/.m2"));
        assertTrue(command.contains("nixos/nix:2.24.11"));
        assertTrue(command.contains("--env=CODELEON_PROJECT_COMMAND=mvn test"));
        assertTrue(command.stream().anyMatch(part -> part.endsWith(":/workspace")));
    }

    @Test
    void mountsDependencyCachesForDetectedEnvironment() {
        List<String> nodeCommand = service.buildDockerCommand(
                "codeleon-nix-node",
                Path.of("C:/tmp/codeleon-nix-node"),
                service.detect(new ProjectRunRequest(null, List.of(file("package.json", "{}"))))
        );
        List<String> pythonCommand = service.buildDockerCommand(
                "codeleon-nix-python",
                Path.of("C:/tmp/codeleon-nix-python"),
                service.detect(new ProjectRunRequest(null, List.of(file("requirements.txt", ""))))
        );
        List<String> flakeCommand = service.buildDockerCommand(
                "codeleon-nix-flake",
                Path.of("C:/tmp/codeleon-nix-flake"),
                service.detect(new ProjectRunRequest(null, List.of(file("flake.nix", "{}"))))
        );

        assertTrue(nodeCommand.contains("codeleon-npm-cache-test:/root/.npm"));
        assertTrue(nodeCommand.contains("--env=NPM_CONFIG_CACHE=/root/.npm"));
        assertTrue(pythonCommand.contains("codeleon-pip-cache-test:/root/.cache/pip"));
        assertTrue(pythonCommand.contains("--env=PIP_CACHE_DIR=/root/.cache/pip"));
        assertTrue(flakeCommand.contains("codeleon-maven-cache-test:/root/.m2"));
        assertTrue(flakeCommand.contains("codeleon-npm-cache-test:/root/.npm"));
        assertTrue(flakeCommand.contains("codeleon-pip-cache-test:/root/.cache/pip"));
    }

    @Test
    void rejectsProjectsWithoutRunnableManifest() {
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.detect(new ProjectRunRequest(null, List.of(file("README.md", "hello"))))
        );

        assertTrue(ex.getMessage().contains("No Nix-compatible project environment detected"));
    }

    @Test
    void rejectsInvalidPackageJson() {
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.detect(new ProjectRunRequest(null, List.of(file("package.json", "{bad json"))))
        );

        assertTrue(ex.getMessage().contains("package.json is not valid JSON"));
    }

    @Test
    void rejectsMultilineCommands() {
        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.detect(new ProjectRunRequest("npm install\nnpm test", List.of(file("package.json", "{}"))))
        );

        assertTrue(ex.getMessage().contains("Invalid project command"));
    }

    private static RunRequest.RunFile file(String path, String text) {
        return new RunRequest.RunFile(path, text);
    }

    private static NixRunnerProperties testProperties() {
        return new NixRunnerProperties(
                true,
                "nixos/nix:2.24.11",
                "codeleon-nix-store-test",
                "codeleon-maven-cache-test",
                "codeleon-npm-cache-test",
                "codeleon-pip-cache-test",
                30_000,
                512,
                0.5,
                64,
                8_192
        );
    }
}
