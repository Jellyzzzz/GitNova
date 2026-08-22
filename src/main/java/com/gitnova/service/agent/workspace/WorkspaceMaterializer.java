package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.CommitObject;
import com.gitnova.gitobject.GitObjectId;
import com.gitnova.gitobject.GitObjectReader;
import com.gitnova.storage.RepoKey;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;

public final class WorkspaceMaterializer {
    private final GitObjectReader gitObjectReader;
    public WorkspaceMaterializer(GitObjectReader gitObjectReader){
        this.gitObjectReader=Objects.requireNonNull(gitObjectReader,"gitObjectReader must not be null");
    }
    public void materialize(RepoKey repoKey,Path stagingRoot,SnapshotScope scope) throws IOException {
        Objects.requireNonNull(repoKey,"repoKey must not be null");
        Objects.requireNonNull(scope,"scope must not be null");
        Path normalizedRoot=requireSafeStaging(stagingRoot);
        CommitObject commit=gitObjectReader.requireCommit(repoKey.value(),scope.baseSha1().value());
        List<PlannedFile>plan=planFiles(normalizedRoot,commit.mapping());
        for(PlannedFile file:plan){
            materializeFile(repoKey,normalizedRoot,file);
        }
    }
    private record PlannedFile(String repositoryPath, GitObjectId blobId, Path target){
    }

    private Path requireSafeStaging(Path stagingRoot){
        Objects.requireNonNull(stagingRoot,"stagingRoot must not be null");
        Path normalized=stagingRoot.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(normalized)){
            throw new WorkspaceProvisionException("staging root must not be a symbolic link", WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE);
        }
        if(!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)){
            throw new WorkspaceProvisionException("staging root must be an existing directory", WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE);
        }
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(normalized)) {
            if (entries.iterator().hasNext()) {
                throw new WorkspaceProvisionException(
                        "staging root must be empty",
                        WorkspaceProvisionException.Reason.WORKSPACE_CONFLICT
                );
            }
        } catch (IOException exception) {
            throw new WorkspaceProvisionException(
                    "failed to inspect staging root",
                    WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE,
                    exception
            );
        }
        return normalized;
    }

    private WorkspaceProvisionException invalidRepositoryPath(String path) {
        return new WorkspaceProvisionException(
                "Commit contains an unsafe repository path: " + path,
                WorkspaceProvisionException.Reason.INVALID_REPOSITORY_PATH
        );
    }
    private Path resolveRepositoryPath(Path stagingRoot,String repositoryPath){
        if(repositoryPath==null
            ||repositoryPath.isBlank()
            ||repositoryPath.indexOf('\0')>=0
            ||repositoryPath.startsWith("/")
            ||repositoryPath.startsWith("\\")
            ||repositoryPath.contains("\\")
            ||repositoryPath.matches("^[A-Za-z]:.*")){
            throw invalidRepositoryPath(repositoryPath);
        }
        for (String segment :
                repositoryPath.split("/", -1)) {

            if (segment.isEmpty()
                    || segment.equals(".")
                    || segment.equals("..")) {

                throw invalidRepositoryPath(
                        repositoryPath
                );
            }
        }
        Path target=stagingRoot.resolve(repositoryPath).normalize();
        if(target.equals(stagingRoot)||!target.startsWith(stagingRoot)){
            throw invalidRepositoryPath(repositoryPath);
        }
        return target;
    }

    private List<PlannedFile>planFiles(Path stagingRoot, Map<String,GitObjectId> mapping){
        Objects.requireNonNull(mapping,"mapping must not be null");
        List<PlannedFile>plans=new ArrayList<>(mapping.size());
        Set<Path>targetFiles=new HashSet<>();
        for(Map.Entry<String,GitObjectId>entry:mapping.entrySet()){
            String repositoryPath= entry.getKey();
            GitObjectId blobId=Objects.requireNonNull(entry.getValue(),"mapping blob must not be null");
            Path target=resolveRepositoryPath(stagingRoot,repositoryPath);
            if(!targetFiles.add(target)){
                throw new WorkspaceProvisionException("Multiple repository paths resolve "
                        + "to the same Workspace path", WorkspaceProvisionException.Reason.PATH_COLLISION);
            }
            plans.add(new PlannedFile(repositoryPath,blobId,target));
        }
        requireNoFileDirectoryConflicts(stagingRoot,targetFiles);
        return List.copyOf(plans);
    }

    private void requireNoFileDirectoryConflicts(Path stagingRoot,Set<Path>targetFiles){
        for(Path file:targetFiles){
            Path ancestor=file.getParent();
            while(ancestor!=null&&!ancestor.equals(stagingRoot)){
                if(targetFiles.contains(ancestor)){
                    throw new WorkspaceProvisionException("Repository paths contain a "
                            + "file/directory collision", WorkspaceProvisionException.Reason.PATH_COLLISION);
                }
                ancestor=ancestor.getParent();
            }
        }
    }

    private void ensureSafeParentDirectories(Path stagingRoot,Path parent)throws IOException {
        Path relative = stagingRoot.relativize(parent);
        Path current = stagingRoot;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException ignored) {
                }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceProvisionException("Workspace parent path is not a safe directory", WorkspaceProvisionException.Reason.PATH_COLLISION);
            }
        }
    }

    private void materializeFile(RepoKey repoKey,Path stagingRoot,PlannedFile file) throws IOException {
        Path parent=file.target.getParent();
        ensureSafeParentDirectories(stagingRoot,parent);
        try(OutputStream outputStream=Files.newOutputStream(file.target, StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            gitObjectReader.copyBlobTo(repoKey.value(),file.blobId.value(),outputStream);
        }catch(FileAlreadyExistsException e) {
            throw new WorkspaceProvisionException( "Workspace target already exists", WorkspaceProvisionException.Reason.PATH_COLLISION,e);
        }
    }

}
