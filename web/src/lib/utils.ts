import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** 合并条件类名并让 Tailwind 后声明的工具类覆盖冲突项。 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
