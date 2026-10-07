package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.EnvLedger;
import com.evops.mapper.EnvLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 环境巡检台账：只追加、不修改、不删除。
 * 规则发布、任务生成、读数、事件开/并/恢复/处置/复核关闭全部在此留痕，
 * 每条尽量固化规则版本号——环境指标与巡检计划全程可追溯。
 */
@Service
public class EnvLedgerService {

    private final EnvLedgerMapper ledgerMapper;
    private final MuseumClock clock;

    public EnvLedgerService(EnvLedgerMapper ledgerMapper, MuseumClock clock) {
        this.ledgerMapper = ledgerMapper;
        this.clock = clock;
    }

    public void append(Long hallId, Long taskId, Long eventId,
                       Long ruleVersionId, String ruleVersionNo,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        EnvLedger entry = new EnvLedger();
        entry.setHallId(hallId);
        entry.setTaskId(taskId);
        entry.setEventId(eventId);
        entry.setRuleVersionId(ruleVersionId);
        entry.setRuleVersionNo(ruleVersionNo);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        // 操作留痕统一服务器时区口径；业务动作（读数/事件）应显式传其采样/发生时间
        entry.setOccurredAt(occurredAt == null ? clock.nowServerWall() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /** 按展厅时间序列查询（环境指标与巡检计划追溯口径） */
    public List<EnvLedger> listByHall(Long hallId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<EnvLedger>()
                .eq(hallId != null, EnvLedger::getHallId, hallId)
                .orderByAsc(EnvLedger::getOccurredAt)
                .orderByAsc(EnvLedger::getId));
    }
}
