package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.Branch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BranchMapper extends BaseMapper<Branch> {

    /**
     * 更新分支 HEAD commit
     */
    @Update("UPDATE branch SET head_commit = #{headCommit} "
          + "WHERE repo_id = #{repoId} AND name = #{branchName}")
    int updateHead(@Param("repoId") Long repoId,
                   @Param("branchName") String branchName,
                   @Param("headCommit") String headCommit);
}
