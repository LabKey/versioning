package net.nemerosa.versioning.git

import net.nemerosa.versioning.SCMInfo
import net.nemerosa.versioning.SCMInfoService
import net.nemerosa.versioning.VersioningExtension
import org.ajoberstar.grgit.Commit
import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.Remote
import org.ajoberstar.grgit.Status
import org.gradle.api.GradleException
import org.gradle.api.Project

class GitInfoService implements SCMInfoService {

    @Override
    SCMInfo getInfo(Project project, VersioningExtension extension) {
        // Is Git enabled?
        boolean hasGit = project.file('.git').exists()
        // No Git information
        if (!hasGit) {
            SCMInfo.NONE
        }
        // Git information available
        else {
            // Open the Git repo
            //noinspection GroovyAssignabilityCheck
            def grgit = Grgit.open(currentDir: project.projectDir)

            // Check passed in environment variable list
            String branch = null
            for (ev in extension.branchEnv) {
                if (System.env[ev] != null) {
                    branch = System.env[ev]
                    break
                }
            }
            // Gets the branch info from git
            if (branch == null) {
                branch = grgit.branch.current.name
            }

            // Gets the commit info (full hash)
            List<Commit> commits = grgit.log(maxCommits: 1)
            if (commits.empty) {
                throw new GradleException("No commit available in the repository - cannot compute version")
            }
            String commit = commits[0].id
            // Gets the current commit (short hash)
            String abbreviated = commits[0].abbreviatedId

            // Gets the current tag, if any
            String tag
            String described = grgit.repository.jgit.describe().setLong(true).call()
            if (described) {
                // The format returned by the long version of the `describe` command is: <tag>-<number>-<commit>
                def m = described =~ /^(.*)-(\d+)-g([0-9a-f]+)$/
                if (m.matches()) {
                    def count = m.group(2) as int
                    if (count == 0) {
                        // We're on a tag
                        tag = m.group(1)
                    } else {
                        // No tag
                        tag = null
                    }
                } else {
                    throw new GradleException("Cannot get parse description of current commit: ${described}")
                }
            } else {
                // Nothing returned - it means there is no previous tag
                tag = null
            }

            String url
            List<Remote> remotes = grgit.remote.list()
            if (remotes) {
                url = remotes.get(0).url
            } else {
                url = null
            }

            // Returns the information
            new SCMInfo(
                    url: url,
                    branch: branch,
                    commit: commit,
                    abbreviated: abbreviated,
                    dirty: isGitTreeDirty(project.projectDir),
                    tag: tag,
            )
        }
    }

    static boolean isGitTreeDirty(File dir) {// Open the Git repo
        //noinspection GroovyAssignabilityCheck
        Status status = Grgit.open(currentDir: dir).status()
        return !isClean(status)
    }

    private static boolean isClean(Status status) {
        return status.staged.allChanges.empty &&
                status.unstaged.allChanges.findAll { !it.startsWith('userHome/') }.empty
    }

    @Override
    List<String> getBaseTags(Project project, VersioningExtension extension, String base) {
        // Filtering on patterns
        def baseTagPattern = /^${base}\.(\d+)$/
        // Git access
        //noinspection GroovyAssignabilityCheck
        def grgit = Grgit.open(currentDir: project.projectDir)
        // List all tags
        return grgit.tag.list()
        // ... filters using the pattern
                .findAll { it.name ==~ baseTagPattern }
        // ... sort by desc time
                .sort { -it.commit.time }
        // ... gets their name only
                .collect { it.name }
    }

    @Override
    String getBranchTypeSeparator() {
        '/'
    }
}
