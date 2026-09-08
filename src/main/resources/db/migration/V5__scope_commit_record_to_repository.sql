-- commit objects are repository-namespaced; their relational index must use the
-- same identity boundary instead of treating a SHA-1 as globally unique.
ALTER TABLE commit_record
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (id),
    ADD UNIQUE KEY uk_commit_record_repo_sha1 (repo_id, sha1);
