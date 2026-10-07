package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.EnvDailyReport;
import org.apache.ibatis.annotations.Select;

public interface EnvDailyReportMapper extends BaseMapper<EnvDailyReport> {

    /** 日报编号取号（H2 序列），编号在插入前即确定且全库唯一 */
    @Select("SELECT NEXT VALUE FOR seq_env_daily_report_no")
    Long nextReportNo();
}
