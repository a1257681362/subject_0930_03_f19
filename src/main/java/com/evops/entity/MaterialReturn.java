package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退料单：必须关联原领用单与原明细行，库存加回原批号。
 * 允许分次退料，但同一行累计退料不得超过该行实际用量；退料只追加，不改写原领用事实。
 *
 * <p>退料单号是幂等键：仓管重试/双击/异常重放复用同一单号时命中同一条记录并原样
 * 回放，绝不重复加回库存、不重复写台账；{@code requestDigest} 固化首次请求摘要，
 * 同单号不同内容的提交视为单号冲突，原操作人与首次请求摘要保持不变。</p>
 */
@Data
@TableName("t_material_return")
public class MaterialReturn {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退料单号，全局唯一 */
    private String returnNo;

    /** 原领用单 */
    private Long usageId;

    /** 原领用明细行 */
    private Long lineId;

    /** 库存加回的原批号 */
    private Long batchId;

    private Long projectId;

    private Long relicId;

    private BigDecimal returnQty;

    private String reason;

    /** 退料操作者（首次提交者；重试/重放不覆盖） */
    private String returnedBy;

    private LocalDateTime returnedAt;

    /** 首次请求摘要（单号+领用单+明细行+数量+操作者的 SHA-256），用于识别重放与单号冲突 */
    private String requestDigest;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
