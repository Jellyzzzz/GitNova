package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.RepoMember;
import com.gitnova.entity.Repository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RepoMemberMapper extends BaseMapper<RepoMember> {
    @Select("""
    select r.* from repository r
    join repo_member rm on rm.repo_id=r.id
    where rm.user_id=#{userId}
    order by r.created_at desc
    """)
    List<Repository> selectByReposUserId(@Param("userId")Long userId);
}
