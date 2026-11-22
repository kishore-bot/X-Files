//! Spawns the built binary, speaks newline-delimited JSON-RPC over its stdio, and checks the
//! initialize, tools/list and tools/call responses.

use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

use serde_json::{Value, json};

const FIXTURE_TEXT: &str = "Quarterly invoice summary.\nTotal due: 1234.50 EUR.\n";

fn request_lines(root: &Path, fixture: &Path, outside: &Path) -> String {
    let messages = [
        json!({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": { "name": "xfiles-protocol-test", "version": "0.0.0" }
            }
        }),
        json!({ "jsonrpc": "2.0", "method": "notifications/initialized" }),
        json!({ "jsonrpc": "2.0", "id": 2, "method": "ping" }),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "tools/list" }),
        json!({
            "jsonrpc": "2.0", "id": 4, "method": "tools/call",
            "params": { "name": "read_document", "arguments": { "path": fixture.to_string_lossy(), "max_chars": 1000 } }
        }),
        json!({
            "jsonrpc": "2.0", "id": 5, "method": "tools/call",
            "params": { "name": "read_document", "arguments": { "path": outside.to_string_lossy() } }
        }),
        json!({
            "jsonrpc": "2.0", "id": 6, "method": "tools/call",
            "params": { "name": "pdf_merge", "arguments": { "inputs": [fixture.to_string_lossy()], "output": root.join("x.pdf").to_string_lossy() } }
        }),
    ];
    messages.iter().map(|m| m.to_string()).collect::<Vec<_>>().join("\n") + "\n"
}

fn response_for(stdout: &str, id: i64) -> Value {
    stdout
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| serde_json::from_str::<Value>(line).expect("server wrote valid JSON"))
        .find(|value| value["id"] == json!(id))
        .unwrap_or_else(|| panic!("no response with id {id} in:\n{stdout}"))
}

#[test]
fn speaks_mcp_over_stdio_and_reads_a_document() {
    let base = tempfile::tempdir().unwrap();
    let root = base.path().join("root");
    let outside_dir = base.path().join("outside");
    std::fs::create_dir_all(&root).unwrap();
    std::fs::create_dir_all(&outside_dir).unwrap();
    let fixture = root.join("invoice.txt");
    std::fs::write(&fixture, FIXTURE_TEXT).unwrap();
    let outside = outside_dir.join("secret.txt");
    std::fs::write(&outside, "not for you").unwrap();

    let mut child = Command::new(env!("CARGO_BIN_EXE_xfiles-mcp"))
        .arg("--root")
        .arg(&root)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("binary starts");

    {
        let mut stdin = child.stdin.take().expect("stdin piped");
        stdin
            .write_all(request_lines(&root, &fixture, &outside).as_bytes())
            .expect("requests written");
    }
    let output = child.wait_with_output().expect("server exits when stdin closes");
    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();

    let init = response_for(&stdout, 1);
    assert_eq!(init["result"]["serverInfo"]["name"], "xfiles-mcp");
    assert!(init["result"]["capabilities"]["tools"].is_object(), "tools capability advertised");

    assert!(response_for(&stdout, 2)["result"].is_object(), "ping answered");

    let listed = response_for(&stdout, 3);
    let names: Vec<&str> = listed["result"]["tools"]
        .as_array()
        .expect("tools array")
        .iter()
        .filter_map(|tool| tool["name"].as_str())
        .collect();
    for expected in ["read_document", "search_document", "glance", "pdf_merge", "pdf_split", "office_find_replace", "list_archive"] {
        assert!(names.contains(&expected), "tool {expected} is listed: {names:?}");
    }
    let read_schema = listed["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .find(|tool| tool["name"] == "read_document")
        .unwrap();
    assert!(read_schema["inputSchema"]["properties"]["path"].is_object());

    let read = response_for(&stdout, 4);
    assert_ne!(read["result"]["isError"], json!(true), "read succeeds: {read}");
    let structured = &read["result"]["structuredContent"];
    assert_eq!(structured["kind"], "text");
    assert!(structured["text"].as_str().unwrap().contains("Total due: 1234.50 EUR"));

    let refused = response_for(&stdout, 5);
    assert_eq!(refused["result"]["isError"], json!(true), "outside path is a tool error: {refused}");
    assert!(refused["result"]["content"][0]["text"].as_str().unwrap().contains("outside the allowed folders"));

    let merge_without_outputs = response_for(&stdout, 6);
    assert_eq!(merge_without_outputs["result"]["isError"], json!(true), "one input is refused: {merge_without_outputs}");
}

#[test]
fn refuses_to_start_without_a_root() {
    let output = Command::new(env!("CARGO_BIN_EXE_xfiles-mcp"))
        .stdin(Stdio::null())
        .output()
        .expect("binary runs");
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("--root"));
    assert!(output.stdout.is_empty(), "nothing written to stdout");
}
