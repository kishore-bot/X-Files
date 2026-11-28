package com.xfiles.documents.code

/**
 * What the engine opens as source code: keep in step with TextDialect in
 * engine/crates/document-parsers/src/text.rs, or the library hides files the reader can show.
 */
val SourceCodeExtensions: Set<String> = setOf(
    "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "go", "rs", "c",
    "h", "cpp", "cc", "cxx", "hpp", "cs", "swift", "m", "mm", "php", "sh", "bash", "zsh",
    "fish", "ps1", "bat", "cmd", "sql", "yaml", "yml", "toml", "ini", "cfg", "conf", "gradle",
    "xml", "xsd", "svg", "plist", "css", "scss", "sass", "less", "dart", "lua", "r", "scala",
    "pl", "vue", "svelte", "properties", "env", "proto", "graphql", "gql", "tf", "hs", "ex",
    "exs", "erl", "clj", "jl", "zig", "nim", "v", "asm", "s", "diff", "patch", "tex", "jsonl",
    "ndjson",
)

/** Code files the engine recognises by their whole name, which have no extension. */
val SourceCodeFileNames: Set<String> = setOf("dockerfile", "makefile", "jenkinsfile", "gemfile", "rakefile")
