//! Markdown as a rendered page: GitHub Flavored Markdown (tables, task lists,
//! strikethrough, footnotes, heading ids) in a polished VS-Code-inspired style
//! with light and dark mode, professional typography, and refined code blocks.
//!
//! Raw HTML in the file is kept; the viewer that shows the page runs no scripts
//! and loads nothing from the network, and relative pictures load from the
//! file's folder where the app may read it.

use std::fmt::Write as _;

use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag, TagEnd, html};

/// The resource name the viewer asks for.
pub(crate) const PREVIEW_RESOURCE: &str = "ezfiles:preview.html";

const CSS: &str = "\
:root{--bg:#fff;--fg:#1f2328;--fg-muted:#59636e;--fg-subtle:#818b98;\
--border:#d1d9e0;--border-light:#d1d9e0b3;\
--code-bg:#f6f8fa;--code-border:#e1e4e8;\
--link:#0969da;--blockquote-border:#3b82f6;\
--table-header-bg:#f0f3f6;--table-stripe:#f6f8fa;\
--hl-kw:#0033b3;--hl-st:#067d17;--hl-cm:#6a737d;--hl-nu:#1750eb;--hl-an:#9e880d;--hl-ty:#005cc5;--hl-fn:#6f42c1;--hl-op:#d73a49}\
@media(prefers-color-scheme:dark){:root{\
--bg:#1e1e1e;--fg:#d4d4d4;--fg-muted:#9da5b4;--fg-subtle:#6b737c;\
--border:#3d4450;--border-light:#3d445080;\
--code-bg:#2d2d2d;--code-border:#404040;\
--link:#6cb6ff;--blockquote-border:#6cb6ff;\
--table-header-bg:#272c33;--table-stripe:#242930;\
--hl-kw:#c586c0;--hl-st:#ce9178;--hl-cm:#6a9955;--hl-nu:#b5cea8;--hl-an:#dcdcaa;--hl-ty:#4ec9b0;--hl-fn:#dcdcaa;--hl-op:#d4d4d4}}\
html{-webkit-text-size-adjust:100%}\
body{margin:0;background:var(--bg);color:var(--fg);\
font:16px/1.6 -apple-system,'Segoe UI',Roboto,'Noto Sans',Helvetica,Arial,sans-serif;\
word-wrap:break-word;-webkit-font-smoothing:antialiased}\
.markdown-body{box-sizing:border-box;max-width:820px;margin:0 auto;padding:24px 20px 56px}\
.markdown-body>*:first-child{margin-top:0!important}\
h1,h2,h3,h4,h5,h6{margin:28px 0 16px;font-weight:600;line-height:1.25;letter-spacing:-.01em}\
h1{font-size:2em;padding-bottom:.3em;border-bottom:1px solid var(--border-light)}\
h2{font-size:1.5em;padding-bottom:.3em;border-bottom:1px solid var(--border-light)}\
h3{font-size:1.25em}h4{font-size:1em}h5{font-size:.875em}h6{font-size:.85em;color:var(--fg-muted)}\
p,blockquote,ul,ol,dl,table,pre,details{margin:0 0 16px}\
a{color:var(--link);text-decoration:none}a:hover{text-decoration:underline}\
strong{font-weight:600}em{font-style:italic}\
blockquote{margin-left:0;padding:.5em 1em;color:var(--fg-muted);\
border-left:3px solid var(--blockquote-border);background:var(--code-bg);border-radius:0 6px 6px 0}\
blockquote>:last-child{margin-bottom:0}\
ul,ol{padding-left:2em}li+li{margin-top:.25em}\
li>input[type=checkbox]{margin:0 .35em .25em -1.4em;vertical-align:middle;\
accent-color:var(--link);pointer-events:none}\
ul:has(>li>input[type=checkbox]){list-style:none;padding-left:1.4em}\
hr{height:2px;padding:0;margin:28px 0;background:var(--border-light);border:0;border-radius:1px}\
code,tt{padding:.2em .4em;margin:0;font-size:85%;white-space:break-spaces;\
background:var(--code-bg);border-radius:6px;border:1px solid var(--code-border);\
font-family:ui-monospace,'JetBrains Mono','Roboto Mono','Cascadia Code',Menlo,Consolas,monospace}\
pre{padding:16px;overflow:auto;font-size:13px;line-height:1.5;\
background:var(--code-bg);border-radius:8px;border:1px solid var(--code-border)}\
pre code{padding:0;background:transparent;border:none;font-size:100%;white-space:pre}\
table{border-spacing:0;border-collapse:collapse;display:block;width:max-content;max-width:100%;\
overflow:auto;border-radius:8px;border:1px solid var(--border)}\
th{font-weight:600;background:var(--table-header-bg)}\
th,td{padding:8px 14px;border:1px solid var(--border)}\
tr{background:var(--bg);border-top:1px solid var(--border-light)}\
tr:nth-child(2n){background:var(--table-stripe)}\
img{max-width:100%;box-sizing:content-box;display:block;margin:0 auto;border-radius:6px}\
del{color:var(--fg-muted);text-decoration:line-through}\
sup{font-size:75%;line-height:0;vertical-align:super}\
.footnote-definition{font-size:85%;color:var(--fg-muted);padding:8px 0;border-top:1px solid var(--border-light)}\
.footnote-definition-label{vertical-align:super;font-size:75%;margin-right:6px;color:var(--link);font-weight:600}\
.kw{color:var(--hl-kw)}.st{color:var(--hl-st)}.cm{color:var(--hl-cm);font-style:italic}\
.nu{color:var(--hl-nu)}.an{color:var(--hl-an)}.ty{color:var(--hl-ty)}.fn{color:var(--hl-fn)}";

/// The page for `text`.
pub(crate) fn render(text: &str) -> Vec<u8> {
    let options = Options::ENABLE_TABLES
        | Options::ENABLE_STRIKETHROUGH
        | Options::ENABLE_TASKLISTS
        | Options::ENABLE_FOOTNOTES
        | Options::ENABLE_HEADING_ATTRIBUTES;
    // Fenced code gets coloured spans; everything else is pulldown-cmark's own HTML.
    let mut events = Vec::new();
    let mut code: Option<(String, String)> = None;
    for event in Parser::new_ext(text, options) {
        match (&mut code, event) {
            (None, Event::Start(Tag::CodeBlock(kind))) => {
                let lang = match kind {
                    CodeBlockKind::Fenced(info) => info.split_whitespace().next().unwrap_or("").to_ascii_lowercase(),
                    CodeBlockKind::Indented => String::new(),
                };
                code = Some((lang, String::new()));
            }
            (Some((_, body)), Event::Text(t)) => body.push_str(&t),
            (Some(_), Event::End(TagEnd::CodeBlock)) => {
                let (lang, body) = code.take().unwrap_or_default();
                let mut html = String::from("<pre><code");
                if !lang.is_empty() {
                    let _ = write!(html, " class=\"language-{}\"", escape(&lang));
                }
                html.push('>');
                highlight(&lang, &body, &mut html);
                html.push_str("</code></pre>\n");
                events.push(Event::Html(html.into()));
            }
            (Some(_), _) => {}
            (None, event) => events.push(event),
        }
    }
    let mut body = String::with_capacity(text.len() * 3 / 2);
    html::push_html(&mut body, events.into_iter());
    let mut out = String::with_capacity(body.len() + CSS.len() + 256);
    let _ = write!(
        out,
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>{CSS}</style></head><body><article class=\"markdown-body\">{body}</article></body></html>"
    );
    out.into_bytes()
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            c => out.push(c),
        }
    }
    out
}

const KEYWORDS: &[&str] = &[
    "abstract",
    "actual",
    "and",
    "as",
    "async",
    "await",
    "break",
    "case",
    "catch",
    "class",
    "companion",
    "const",
    "continue",
    "data",
    "def",
    "default",
    "defer",
    "del",
    "do",
    "elif",
    "else",
    "enum",
    "except",
    "expect",
    "export",
    "extends",
    "extern",
    "false",
    "final",
    "finally",
    "fn",
    "for",
    "from",
    "fun",
    "func",
    "function",
    "go",
    "if",
    "impl",
    "implements",
    "import",
    "in",
    "inline",
    "interface",
    "internal",
    "is",
    "lambda",
    "let",
    "loop",
    "match",
    "mod",
    "module",
    "mut",
    "new",
    "nil",
    "None",
    "not",
    "null",
    "object",
    "open",
    "or",
    "override",
    "package",
    "pass",
    "private",
    "protected",
    "pub",
    "public",
    "raise",
    "return",
    "sealed",
    "self",
    "Self",
    "static",
    "struct",
    "super",
    "suspend",
    "switch",
    "this",
    "throw",
    "throws",
    "trait",
    "True",
    "False",
    "true",
    "try",
    "type",
    "typeof",
    "use",
    "val",
    "var",
    "void",
    "when",
    "where",
    "while",
    "with",
    "yield",
    "crate",
    "ref",
    "dyn",
    "unsafe",
    "const",
    "readonly",
    "echo",
    "then",
    "fi",
    "esac",
    "done",
    "local",
    "export",
    "select",
    "SELECT",
    "FROM",
    "WHERE",
    "INSERT",
    "UPDATE",
    "DELETE",
    "CREATE",
    "TABLE",
    "JOIN",
    "ON",
    "AND",
    "OR",
    "NOT",
    "NULL",
    "ORDER",
    "BY",
    "GROUP",
    "INTO",
    "VALUES",
];

/// A light, language-agnostic colouring: comments, strings, numbers,
/// keywords, annotations and capitalised type names.
fn highlight(lang: &str, code: &str, out: &mut String) {
    let hash_comments = matches!(
        lang,
        "py" | "python"
            | "sh"
            | "bash"
            | "zsh"
            | "shell"
            | "yaml"
            | "yml"
            | "toml"
            | "ruby"
            | "rb"
            | "r"
            | "perl"
            | "ini"
            | "properties"
            | "dockerfile"
            | "makefile"
    );
    let dash_comments = matches!(lang, "sql" | "lua" | "haskell" | "hs");
    let plain = matches!(lang, "" | "text" | "txt" | "plain" | "markdown" | "md" | "diff" | "csv");
    let chars: Vec<char> = code.chars().collect();
    let mut i = 0;
    let span = |out: &mut String, class: &str, s: &str| {
        let _ = write!(out, "<span class=\"{class}\">{}</span>", escape(s));
    };
    while i < chars.len() {
        let c = chars[i];
        let rest_starts = |p: &str| p.chars().enumerate().all(|(k, pc)| chars.get(i + k) == Some(&pc));
        if plain {
            out.push_str(&escape(&c.to_string()));
            i += 1;
            continue;
        }
        let line_comment = rest_starts("//") || (hash_comments && c == '#') || (dash_comments && rest_starts("--"));
        if line_comment {
            let end = chars[i..].iter().position(|&x| x == '\n').map_or(chars.len(), |p| i + p);
            span(out, "cm", &chars[i..end].iter().collect::<String>());
            i = end;
        } else if rest_starts("/*") || rest_starts("<!--") {
            let close = if c == '/' { "*/" } else { "-->" };
            let text: String = chars[i..].iter().collect();
            let end = text[2..].find(close).map_or(text.len(), |p| p + 2 + close.len());
            let piece: String = text[..end].to_owned();
            i += piece.chars().count();
            span(out, "cm", &piece);
        } else if c == '"' || c == '\'' || c == '`' {
            let mut j = i + 1;
            while j < chars.len() && chars[j] != c && !(c != '`' && chars[j] == '\n') {
                if chars[j] == '\\' {
                    j += 1;
                }
                j += 1;
            }
            let end = (j + 1).min(chars.len());
            span(out, "st", &chars[i..end].iter().collect::<String>());
            i = end;
        } else if c.is_ascii_digit() && (i == 0 || !(chars[i - 1].is_alphanumeric() || chars[i - 1] == '_')) {
            let mut j = i;
            while j < chars.len() && (chars[j].is_ascii_alphanumeric() || chars[j] == '.' || chars[j] == '_') {
                j += 1;
            }
            span(out, "nu", &chars[i..j].iter().collect::<String>());
            i = j;
        } else if c == '@' && chars.get(i + 1).is_some_and(|n| n.is_alphabetic()) {
            let mut j = i + 1;
            while j < chars.len() && (chars[j].is_alphanumeric() || chars[j] == '_' || chars[j] == '.') {
                j += 1;
            }
            span(out, "an", &chars[i..j].iter().collect::<String>());
            i = j;
        } else if c.is_alphabetic() || c == '_' {
            let mut j = i;
            while j < chars.len() && (chars[j].is_alphanumeric() || chars[j] == '_') {
                j += 1;
            }
            let word: String = chars[i..j].iter().collect();
            if KEYWORDS.contains(&word.as_str()) {
                span(out, "kw", &word);
            } else if word.len() >= 2
                && word.as_bytes()[0].is_ascii_uppercase()
                && word[1..].chars().any(|c| c.is_ascii_lowercase())
            {
                span(out, "ty", &word);
            } else if chars.get(j) == Some(&'(') {
                span(out, "fn", &word);
            } else {
                out.push_str(&escape(&word));
            }
            i = j;
        } else {
            out.push_str(&escape(&c.to_string()));
            i += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::render;

    fn page(md: &str) -> String {
        String::from_utf8(render(md)).unwrap()
    }

    #[test]
    fn github_flavoured_markdown_renders() {
        let html = page(
            "# Title\n\n- [x] done\n- [ ] todo\n\n| a | b |\n|---|--:|\n| 1 | 2 |\n\n~~old~~ text[^1]\n\n[^1]: note\n",
        );
        assert!(html.contains("<h1>Title</h1>"));
        assert!(html.contains("type=\"checkbox\"") && html.contains("checked"));
        assert!(html.contains("<table>") && html.contains("style=\"text-align: right\""));
        assert!(html.contains("<del>old</del>"));
        assert!(html.contains("footnote-definition"));
        assert!(html.contains("class=\"markdown-body\""));
    }

    #[test]
    fn fenced_code_is_coloured_and_escaped() {
        let html = page("```kotlin\n@Composable\nfun main() { val s = \"<b>\" // hi\n  return 42 }\n```\n");
        assert!(html.contains("<code class=\"language-kotlin\">"));
        assert!(html.contains("<span class=\"an\">@Composable</span>"));
        assert!(html.contains("<span class=\"kw\">fun</span>"));
        assert!(html.contains("<span class=\"fn\">main</span>"));
        assert!(html.contains("<span class=\"st\">&quot;&lt;b&gt;&quot;</span>"));
        assert!(html.contains("<span class=\"cm\">// hi</span>"));
        assert!(html.contains("<span class=\"nu\">42</span>"));
        let plain = page("```\n<script>x</script>\n```\n");
        assert!(plain.contains("&lt;script&gt;"));
    }

    #[test]
    fn type_names_and_dark_mode_css() {
        let html = page("```rust\nlet m: HashMap<String, Vec<i32>> = HashMap::new();\n```\n");
        assert!(html.contains("<span class=\"ty\">HashMap</span>"));
        assert!(html.contains("<span class=\"ty\">String</span>"));
        assert!(html.contains("<span class=\"ty\">Vec</span>"));
        assert!(html.contains("<span class=\"kw\">let</span>"));
        assert!(html.contains("prefers-color-scheme:dark"), "dark mode CSS must be present");
        assert!(html.contains("--hl-kw"), "CSS custom properties must be present");
    }
}
