"""Regression checks for data contracts and supervised loss boundaries."""
import os
import copy
import json
import unittest
from pathlib import Path
from catalog import TOOLS
from build_dataset import make_cases
from validate_dataset import validate_row


class DatasetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = make_cases("test", 1)

    def row(self, family):
        return copy.deepcopy(next(r for r in self.rows if r["family"] == family))

    def test_all_generated_contracts(self):
        for row in self.rows:
            with self.subTest(family=row["family"]):
                validate_row(row)

    def test_unknown_document_is_rejected(self):
        row = self.row("workflow_openDocument")
        row["messages"][-1]["content"] = json.dumps({"kind": "tool", "name": "openDocument", "arguments": {"documentId": "invented"}})
        with self.assertRaises(AssertionError):
            validate_row(row)

    def test_disabled_access_rejects_tool(self):
        row = self.row("workflow_openDocument")
        row["state"]["enabled"] = False
        with self.assertRaises(AssertionError):
            validate_row(row)

    def test_unsupported_action_is_rejected(self):
        row = self.row("workflow_openDocument")
        row["messages"][-1]["content"] = json.dumps({"kind": "tool", "name": "deleteEverything", "arguments": {}})
        with self.assertRaises(AssertionError):
            validate_row(row)

    def test_citation_requires_tool_evidence(self):
        row = self.row("wake_reply")
        row["messages"][-1]["content"] = json.dumps({"kind": "reply", "text": "Found it", "citations": ["fake"]})
        with self.assertRaises(AssertionError):
            validate_row(row)

    def test_latest_and_recent_use_different_sort(self):
        latest = json.loads(self.row("latest_resume")["messages"][-1]["content"])
        recent = json.loads(self.row("recent_not_latest")["messages"][-1]["content"])
        self.assertEqual(latest["arguments"]["sort"], "modified_desc")
        self.assertEqual(recent["arguments"]["sort"], "opened_desc")

    def test_order_is_preserved(self):
        row = self.row("ordered_mergePdfDocuments")
        expected = json.loads(row["messages"][-1]["content"])["arguments"]["documentIds"]
        self.assertEqual(expected, list(reversed(row["state"]["selectedDocumentIds"])))

    def test_summary_facts_come_from_excerpt(self):
        row = self.row("grounded_summary")
        evidence = json.loads(next(m["content"] for m in row["messages"] if m["role"] == "tool"))["text"]
        answer = json.loads(row["messages"][-1]["content"])["text"]
        import re
        self.assertTrue(set(re.findall(r"\d+", answer)) <= set(re.findall(r"\d+", evidence)))

    def test_future_adapters_are_marked(self):
        for name in ["searchLibrary", "controlReader", "proposeFilePlan", "cancelAction"]:
            self.assertEqual(TOOLS[name]["status"], "proposed_local_adapter")

    def test_assistant_only_mask_preserves_target(self):
        from transformers import AutoTokenizer
        from train import examples
        tokenizer = AutoTokenizer.from_pretrained(os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")), local_files_only=True)
        data, rejected = examples(tokenizer, [self.row("grounded_summary")], 1536)
        self.assertFalse(rejected)
        self.assertEqual(len(data), 2)
        for ex in data:
            labels = ex["labels"]
            first = next(i for i, x in enumerate(labels) if x != -100)
            self.assertGreater(first, 100)
            self.assertTrue(all(x == -100 for x in labels[:first]))
            self.assertTrue(tokenizer.decode(ex["input_ids"][first:]).startswith('{"kind":'))
            self.assertEqual(labels[first:], ex["input_ids"][first:])
        _, rejected = examples(tokenizer, [self.row("grounded_summary")], 16)
        self.assertEqual(len(rejected), 2)

    def test_capability_snapshot_matches_feature_document(self):
        import hashlib
        root=Path(__file__).resolve().parent
        caps=json.loads((root/"data/app_capabilities.json").read_text(encoding="utf-8"))
        self.assertEqual(caps["feature_document_sha256"],hashlib.sha256((root.parent/"docs/FEATURES.md").read_bytes()).hexdigest())
        self.assertEqual(len(caps["formats"]),14)
        self.assertEqual(next(r for r in caps["formats"] if r["name"]=="EPUB")["extensions"],[".epub"])

    def test_grounded_help_supplies_verified_context(self):
        root=Path(__file__).resolve().parent
        rows=[json.loads(s) for s in (root/"data/grounded_domain_test.jsonl").read_text(encoding="utf-8").splitlines()]
        for row in rows:
            validate_row(row)
            if row["family"].startswith(("domain_help_","domain_format_")):
                self.assertIn("capabilityFacts",row["state"])
                self.assertEqual(json.loads(row["messages"][1]["content"].removeprefix("APP_STATE=")),row["state"])

    def test_quantized_output_schema_rejects_invented_tool(self):
        import jsonschema
        from evaluate_gguf import schema
        row=self.row("workflow_openDocument")
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({"kind":"tool","name":"deleteEverything","arguments":{}},schema(row))
        jsonschema.validate({"kind":"reply","text":"Which file?","citations":[]},schema(row))

    def test_raw_token_prompt_has_single_bos(self):
        from transformers import AutoTokenizer
        tokenizer=AutoTokenizer.from_pretrained(os.environ.get("FILY_BASE_MODEL", os.path.join(os.path.expanduser("~"), "Downloads", "LFM2")),local_files_only=True)
        prompt=tokenizer.apply_chat_template(self.row("wake_reply")["messages"][:-1],tokenize=False,add_generation_prompt=True)
        ids=tokenizer.encode(prompt,add_special_tokens=False)
        self.assertEqual(ids[0],tokenizer.bos_token_id)
        self.assertNotEqual(ids[1],tokenizer.bos_token_id)

    def test_argument_validation_runs_after_decode(self):
        import jsonschema
        from evaluate_gguf import schema
        row=self.row("workflow_openDocument")
        value=json.loads(row["messages"][-1]["content"])
        value["arguments"]["unexpected"]=True
        # Decoder grammar preserves argument order; the execution contract stays strict.
        jsonschema.validate(value,schema(row))
        row["messages"][-1]["content"]=json.dumps(value)
        with self.assertRaises(jsonschema.ValidationError):
            validate_row(row)


if __name__ == "__main__":
    unittest.main()
