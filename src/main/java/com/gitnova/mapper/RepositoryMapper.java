package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.Repository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 仓库 Mapper — 包含 CAS 乐观锁 SQL
 */
@Mapper
public interface RepositoryMapper extends BaseMapper<Repository> {

    /** Non-authoritative cache update after the default branch CAS has succeeded. */
    @Update("UPDATE repository SET head_commit_sha1 = #{headSha1} WHERE id = #{repoId}")
    int updateDefaultBranchHeadCache(@Param("repoId") Long repoId,
                                     @Param("headSha1") String headSha1);
}
