package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.CommitRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface CommitRecordMapper extends BaseMapper<CommitRecord> {

    @Insert("INSERT INTO commit_record (sha1, repo_id, parent_sha1, message, author_id, branch_name, created_at) "
            + "VALUES (#{sha1}, #{repoId}, #{parentSha1}, #{message}, #{authorId}, #{branchName}, #{createdAt}) "
            + "ON DUPLICATE KEY UPDATE sha1 = sha1")
    int insertIfAbsent(CommitRecord record);
}
