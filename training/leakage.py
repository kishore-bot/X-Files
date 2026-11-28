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


# Wording no train row uses, so a reworded held-out row cannot collide with train again. No digits: the
# signature would erase them.
HELD_OUT_PREFIXES = {
    "validation": ["Fily, one request: ", "Fily, here is a task for you: "],
    "test": ["Next request: ", "New task for you: "],
}


def hold_out(row, split, train_near):
    """Keeps every split independent of train without losing coverage.

    Train rows are recorded and returned. A held-out row that only repeats a train row (same words once IDs and
    numbers are erased) is reworded with phrasing train never uses, rather than dropped, so validation and test keep
    an example of every family and tool. Returns (row, reworded), or (None, False) if no rewording separates it.
    """
    near = near_signature(row["messages"])
    if split == "train":
        train_near.add(near)
        return row, False
    if near not in train_near:
        return row, False
    for prefix in HELD_OUT_PREFIXES.get(split, []):
        candidate = json.loads(json.dumps(row))
        turn = next((m for m in candidate["messages"]
                     if m["role"] == "user" and not m["content"].startswith("APP_STATE=")), None)
        if turn is None:
            break
        turn["content"] = prefix + turn["content"][:1].lower() + turn["content"][1:]
        if near_signature(candidate["messages"]) not in train_near:
            return candidate, True
    return None, False
