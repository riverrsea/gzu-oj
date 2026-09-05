/** 将从一开始的题目序号转换为 A、B、…、AA 形式。 */
export function formatProblemOrdinal(value: number): string {
  let ordinal = Math.max(1, Math.floor(value));
  let label = "";
  while (ordinal > 0) {
    ordinal -= 1;
    label = String.fromCharCode(65 + (ordinal % 26)) + label;
    ordinal = Math.floor(ordinal / 26);
  }
  return label;
}
