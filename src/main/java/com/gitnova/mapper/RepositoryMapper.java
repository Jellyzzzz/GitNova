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

    /**
     * CAS 更新 HEAD commit SHA-1（乐观锁核心）
     *
     * 只有当前 head_commit_sha1 == baseHeadSha1 时才更新，否则 affected rows = 0
     *
     * @param repoId       仓库 ID
     * @param baseHeadSha1 客户端认为的当前 HEAD
     * @param newHeadSha1  新的 HEAD
     * @return affected rows（0 = CAS 失败 → non-fast-forward）
     */
    @Update("UPDATE repository SET head_commit_sha1 = #{newHeadSha1} "
          + "WHERE id = #{repoId} AND head_commit_sha1 = #{baseHeadSha1}")
    int casUpdateHead(@Param("repoId") Long repoId,
                      @Param("baseHeadSha1") String baseHeadSha1,
                      @Param("newHeadSha1") String newHeadSha1);
}
