package com.gitnova.service.agent.workspace;

import com.gitnova.gitobject.GitObjectReadException;
import com.gitnova.storage.config.WorkspaceStorageProperties;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class LocalWorkspaceProvider implements WorkspaceProvider{
    private static final String STAGING_PREFIX = ".provisioning-";
    private final Path workspaceBase;
    private final WorkspaceMaterializer materializer;
    public LocalWorkspaceProvider(WorkspaceStorageProperties properties,WorkspaceMaterializer materializer){
        Objects.requireNonNull(properties,"properties must not be null");
        this.workspaceBase=properties.basePath().toAbsolutePath().normalize();
        this.materializer=Objects.requireNonNull(materializer,"materializer must not be null");
    }

    @Override
    public WorkspaceHandle provision(WorkspaceSpec trustedSpec) {
        Objects.requireNonNull(trustedSpec,"trustedSpec must not be null");
        Path safeBase=requireSafeWorkspaceBase();
        Path finalRoot=resolveFinalRoot(safeBase,trustedSpec.workspaceId());
        requireFinalRootAbsent(finalRoot);
        Path stagingRoot=createStagingRoot(safeBase,trustedSpec.workspaceId());
        try{
            materializer.materialize(trustedSpec.repoKey(),stagingRoot,trustedSpec.snapshotScope());
            WorkspaceHandle readyHandle =
                    new WorkspaceHandle(
                            trustedSpec.workspaceId(),
                            trustedSpec.repoKey(),
                            trustedSpec.snapshotScope(),
                            finalRoot,
                            WorkspaceStatus.READY,
                            0
                    );
            publish(stagingRoot,finalRoot);
            return readyHandle;
        }catch (GitObjectReadException exception) {
            cleanupStaging(
                    stagingRoot,
                    exception
            );
            throw mapReadFailure(
                    exception
            );
        } catch (WorkspaceProvisionException exception) {
            cleanupStaging(
                    stagingRoot,
                    exception
            );
            throw exception;
        } catch (IOException exception) {
            cleanupStaging(
                    stagingRoot,
                    exception
            );
            throw new WorkspaceProvisionException(
                    "failed to materialize workspace",
                    WorkspaceProvisionException.Reason
                            .FILESYSTEM_FAILURE,
                    exception
            );
        }
    }
    private Path requireSafeWorkspaceBase(){
        try{
            if(Files.notExists(workspaceBase, LinkOption.NOFOLLOW_LINKS)){
                Files.createDirectories(workspaceBase);
            }
            if(Files.isSymbolicLink(workspaceBase)||!Files.isDirectory(workspaceBase,LinkOption.NOFOLLOW_LINKS)){
                throw new WorkspaceProvisionException( "workspace base must be a safe directory", WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE);
            }
            return workspaceBase;
        }catch(WorkspaceProvisionException e){
            throw e;
        }catch(IOException e){
            throw new WorkspaceProvisionException("failed to prepare workspace base", WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE,e);
        }
    }
    private Path resolveFinalRoot(
            Path safeBase,
            WorkspaceId workspaceId
    ) {
        Path finalRoot =
                safeBase
                        .resolve(workspaceId.toString())
                        .normalize();
        if (finalRoot.equals(safeBase)
                || !finalRoot.startsWith(safeBase)) {
            throw new WorkspaceProvisionException(
                    "workspace path escapes configured root",
                    WorkspaceProvisionException.Reason
                            .FILESYSTEM_FAILURE
            );
        }
        return finalRoot;
    }
    private void requireFinalRootAbsent(
            Path finalRoot
    ) {
        if (Files.exists(
                finalRoot,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new WorkspaceProvisionException(
                    "workspace already exists",
                    WorkspaceProvisionException.Reason
                            .WORKSPACE_CONFLICT
            );
        }
    }
    private Path createStagingRoot(Path safeBase,WorkspaceId workspaceId){
        try{
            return Files.createTempDirectory(safeBase,STAGING_PREFIX+workspaceId+"-");
        }catch(IOException e){
            throw new WorkspaceProvisionException(
                    "failed to create staging workspace",
                    WorkspaceProvisionException.Reason
                            .FILESYSTEM_FAILURE,
                    e
            );
        }
    }
    private void publish(Path stagingRoot,Path finalRoot){
        try{
            Files.move(stagingRoot,finalRoot, StandardCopyOption.ATOMIC_MOVE);
        }catch (AtomicMoveNotSupportedException exception){
            throw new WorkspaceProvisionException("atomic workspace publish is unavailable",
                    WorkspaceProvisionException.Reason
                            .ATOMIC_PUBLISH_UNAVAILABLE,
                    exception);
        }catch(FileAlreadyExistsException exception){
            throw new WorkspaceProvisionException("workspace already exists",
                    WorkspaceProvisionException.Reason
                            .WORKSPACE_CONFLICT,
                    exception);
        }catch(IOException exception){
            throw new WorkspaceProvisionException(
                    "failed to publish workspace",
                    WorkspaceProvisionException.Reason
                            .FILESYSTEM_FAILURE,
                    exception
            );
        }
    }
    private WorkspaceProvisionException mapReadFailure(
            GitObjectReadException exception
    ) {
        return switch (exception.reason()) {
            case NOT_FOUND ->
                    new WorkspaceProvisionException(
                            "snapshot object was not found",
                            WorkspaceProvisionException.Reason
                                    .SNAPSHOT_NOT_FOUND,
                            exception
                    );

            case CORRUPT ->
                    new WorkspaceProvisionException(
                            "snapshot contains corrupt objects",
                            WorkspaceProvisionException.Reason
                                    .SNAPSHOT_CORRUPT,
                            exception
                    );

            case TRANSIENT ->
                    new WorkspaceProvisionException(
                            "snapshot storage is unavailable",
                            WorkspaceProvisionException.Reason
                                    .STORAGE_UNAVAILABLE,
                            exception
                    );
        };
    }

    private void cleanupStaging(
            Path stagingRoot,
            Throwable primaryFailure
    ) {
        if (stagingRoot == null) {
            return;
        }
        try {
            guardedDeleteStaging(stagingRoot);
        } catch (IOException | RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void guardedDeleteStaging(Path stagingRoot) throws IOException {
        Path normalized = stagingRoot.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        if (normalized.equals(workspaceBase)
                || normalized.getParent() == null
                || !normalized.getParent().equals(workspaceBase)
                || fileName == null
                || !fileName.toString().startsWith(STAGING_PREFIX)) {
            throw new WorkspaceProvisionException(
                    "refusing to delete an unsafe staging path",
                    WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE
            );
        }
        if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceProvisionException(
                    "staging path is not a safe directory",
                    WorkspaceProvisionException.Reason.FILESYSTEM_FAILURE
            );
        }

        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException failure
            ) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
