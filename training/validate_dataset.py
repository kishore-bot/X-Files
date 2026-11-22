"""Validate schema, grounding references, authorization and exact split leakage."""
import hashlib
import json
from collections import Counter
from pathlib import Path
import jsonschema
from catalog import TOOLS

ROOT = Path(__file__).resolve().parent


def validate_row(row):
    allowed = set(row["state"].get("authorizedIds", []))
    assert row["messages"][-1]["role"] == "assistant", row["id"]
    assert row["messages"][0]["role"] == "system"
    enabled = row["state"]["enabled"]
    evidence = set()
    for message in row["messages"][2:]:
        if message["role"] == "tool":
            result = json.loads(message["content"])
            if result.get("enabled") is False:
                enabled = False
            if result.get("documentId"):
                evidence.add(result["documentId"])
            for doc in result.get("documents", []):
                allowed.add(doc["id"])
                evidence.add(doc["id"])
        if message["role"] != "assistant":
            continue
        output = json.loads(message["content"])
        kind = output["kind"]
        assert kind in {"tool", "reply", "clarify"}
        if kind == "tool":
            assert enabled, (row["id"], "action while disabled")
            name, args = output["name"], output["arguments"]
            assert name in row["tools"] and name in TOOLS
            jsonschema.validate(args, TOOLS[name]["parameters"])
            ids = args.get("documentIds", []) + ([args["documentId"]] if "documentId" in args else [])
            assert set(ids) <= allowed, (row["id"], "invented ID")
            if name in {"getDocumentExcerpt", "findTextInDocument"}:
                assert row["state"]["contentAllowed"]
            assert set(output) == {"kind", "name", "arguments"}
        else:
            assert output["text"].strip()
            assert set(output) == ({"kind", "text", "citations"} if kind == "reply" else {"kind", "text"})
            if kind == "reply":
                assert set(output["citations"]) <= evidence, (row["id"], "citation without evidence")


def main():
    seen_ids, hashes = set(), {}
    report = {}
    for split in ["train", "validation", "test"]:
        rows = [json.loads(s) for s in (ROOT / "data" / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
        tools, families, kinds = Counter(), Counter(), Counter()
        for row in rows:
            assert row["id"] not in seen_ids
            seen_ids.add(row["id"])
            validate_row(row)
            families[row["family"]] += 1
            for m in row["messages"]:
                if m["role"] == "assistant":
                    value = json.loads(m["content"])
                    kinds[value["kind"]] += 1
                    if value["kind"] == "tool":
                        tools[value["name"]] += 1
            signature = hashlib.sha256(json.dumps(row["messages"], sort_keys=True).encode()).hexdigest()
            assert signature not in hashes or hashes[signature] == split, "Cross-split duplicate"
            hashes[signature] = split
        assert set(tools) == set(TOOLS), (split, set(TOOLS) - set(tools))
        report[split] = {"conversations": len(rows), "families": len(families), "tools_covered": len(tools), "outputs": dict(kinds)}
    (ROOT / "data" / "validation_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
