package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.RepoMember;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RepoMemberMapper extends BaseMapper<RepoMember> {
}
