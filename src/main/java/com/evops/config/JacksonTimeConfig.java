package com.evops.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;

/**
 * 入参日期/时间严格解析（museum-17）：
 * Jackson/JSR-310 默认 SMART 口径会把不存在的日期（如非闰年 2023-02-29）
 * <b>静默调整</b>为 2023-02-28——等于在展示层偷偷改写采样事实。
 * 这里统一改为 STRICT：非法日历日期直接 400，绝不做"善意平移"。
 * 同时兼容本项目既有的两种分隔写法（ISO 的 'T' 与传统空格）。
 */
@Configuration
public class JacksonTimeConfig {

    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter STRICT_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd")
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictJavaTimeCustomizer() {
        // deserializerByType 优先级高于自动注册的 JavaTimeModule 默认（SMART）反序列化器
        return builder -> builder
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(STRICT_DATE))
                .deserializerByType(LocalDateTime.class,
                        new LocalDateTimeDeserializer(STRICT_DATE_TIME));
    }
}
