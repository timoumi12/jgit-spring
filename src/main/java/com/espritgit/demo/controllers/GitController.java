package com.espritgit.demo.controllers;

import com.espritgit.demo.services.GitService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger; // Import Logger
import org.slf4j.LoggerFactory; // Import LoggerFactory
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/git")
public class GitController {

    private static final Logger logger = LoggerFactory.getLogger(GitController.class); // Add logger instance

    private final GitService gitService;

    @Autowired
    public GitController(GitService gitService) {
        this.gitService = gitService;
    }

    @GetMapping("/info")
    public ResponseEntity<?> getRepoInfo(@RequestParam String repoUrl) {
        try {
            Path localRepoPath = gitService.prepareLocalRepository(repoUrl);

            List<String> branches = gitService.listBranches(localRepoPath);
            String latestCommitHash = gitService.getLatestCommitHash(localRepoPath);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Repository processed successfully");
            response.put("repositoryUrl", repoUrl);
            response.put("localPath", localRepoPath.toAbsolutePath().toString()); // Show absolute path
            response.put("branches", branches);
            response.put("latestCommitHash", latestCommitHash);

            return ResponseEntity.ok(response);

        } catch (GitAPIException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Git operation failed: " + e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("File system operation failed: " + e.getMessage());
        }
        // NO finally block to delete the repository here, as we want to persist it.
    }


    @GetMapping("/clone") // You could change this to @PostMapping if you prefer
    public ResponseEntity<?> cloneRepository(@RequestParam String repoUrl) {
        try {
            Path localRepoPath = gitService.prepareLocalRepository(repoUrl); // This does the clone/check

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Repository cloned/verified successfully.");
            response.put("repositoryUrl", repoUrl);
            response.put("localPath", localRepoPath.toAbsolutePath().toString());

            return ResponseEntity.ok(response);

        } catch (GitAPIException e) {
            logger.error("Git operation failed during clone/prepare for URL {}: {}", repoUrl, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Git operation failed during clone/prepare.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        } catch (IOException e) {
            logger.error("File system operation failed during clone/prepare for URL {}: {}", repoUrl, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "File system operation failed during clone/prepare.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/create-empty")
    public ResponseEntity<?> createEmptyRepository(@RequestParam String repoName) {
        if (repoName == null || repoName.trim().isEmpty() || repoName.contains("/") || repoName.contains("\\")) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Invalid repository name.");
            errorResponse.put("error", "Repository name cannot be empty and must not contain path separators.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }

        try {
            Path localRepoPath = gitService.createLocalEmptyRepository(repoName);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Empty repository created successfully.");
            response.put("repositoryName", repoName);
            response.put("localPath", localRepoPath.toAbsolutePath().toString());
            // The .git directory will be inside localPath, e.g., localPath/.git
            response.put("gitDirectory", Paths.get(localRepoPath.toString(), ".git").toAbsolutePath().toString());


            return ResponseEntity.status(HttpStatus.CREATED).body(response); // 201 Created

        } catch (IllegalStateException e) { // Handles "already exists" scenarios
            logger.warn("Attempt to create repository that already exists or conflicts: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Could not create repository.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse); // 409 Conflict
        } catch (GitAPIException e) {
            logger.error("Git operation failed during empty repository creation for '{}': {}", repoName, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Git operation failed during repository creation.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        } catch (IOException e) {
            logger.error("File system operation failed during empty repository creation for '{}': {}", repoName, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "File system operation failed during repository creation.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/local-repos")
    public ResponseEntity<?> listLocalRepositories() {
        try {
            List<String> repoNames = gitService.listLocalRepositories();
            Path basePath = Paths.get(gitService.getRepositoriesBasePath()); // Get base path for context

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Successfully retrieved local repositories.");
            response.put("basePath", basePath.toAbsolutePath().toString());
            response.put("count", repoNames.size());
            response.put("repositories", repoNames);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            logger.error("Failed to list local repositories: {}", e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Failed to list local repositories.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * 1. Get Repository Status
     * Example: GET /api/git/my-repo/status
     */
    @GetMapping("/{repoName}/status")
    public ResponseEntity<?> getRepositoryStatus(@PathVariable String repoName) throws GitAPIException, IOException {
        logger.info("Request to get status for repository: {}", repoName);
        Map<String, Object> status = gitService.getRepositoryStatus(repoName);
        return ResponseEntity.ok(status);
    }

    /**
     * 2. List Commits (Log)
     * Example: GET /api/git/my-repo/log?branch=main&maxCount=10&skip=0
     */
    @GetMapping("/{repoName}/log")
    public ResponseEntity<List<Map<String, String>>> getCommitLog(
            @PathVariable String repoName,
            @RequestParam(defaultValue = "HEAD") String branch, // Default to HEAD if no branch specified
            @RequestParam(defaultValue = "20") int maxCount,
            @RequestParam(defaultValue = "0") int skip) throws GitAPIException, IOException {
        logger.info("Request to get commit log for repository: {}, branch: {}, maxCount: {}, skip: {}",
                repoName, branch, maxCount, skip);
        List<Map<String, String>> commits = gitService.getCommitLog(repoName, branch, maxCount, skip);
        return ResponseEntity.ok(commits);
    }

    /**
     * 3. Get File Content at a Specific Commit/Branch
     * Example: GET /api/git/my-repo/file?path=README.md&ref=main
     */
    @GetMapping("/{repoName}/file")
    public ResponseEntity<String> getFileContent(
            @PathVariable String repoName,
            @RequestParam String path,
            @RequestParam(defaultValue = "HEAD") String ref) throws GitAPIException, IOException {
        logger.info("Request to get file content for repository: {}, path: {}, ref: {}", repoName, path, ref);
        String content = gitService.getFileContent(repoName, path, ref);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN) // Set content type to plain text
                .body(content);
    }
}