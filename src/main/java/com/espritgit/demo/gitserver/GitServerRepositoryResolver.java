package com.example.jgitpersistentdemo.gitserver;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GitServerRepositoryResolver implements RepositoryResolver<HttpServletRequest> {

    private static final Logger logger = LoggerFactory.getLogger(GitServerRepositoryResolver.class);
    private final String repositoriesBasePath;

    public GitServerRepositoryResolver(String repositoriesBasePath) {
        this.repositoriesBasePath = repositoriesBasePath;
    }

    @Override
    public Repository open(HttpServletRequest request, String name)
            throws ServiceNotAuthorizedException, ServiceNotEnabledException {
        // The 'name' will be like "my-repo.git" or "group/my-repo.git"
        // We need to map this to a path under repositoriesBasePath

        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        // Sanitize name to prevent directory traversal, although Paths.get should handle some of it.
        // For simplicity, we are assuming 'name' doesn't contain '..' etc.
        // In a production system, more robust sanitization is needed.
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
            logger.warn("Attempt to access repository with invalid name: {}", name);
            throw new ServiceNotEnabledException("Invalid repository name: " + name);
        }

        Path repoPath = Paths.get(repositoriesBasePath, name);
        File gitDir = repoPath.toFile(); // JGit expects path to the .git directory OR the worktree

        logger.debug("Attempting to open repository: {} -> {}", name, repoPath.toAbsolutePath());

        if (!Files.exists(repoPath) || !Files.isDirectory(repoPath)) {
            logger.warn("Repository not found at path: {}", repoPath.toAbsolutePath());
            throw new ServiceNotEnabledException("Repository not found: " + name);
        }

        // Check if it's a valid git repository (contains .git dir or is a bare repo)
        File dotGitDir = new File(gitDir, ".git");
        boolean isBare = new File(gitDir, "HEAD").exists() && new File(gitDir, "objects").exists() && new File(gitDir, "refs").exists();


        if (!dotGitDir.exists() && !isBare) {
            logger.warn("Path is not a valid Git repository (no .git dir or not bare): {}", repoPath.toAbsolutePath());
            throw new ServiceNotEnabledException("Not a valid Git repository: " + name);
        }

        try {
            // FileRepositoryBuilder can open both working tree and .git directory paths
            Repository repository = new FileRepositoryBuilder()
                    .setGitDir(dotGitDir.exists() ? dotGitDir : gitDir) // Use .git if exists, else assume bare
                    .readEnvironment() // Scan environment GIT_DIR, etc.
                    .findGitDir()      // Scan up the tree for .git
                    .build();
            logger.info("Successfully opened repository: {}", repository.getDirectory());
            return repository;
        } catch (IOException e) {
            logger.error("Failed to open repository {}: {}", name, e.getMessage());
            throw new ServiceNotEnabledException("Cannot open repository: " + name, e);
        }
    }
}