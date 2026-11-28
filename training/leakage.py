"""Near-duplicate detection across splits.

The exact check compares whole conversations, and those always differ between splits: every split has its own
entity IDs and its own random distractor tools in the system prompt. A test row that copies a train row with only
the IDs changed therefore passed as "held out". This signature drops the system prompt and replaces every token
that contains a digit (IDs, project names, amounts, dates), so such a row compares equal to its train twin.
"""
import hashlib
import json
import re

ENTITY = re.compile(r"[\w-]*\d[\w-]*")


def near_signature(messages):
    normalized = [(m["role"], " ".join(ENTITY.sub("#", m["content"]).lower().split()))
                  for m in messages if m["role"] != "system"]
    return hashlib.sha256(json.dumps(normalized).encode()).hexdigest()
