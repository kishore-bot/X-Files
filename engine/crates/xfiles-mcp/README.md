# xfiles-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) server that exposes the Xfiles document
engine (`engine/crates/document-parsers`) to desktop AI clients such as Claude Desktop and Claude Code.
It speaks MCP over stdio and calls the parsers directly, not through UniFFI.

It is a host-only binary. The Android build (`document-ffi`) does not depend on it, and the
workspace `default-members` list leaves it out, so a plain `cargo build` in `engine/` skips it.

## Build and run

```sh
cd engine
cargo build --release -p xfiles-mcp
# binary: engine/target/release/xfiles-mcp  (xfiles-mcp.exe on Windows)

# Every file the server touches must be under a --root folder. Repeat --root for more folders.
target/release/xfiles-mcp --root "C:\Users\me\Documents" --root "D:\Scans"
```

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--root <folder>` | required | Folder the server may read and write. Repeatable. |
| `--timeout-secs <n>` | 120 | Stop one operation after n seconds (1-3600). |
| `--max-input-mb <n>` | 256 | Refuse input files larger than n MB (1-2048). |
| `--version`, `--help` | | Print and exit. |

Standard output carries protocol messages only. Diagnostics go to stderr.

## Tools

| Tool | What it does |
| --- | --- |
| `read_document` | Text of DOCX, PPTX, XLSX, ODT, ODP, ODS, EPUB, TXT, MD, CSV, HTML, RTF or ZIP listings. Character windows with `next_start_char`. Optional `from_page`/`to_page` (slides, sheets or sections). |
| `search_document` | Case-insensitive search with total count, page number and snippets. |
| `glance` | Key sentences, keywords, dates, amounts, emails, phones, links, reference numbers, reading time, language. |
| `pdf_merge` | Join 2 to 50 PDFs. |
| `pdf_split` | Split by `ranges`, every N pages, or per page, into an existing folder. A publication failure after some parts are saved returns those parts with `complete: false`. |
| `pdf_compress` | `lossless`, `balanced` or `strong` image quality. |
| `pdf_reorder` | Reorder every page once, with optional turns. |
| `pdf_rotate` | Turn selected pages by multiples of 90 degrees. |
| `pdf_watermark` | Text watermark with size, opacity, angle, colour, position or tiling. |
| `pdf_protect` | AES-256 user password. |
| `office_find_replace` | Preview counts and samples (default), or write a new file with replacements applied and re-open it to verify. |
| `list_archive` | ZIP entry listing. |
| `extract_archive_entry` | Extract one ZIP entry to a new file. |

Safety rules that apply to every tool:

- Inputs are only read. Every change goes to a new output path.
- An existing output is refused unless `overwrite` is true. An output never replaces an input.
- Paths resolve under a `--root`, following symbolic links. Anything outside is refused.
- Outputs are written to a temporary file and renamed only on success.
- Each operation has a time limit and a cancel flag. Input size is capped.
- Failures come back as tool results with `isError: true` and a readable message.

Known gap: the engine has no PDF text layer. `read_document`, `search_document` and `glance` refuse
PDFs with a clear message. The `pdf_*` tools do work on PDFs.

## Claude Desktop

Add the server to `claude_desktop_config.json`
(Windows: `%APPDATA%\Claude\claude_desktop_config.json`; macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "xfiles": {
      "command": "C:\\Users\\me\\Documents\\projects\\Xfiles\\engine\\target\\release\\xfiles-mcp.exe",
      "args": ["--root", "C:\\Users\\me\\Documents", "--root", "D:\\Scans"]
    }
  }
}
```

Restart Claude Desktop after editing the file.

## Claude Code

```sh
claude mcp add xfiles -- "C:\Users\me\Documents\projects\Xfiles\engine\target\release\xfiles-mcp.exe" --root "C:\Users\me\Documents"
```

Check it with `claude mcp list`, or `/mcp` inside a session.

## Tests

```sh
cd engine
cargo test -p xfiles-mcp
```

Unit tests cover the path sandbox (including symbolic links), output rules, argument validation,
text windows and search. The integration test starts the binary, runs `initialize`, `ping`,
`tools/list` and `tools/call`, and checks an out-of-root refusal.
