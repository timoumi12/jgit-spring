package com.espritgit.demo.services;


import com.espritgit.demo.exception.ResourceNotFoundException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.eclipse.jgit.api.LogCommand; // New import
import org.eclipse.jgit.api.Status; // New import
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*; // New import for ObjectId, Repository, Constants, PersonIdent
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk; // New import
import org.eclipse.jgit.treewalk.TreeWalk; // New import
import org.eclipse.jgit.treewalk.filter.PathFilter; // New import

import java.nio.charset.StandardCharsets; // New import
import java.text.SimpleDateFormat; // New import
import java.util.*; // New import
import java.util.stream.Collectors;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class GitService {

    private static final Logger logger = LoggerFactory.getLogger(GitService.class);

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z"); // For commit dates

    @Value("${git.repositories.base-path}")
    private String repositoriesBasePath;


    // Getter for repositoriesBasePath (useful for controller)
    public String getRepositoriesBasePath() {
        return repositoriesBasePath;
    }

    /**
     * Ensures the repository is cloned locally. If it already exists, it uses the existing one.
     *
     * @param repoUrl The URL of the Git repository.
     * @return The Path to the local repository.
     * @throws GitAPIException If a Git operation fails.
     * @throws IOException     If a file system operation fails.
     */
    public Path prepareLocalRepository(String repoUrl) throws GitAPIException, IOException {
        String repoName = extractRepoNameFromUrl(repoUrl);
        Path localRepoPath = Paths.get(repositoriesBasePath, repoName);

        if (Files.exists(localRepoPath) && isValidGitRepository(localRepoPath)) {
            logger.info("Repository {} already exists at {}. Using existing.", repoName, localRepoPath);
            // Optionally, you could add a 'git pull' here to update
            // try (Git git = Git.open(localRepoPath.toFile())) {
            //     logger.info("Pulling latest changes for {}", localRepoPath);
            //     git.pull().call();
            // }
        } else {
            logger.info("Cloning repository {} from {} into {}", repoName, repoUrl, localRepoPath);
            // If the path exists but is not a valid git repo (e.g., failed previous clone), delete it.
            if (Files.exists(localRepoPath)) {
                logger.warn("Path {} exists but is not a valid Git repository. Deleting it before cloning.", localRepoPath);
                deleteDirectory(localRepoPath.toFile());
            }

            // Ensure parent directory of localRepoPath exists
            Files.createDirectories(localRepoPath.getParent());

            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(localRepoPath.toFile())
                    .setCloneAllBranches(true)
                    .call()) {
                logger.info("Repository {} cloned successfully to {}", repoName, localRepoPath);
            } catch (GitAPIException e) {
                logger.error("Error cloning repository {}: {}", repoUrl, e.getMessage());
                // Clean up the directory if cloning failed
                deleteDirectory(localRepoPath.toFile());
                throw e;
            }
        }
        return localRepoPath;
    }

    public List<String> listBranches(Path repoPath) throws IOException, GitAPIException {
        logger.info("Listing branches for repository at {}", repoPath);
        try (Git git = Git.open(repoPath.toFile())) {
            List<Ref> branches = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call();
            return branches.stream()
                    .map(Ref::getName)
                    .collect(Collectors.toList());
        }
    }

    public String getLatestCommitHash(Path repoPath) throws IOException, GitAPIException {
        logger.info("Getting latest commit for repository at {}", repoPath);
        try (Git git = Git.open(repoPath.toFile())) {
            // Get the default branch (e.g., main or master)
            String defaultBranch = git.getRepository().getFullBranch(); // e.g., refs/heads/main
            if (defaultBranch == null) {
                // Fallback if HEAD is detached or no default branch found easily
                // Try to find 'main' or 'master'
                List<Ref> branches = git.branchList().call();
                Ref mainRef = branches.stream().filter(b -> b.getName().endsWith("/main")).findFirst().orElse(null);
                if (mainRef == null) {
                    mainRef = branches.stream().filter(b -> b.getName().endsWith("/master")).findFirst().orElse(null);
                }
                if (mainRef != null) {
                    defaultBranch = mainRef.getName();
                } else if (!branches.isEmpty()){
                    defaultBranch = branches.get(0).getName(); // last resort, pick first local branch
                } else {
                    return "No branches found to determine latest commit.";
                }
            }

            Iterable<RevCommit> logs = git.log().add(git.getRepository().resolve(defaultBranch)).setMaxCount(1).call();
            RevCommit latestCommit = logs.iterator().hasNext() ? logs.iterator().next() : null;

            if (latestCommit != null) {
                return latestCommit.getId().getName(); // .getName() gives the SHA-1 hash
            }
            return "No commits found on default branch.";
        }
    }

    private String extractRepoNameFromUrl(String repoUrl) {
        String name = repoUrl.substring(repoUrl.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private boolean isValidGitRepository(Path path) {
        // A simple check: does it contain a .git directory?
        return Files.isDirectory(path) && Files.isDirectory(Paths.get(path.toString(), ".git"));
    }

    public void deleteDirectory(File directoryToBeDeleted) {
        if (!directoryToBeDeleted.exists()) {
            return;
        }
        logger.info("Deleting directory: {}", directoryToBeDeleted.getAbsolutePath());
        try {
            Files.walk(directoryToBeDeleted.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            logger.error("Error deleting directory {}: {}", directoryToBeDeleted.getAbsolutePath(), e.getMessage());
        }
    }

    public Path createLocalEmptyRepository(String repoName) throws IOException, GitAPIException {
        Path localRepoPath = Paths.get(repositoriesBasePath, repoName);

        if (Files.exists(localRepoPath)) {
            // Check if it's already a valid Git repository or just a directory
            if (isValidGitRepository(localRepoPath)) {
                logger.warn("Repository '{}' already exists as a Git repository at {}.", repoName, localRepoPath);
                throw new IllegalStateException("Repository '" + repoName + "' already exists as a Git repository.");
            } else {
                logger.warn("Directory '{}' already exists but is not a Git repository at {}.", repoName, localRepoPath);
                throw new IllegalStateException("A directory (not a Git repository) named '" + repoName + "' already exists.");
            }
        }

        // Ensure parent directory of repositoriesBasePath exists (though it should from prepareLocalRepository)
        // And then create the specific repository directory
        Files.createDirectories(localRepoPath); // This will create the 'repoName' directory

        logger.info("Initializing new empty Git repository '{}' at {}", repoName, localRepoPath);
        try (Git git = Git.init().setDirectory(localRepoPath.toFile()).call()) {
            logger.info("Successfully initialized empty repository: {}", git.getRepository().getDirectory());
            return localRepoPath;
        } catch (GitAPIException e) {
            logger.error("Failed to initialize Git repository '{}': {}", repoName, e.getMessage());
            // Clean up the created directory if init fails
            deleteDirectory(localRepoPath.toFile());
            throw e;
        }
    }

    public List<String> listLocalRepositories() throws IOException {
        Path basePath = Paths.get(repositoriesBasePath);
        List<String> repoNames = new ArrayList<>();

        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            logger.warn("Repositories base path '{}' does not exist or is not a directory. No local repositories to list.", repositoriesBasePath);
            return repoNames; // Return empty list if base path is invalid
        }

        logger.info("Scanning for local repositories in: {}", basePath.toAbsolutePath());
        try (Stream<Path> stream = Files.list(basePath)) {
            repoNames = stream
                    .filter(Files::isDirectory)         // Consider only directories
                    .filter(this::isValidGitRepository) // Check if it's a valid Git repo (has .git folder)
                    .map(path -> path.getFileName().toString()) // Extract the directory name
                    .sorted() // Optional: sort alphabetically
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error listing local repositories in '{}': {}", repositoriesBasePath, e.getMessage(), e);
            throw e; // Re-throw to be handled by the controller
        }

        logger.info("Found {} local repositories: {}", repoNames.size(), repoNames);
        return repoNames;
    }

    // --- Helper to get Git object for a repo name ---
    private Git openRepository(String repoName) throws IOException {
        Path localRepoPath = getLocalRepoPath(repoName);
        if (!Files.exists(localRepoPath) || !isValidGitRepository(localRepoPath)) {
            throw new ResourceNotFoundException("Repository '" + repoName + "' not found or is not a valid Git repository.");
        }
        return Git.open(localRepoPath.toFile());
    }

    private Path getLocalRepoPath(String repoName) {
        // Basic sanitization: prevent ".." and ensure it's a simple name
        if (repoName == null || repoName.trim().isEmpty() || repoName.contains("/") || repoName.contains("\\") || repoName.contains("..")) {
            throw new IllegalArgumentException("Invalid repository name format: " + repoName);
        }
        return Paths.get(repositoriesBasePath, repoName);
    }

    /**
     * 1. Get Repository Status
     */
    public Map<String, Object> getRepositoryStatus(String repoName) throws IOException, GitAPIException {
        try (Git git = openRepository(repoName)) {
            Repository repository = git.getRepository();
            if (repository.isBare()) {
                throw new UnsupportedOperationException("Cannot get status for a bare repository: " + repoName);
            }

            Status status = git.status().call();
            Map<String, Object> statusMap = new LinkedHashMap<>(); // Use LinkedHashMap to preserve order

            statusMap.put("repository", repoName);
            statusMap.put("currentBranch", repository.getFullBranch()); // e.g., refs/heads/main
            statusMap.put("isClean", status.isClean());
            statusMap.put("hasUncommittedChanges", status.hasUncommittedChanges());

            statusMap.put("added", status.getAdded()); // Files added to the index, not in HEAD
            statusMap.put("changed", status.getChanged()); // Files changed from HEAD to index
            statusMap.put("modified", status.getModified()); // Files changed from index to working tree
            statusMap.put("missing", status.getMissing()); // Files removed from the working tree, still in index
            statusMap.put("removed", status.getRemoved()); // Files removed from the index, not in HEAD
            statusMap.put("uncommittedChanges", status.getUncommittedChanges()); //Combines added, changed, modified, missing, removed
            statusMap.put("untracked", status.getUntracked());
            statusMap.put("untrackedFolders", status.getUntrackedFolders());
            statusMap.put("conflicting", status.getConflicting()); // Files with merge conflicts
            statusMap.put("conflictingStageState", status.getConflictingStageState()); // Detailed conflict info

            return statusMap;
        }
    }

    /**
     * 2. List Commits (Log)
     */
    public List<Map<String, String>> getCommitLog(String repoName, String branchOrRefName, int maxCount, int skip) throws IOException, GitAPIException {
        try (Git git = openRepository(repoName)) {
            Repository repository = git.getRepository();
            List<Map<String, String>> commits = new ArrayList<>();

            LogCommand logCommand = git.log();

            ObjectId branchObjectId = repository.resolve(branchOrRefName);
            if (branchObjectId == null) {
                throw new ResourceNotFoundException("Branch or reference '" + branchOrRefName + "' not found in repository '" + repoName + "'.");
            }
            logCommand.add(branchObjectId);

            if (maxCount > 0) {
                logCommand.setMaxCount(maxCount);
            }
            if (skip > 0) {
                logCommand.setSkip(skip);
            }

            Iterable<RevCommit> logs = logCommand.call();
            for (RevCommit rev : logs) {
                Map<String, String> commitDetails = new LinkedHashMap<>();
                commitDetails.put("hash", rev.getId().getName());
                commitDetails.put("shortMessage", rev.getShortMessage());
                commitDetails.put("fullMessage", rev.getFullMessage());

                PersonIdent authorIdent = rev.getAuthorIdent();
                commitDetails.put("authorName", authorIdent.getName());
                commitDetails.put("authorEmail", authorIdent.getEmailAddress());
                commitDetails.put("authorDate", dateFormat.format(authorIdent.getWhen()));

                PersonIdent committerIdent = rev.getCommitterIdent();
                commitDetails.put("committerName", committerIdent.getName());
                commitDetails.put("committerEmail", committerIdent.getEmailAddress());
                commitDetails.put("committerDate", dateFormat.format(committerIdent.getWhen()));

                commits.add(commitDetails);
            }
            return commits;
        }
    }


    /**
     * 3. Get File Content at a Specific Commit/Branch
     */
    public String getFileContent(String repoName, String filePath, String refName) throws IOException, GitAPIException {
        try (Git git = openRepository(repoName)) {
            Repository repository = git.getRepository();

            ObjectId refObjectId = repository.resolve(refName);
            if (refObjectId == null) {
                throw new ResourceNotFoundException("Reference '" + refName + "' not found in repository '" + repoName + "'.");
            }

            try (RevWalk revWalk = new RevWalk(repository)) {
                RevCommit commit = revWalk.parseCommit(refObjectId);
                org.eclipse.jgit.lib.ObjectId treeId = commit.getTree().getId(); // Use fully qualified name

                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(treeId);
                    treeWalk.setRecursive(true); // Important if filePath is nested
                    treeWalk.setFilter(PathFilter.create(filePath));

                    if (!treeWalk.next()) {
                        throw new ResourceNotFoundException("File '" + filePath + "' not found in reference '" + refName + "' of repository '" + repoName + "'.");
                    }

                    ObjectId objectId = treeWalk.getObjectId(0);
                    ObjectLoader loader = repository.open(objectId, Constants.OBJ_BLOB);
                    byte[] bytes = loader.getBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                } finally {
                    revWalk.dispose(); // Explicitly dispose RevWalk if not in try-with-resources for it
                }
            }
        }
    }
}