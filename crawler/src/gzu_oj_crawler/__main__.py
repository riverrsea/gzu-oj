"""支持 ``python -m gzu_oj_crawler`` 调用。"""

from __future__ import annotations

from .cli import main

if __name__ == "__main__":
    raise SystemExit(main())
