package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.Branch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BranchMapper extends BaseMapper<Branch> {

    @Select("SELECT head_commit FROM branch WHERE repo_id = #{repoId} AND name = #{branchName}")
    String findHead(@Param("repoId") Long repoId, @Param("branchName") String branchName);

    @Update("UPDATE branch SET head_commit = #{newHead} "
          + "WHERE repo_id = #{repoId} AND name = #{branchName} AND head_commit = #{expectedHead}")
    int compareAndSetHead(@Param("repoId") Long repoId,
                          @Param("branchName") String branchName,
                          @Param("expectedHead") String expectedHead,
                          @Param("newHead") String newHead);
}
