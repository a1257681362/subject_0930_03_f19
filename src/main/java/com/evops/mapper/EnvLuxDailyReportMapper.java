package com.evops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.entity.EnvLuxDailyReport;
import org.apache.ibatis.annotations.Select;

public interface EnvLuxDailyReportMapper extends BaseMapper<EnvLuxDailyReport> {

    /** 日报编号取号（H2 序列） */
    @Select("SELECT NEXT VALUE FOR seq_env_lux_daily_report_no")
    Long nextReportNo();
}
