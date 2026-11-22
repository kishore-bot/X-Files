import unittest

from eval_harness import numbers_grounded, score

HISTORY = [{"role": "user", "content": "how much is due"},
           {"role": "tool", "content": '{"excerpt":"[page 2] Amount due Rs 1,250 by 12 March"}'}]


class EvalHarnessTest(unittest.TestCase):
    def test_tool_name_and_args(self):
        expected = {"kind": "tool", "name": "searchLibrary", "arguments": {"query": "bill"}}
        s = score(expected, {"kind": "tool", "name": "searchLibrary", "arguments": {"query": "bills"}}, [])
        self.assertTrue(s["tool_name_correct"])
        self.assertFalse(s["tool_args_exact"])
        self.assertFalse(s["exact_match"])

    def test_invented_number_is_not_grounded(self):
        expected = {"kind": "reply", "text": "Rs 1,250 is due.", "citations": []}
        s = score(expected, {"kind": "reply", "text": "Rs 9,999 is due.", "citations": []}, HISTORY)
        self.assertFalse(s["reply_grounded"])
        s = score(expected, {"kind": "reply", "text": "Rs 1,250 is due (page 2).", "citations": []}, HISTORY)
        self.assertTrue(s["reply_grounded"])

    def test_refusal_expected(self):
        expected = {"kind": "reply", "text": "I couldn't find a due date.", "citations": []}
        s = score(expected, {"kind": "reply", "text": "It is due on 5 May.", "citations": []}, HISTORY)
        self.assertFalse(s["refusal_correct"])

    def test_invalid_json(self):
        s = score({"kind": "reply", "text": "x", "citations": []}, None, [])
        self.assertFalse(s["valid_json"])
        self.assertIsNone(s["kind_correct"])

    def test_page_numbers_ignored(self):
        self.assertTrue(numbers_grounded("See page 7.", "nothing numeric"))


if __name__ == "__main__":
    unittest.main()
