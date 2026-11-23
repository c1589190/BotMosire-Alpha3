package io.github.c1589190.bma3.scheduler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Five-field cron subset: {@code minute hour day-of-month month day-of-week}.
 *
 * <p>Supported syntax: {@code *}, {@code n}, {@code a-b}, {@code *}{@code /n}, {@code a-b/n},
 * {@code a/n} and comma lists. No seconds field, no year field, no {@code @reboot}. Field ranges
 * follow the classic crontab ({@code dow} accepts 0-7 where 7 is Sunday).</p>
 *
 * <p>Day matching follows the standard rule: when both day-of-month and day-of-week are restricted,
 * a day matches if <b>either</b> matches.</p>
 */
public final class CronExpression {

    private static final int MAX_YEARS_AHEAD = 4;

    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32];
    private final boolean[] months = new boolean[13];
    private final boolean[] daysOfWeek = new boolean[7];
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String raw;

    private CronExpression(String raw, boolean domRestricted, boolean dowRestricted) {
        this.raw = raw;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron 表达式不能为空");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        String[] fields = normalized.split(" ");
        if (fields.length != 5) {
            throw new IllegalArgumentException(
                    "cron 必须是 5 个字段（分 时 日 月 周），收到: " + normalized);
        }
        CronExpression cron = new CronExpression(normalized,
                !"*".equals(fields[2]), !"*".equals(fields[4]));
        cron.fill(cron.minutes, fields[0], 0, 59, "minute");
        cron.fill(cron.hours, fields[1], 0, 23, "hour");
        cron.fill(cron.daysOfMonth, fields[2], 1, 31, "day-of-month");
        cron.fill(cron.months, fields[3], 1, 12, "month");
        boolean[] dow = new boolean[8];
        cron.fill(dow, fields[4], 0, 7, "day-of-week");
        for (int i = 0; i <= 7; i++) {
            if (dow[i]) {
                cron.daysOfWeek[i % 7] = true;
            }
        }
        return cron;
    }

    /** Next matching instant strictly after {@code after}, evaluated in {@code zone}. */
    public Instant next(Instant after, ZoneId zone) {
        ZoneId effective = zone == null ? ZoneId.systemDefault() : zone;
        ZonedDateTime cursor = after.atZone(effective)
                .plusMinutes(1).withSecond(0).withNano(0);
        int limit = MAX_YEARS_AHEAD * 366 * 24 * 60;
        for (int step = 0; step < limit; step++) {
            if (!months[cursor.getMonthValue()]) {
                cursor = cursor.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!dayMatches(cursor)) {
                cursor = cursor.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!hours[cursor.getHour()]) {
                cursor = cursor.plusHours(1).withMinute(0);
                continue;
            }
            if (!minutes[cursor.getMinute()]) {
                cursor = cursor.plusMinutes(1);
                continue;
            }
            return cursor.toInstant();
        }
        throw new IllegalStateException("cron 在未来 " + MAX_YEARS_AHEAD + " 年内没有匹配时刻: " + raw);
    }

    private boolean dayMatches(ZonedDateTime time) {
        boolean dom = daysOfMonth[time.getDayOfMonth()];
        boolean dow = daysOfWeek[time.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) {
            return dom || dow;
        }
        if (domRestricted) {
            return dom;
        }
        return !dowRestricted || dow;
    }

    private void fill(boolean[] target, String field, int min, int max, String label) {
        for (String part : field.split(",")) {
            fillPart(target, part.trim(), min, max, label);
        }
    }

    private void fillPart(boolean[] target, String part, int min, int max, String label) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException("cron " + label + " 字段为空");
        }
        int step = 1;
        String range = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            try {
                step = Integer.parseInt(part.substring(slash + 1));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("cron " + label + " 步长非法: " + part);
            }
            if (step < 1) {
                throw new IllegalArgumentException("cron " + label + " 步长必须 ≥ 1: " + part);
            }
        }
        int from;
        int to;
        if ("*".equals(range)) {
            from = min;
            to = max;
        } else if (range.contains("-")) {
            String[] bounds = range.split("-", 2);
            from = number(bounds[0], min, max, label);
            to = number(bounds[1], min, max, label);
            if (from > to) {
                throw new IllegalArgumentException("cron " + label + " 范围反向: " + part);
            }
        } else {
            from = number(range, min, max, label);
            to = slash >= 0 ? max : from;
        }
        for (int value = from; value <= to; value += step) {
            target[value] = true;
        }
    }

    private static int number(String raw, int min, int max, String label) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("cron " + label + " 不是数字: " + raw);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "cron " + label + " 超出 " + min + ".." + max + ": " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return raw;
    }
}
