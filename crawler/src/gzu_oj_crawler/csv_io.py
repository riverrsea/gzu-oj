"""CSV 读写的公共约定。

Kotlin 版使用 Apache Commons CSV 的 ``CSVFormat.DEFAULT``：逗号分隔、最小引用、
CRLF 换行、UTF-8 编码。这里保持一致，保证两版产物可以互相替代。
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterable, Sequence
from pathlib import Path


def csv_bytes(header: Sequence[str], rows: Iterable[Sequence[object]]) -> bytes:
    """把表头和数据行序列化为 CRLF 换行的 UTF-8 CSV 字节。"""
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer)
    writer.writerow(list(header))
    for row in rows:
        writer.writerow(["" if value is None else value for value in row])
    return buffer.getvalue().encode("utf-8")


def write_csv(path: Path, header: Sequence[str], rows: Iterable[Sequence[object]]) -> None:
    """覆盖写入 CSV，并确保父目录存在。"""
    normalized = path.absolute()
    normalized.parent.mkdir(parents=True, exist_ok=True)
    normalized.write_bytes(csv_bytes(header, rows))
