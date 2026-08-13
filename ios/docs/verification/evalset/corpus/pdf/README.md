# PDF 语料（文本层）

`verification.md` 要求至少 4 个**文本型** PDF：短、长、多页、双栏或复杂换行。

| 文件 | 用途 |
|---|---|
| `01_short.pdf` | 短文；OCR 非必需、文档序页码 |
| `02_long.pdf` | 长文锚点；切分页码绑定 |
| `03_multipage.pdf` | 3 页，每页唯一锚点（文档序 1/2/3） |
| `04_complex_layout.pdf` | 双栏；沙箱先于解析 / 发布前不可检索 |

源文在 `sources/*.txt`。重新生成：

```bash
swift ios/docs/verification/evalset/scripts/generate_text_pdfs.swift
```

生成器会用 PDFKit 校验文字层可抽取，并核对多页锚点所在页。

出题时 `expect.evidence[].locator.page` 使用 **PDFKit 文档序**（1-based），不要用 `findString` 事后改绑。
