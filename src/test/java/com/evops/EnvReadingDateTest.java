package com.evops;

import com.evops.config.MuseumTimeProperties;
import com.evops.service.MuseumTimeProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 采样墙钟时间归日口径纯单元测试：
 * 跨零点、DST 间隙（spring-forward）/重叠（fall-back）、闰日。
 * 不依赖 Spring 容器，直接构造 {@link MuseumTimeProvider}（同包可调 @PostConstruct 方法）。
 */
class EnvReadingDateTest {

    private static MuseumTimeProvider time;

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @BeforeAll
    static void setup() {
        MuseumTimeProperties props = new MuseumTimeProperties();
        props.setServerZone("UTC");
        props.setDefaultHallZone("Asia/Shanghai");
        props.init();
        time = new MuseumTimeProvider(props);
        time.verifySystemZone();
    }

    @Test
    void midnight_split_uses_hall_wall_date_not_server_date() {
        // 服务器时区 UTC，但归属按展厅上海墙钟：23:30 与次日 00:30 必须分到两天
        assertEquals(LocalDate.of(2024, 3, 10),
                time.readingDate(LocalDateTime.of(2024, 3, 10, 23, 30), SH));
        assertEquals(LocalDate.of(2024, 3, 11),
                time.readingDate(LocalDateTime.of(2024, 3, 11, 0, 30), SH));
        // 零点整属于新一天
        assertEquals(LocalDate.of(2024, 3, 11),
                time.readingDate(LocalDateTime.of(2024, 3, 11, 0, 0), SH));
        // 同一天 23:59:59 不跨日
        assertEquals(LocalDate.of(2024, 3, 10),
                time.readingDate(LocalDateTime.of(2024, 3, 10, 23, 59, 59), SH));
    }

    @Test
    void shanghai_has_no_dst_offset() {
        // 上海 1991 年后无 DST：当地 00:30 对应 UTC 前一日 16:30（跨零点日界差异的根源）
        assertEquals(Instant.parse("2024-03-10T15:30:00Z"),
                time.toInstant(LocalDateTime.of(2024, 3, 10, 23, 30), SH));
        assertEquals(Instant.parse("2024-03-10T16:30:00Z"),
                time.toInstant(LocalDateTime.of(2024, 3, 11, 0, 30), SH));
    }

    @Test
    void dst_spring_forward_gap_shifts_forward_keeps_local_date() {
        // 纽约 2024-03-10 02:00 EST -> 03:00 EDT：02:30 不存在，前推为 03:30 EDT（UTC 07:30）
        LocalDateTime gap = LocalDateTime.of(2024, 3, 10, 2, 30);
        assertEquals(LocalDate.of(2024, 3, 10), time.readingDate(gap, NY));
        assertEquals(Instant.parse("2024-03-10T07:30:00Z"), time.toInstant(gap, NY));
        // 间隙前一刻 01:59:59 仍 EST
        assertEquals(Instant.parse("2024-03-10T06:59:59Z"),
                time.toInstant(LocalDateTime.of(2024, 3, 10, 1, 59, 59), NY));
        // 柏林 2024-03-31 02:30 间隙 -> 03:30 CEST（UTC 01:30），当地日期不变
        LocalDateTime berlinGap = LocalDateTime.of(2024, 3, 31, 2, 30);
        assertEquals(LocalDate.of(2024, 3, 31), time.readingDate(berlinGap, BERLIN));
        assertEquals(Instant.parse("2024-03-31T01:30:00Z"), time.toInstant(berlinGap, BERLIN));
    }

    @Test
    void dst_fall_back_overlap_uses_earlier_offset() {
        // 纽约 2024-11-03 01:00 EDT -> 01:00 EST：01:30 出现两次，取较早一次 EDT(-4)=UTC 05:30
        LocalDateTime overlap = LocalDateTime.of(2024, 11, 3, 1, 30);
        assertEquals(LocalDate.of(2024, 11, 3), time.readingDate(overlap, NY));
        assertEquals(Instant.parse("2024-11-03T05:30:00Z"), time.toInstant(overlap, NY));
        // 重叠后 02:30 已是 EST(-5)=UTC 07:30
        assertEquals(Instant.parse("2024-11-03T07:30:00Z"),
                time.toInstant(LocalDateTime.of(2024, 11, 3, 2, 30), NY));
        // 跨零点：前一日 23:30 归 11-02（EDT，UTC 03:30 次日，但当地仍是 2 日）
        assertEquals(LocalDate.of(2024, 11, 2),
                time.readingDate(LocalDateTime.of(2024, 11, 2, 23, 30), NY));
    }

    @Test
    void leap_day_assignment() {
        // 2024 闰年：2/29 存在
        assertEquals(LocalDate.of(2024, 2, 29),
                time.readingDate(LocalDateTime.of(2024, 2, 29, 0, 30), SH));
        assertEquals(LocalDate.of(2024, 2, 29),
                time.readingDate(LocalDateTime.of(2024, 2, 29, 23, 59, 59), SH));
        // 2028 闰年
        assertEquals(LocalDate.of(2028, 2, 29),
                time.readingDate(LocalDateTime.of(2028, 2, 29, 12, 0), SH));
        // 2023 平年：2 月最后一天 28 日，23:30 归 28
        assertEquals(LocalDate.of(2023, 2, 28),
                time.readingDate(LocalDateTime.of(2023, 2, 28, 23, 30), SH));
        // 闰日零点后一秒归 3/1
        assertEquals(LocalDate.of(2024, 3, 1),
                time.readingDate(LocalDateTime.of(2024, 3, 1, 0, 0, 1), SH));
        // 纽约闰日本地日期
        assertEquals(LocalDate.of(2024, 2, 29),
                time.readingDate(LocalDateTime.of(2024, 2, 29, 23, 0), NY));
    }

    @Test
    void business_now_uses_configured_server_zone_regardless_of_jvm() {
        // 固定钟：UTC 2024-03-10T16:00Z，服务器时区 UTC，业务 now 为 16:00
        time.setClock(Clock.fixed(Instant.parse("2024-03-10T16:00:00Z"), ZoneOffset.UTC));
        assertEquals(LocalDateTime.of(2024, 3, 10, 16, 0), time.now());
        // 换钟到上海时区视图，同一绝对时刻业务 now 为次日 00:00 —— 证明取时口径由配置决定
        time.setClock(Clock.fixed(Instant.parse("2024-03-10T16:00:00Z"), SH));
        assertEquals(LocalDateTime.of(2024, 3, 11, 0, 0), time.now());
        time.resetClock();
    }

    @Test
    void invalid_zone_rejected() {
        MuseumTimeProperties bad = new MuseumTimeProperties();
        bad.setServerZone("Asia/Not_A_Zone");
        assertThrows(IllegalStateException.class, bad::init);
    }

    @Test
    void blank_hall_zone_falls_back_to_default() {
        assertEquals(SH, time.hallZone(null));
        assertEquals(SH, time.hallZone("  "));
        assertEquals(NY, time.hallZone("America/New_York"));
        assertThrows(IllegalStateException.class, () -> time.hallZone("Mars/Olympus"));
    }
}
