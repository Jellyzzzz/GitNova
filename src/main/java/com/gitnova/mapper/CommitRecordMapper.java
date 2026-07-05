package com.gitnova.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gitnova.entity.CommitRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommitRecordMapper extends BaseMapper<CommitRecord> {
}
