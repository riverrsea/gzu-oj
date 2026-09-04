/** 前端统一使用中国标准时间，避免浏览器系统时区导致时间显示漂移。 */
export const APP_TIME_ZONE = "Asia/Shanghai";

/** 按中国标准时间格式化后端返回的 ISO 时间戳。 */
export function formatChinaDateTime(value: string, options: Intl.DateTimeFormatOptions = {}): string {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: APP_TIME_ZONE, ...options }).format(new Date(value));
}

/** 将中国标准时间的 datetime-local 值转换为 UTC ISO 时间戳。 */
export function chinaDateTimeInputToIso(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error("时间格式无效");
  const [, year, month, day, hour, minute] = match;
  return new Date(Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute)) - 8 * 60 * 60_000).toISOString();
}

/** 将时间点格式化为中国标准时间的 datetime-local 输入值。 */
export function toChinaDateTimeInputValue(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: APP_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.filter((part) => part.type !== "literal").map((part) => [part.type, part.value]));
  return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}`;
}
