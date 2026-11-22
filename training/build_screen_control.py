"""Screen-control curriculum (edge v6): edge v5 plus controlReader on every viewer the app exposes.

The app (shared/.../ReaderControl.kt and the viewers) supports exactly the capability sets below and
answers quick commands with the same replies, so training and runtime agree. Edge v5 rows are kept,
with their system prompts re-rendered from the current catalog so old and new rows share one schema.
"""
import json
import random
from collections import Counter
from pathlib import Path

from build_dataset import conversation, msg, reply, tool
from catalog import system

ROOT = Path(__file__).resolve().parent
SEED = 16112026

# What each viewer registers in the app, in catalog order.
VIEWERS = {
    "pdf": ["next_page", "previous_page", "go_to_page", "zoom_in", "zoom_out", "scroll_down", "scroll_up", "scroll_top", "scroll_bottom"],
    "slides": ["next_page", "previous_page", "go_to_page", "scroll_top", "scroll_bottom"],
    "photos": ["zoom_in", "zoom_out", "rotate_photo", "next_photo", "previous_photo"],
    "sheet": ["scroll_down", "scroll_up", "scroll_top", "scroll_bottom", "select_cell", "next_sheet", "previous_sheet"],
    "text": ["scroll_down", "scroll_up", "scroll_top", "scroll_bottom"],
    "media": ["play", "pause", "seek"],
}

# (operation, value or None, phrasings by split). Values are what the app's viewers accept.
REQUESTS = [
    ("next_page", None, {"train": ["next page", "go to the next page", "turn the page", "show me the following page", "move to the next slide"],
                         "validation": ["flip to the next page"], "test": ["advance one page"]}),
    ("previous_page", None, {"train": ["previous page", "go back a page", "back one slide", "show the page before this"],
                             "validation": ["go to the prior page"], "test": ["one page back"]}),
    ("go_to_page", 7, {"train": ["go to page 7", "jump to page seven", "open page 7", "take me to slide 7"],
                       "validation": ["show page number seven"], "test": ["page seven please"]}),
    ("zoom_in", None, {"train": ["zoom in", "make it bigger", "enlarge the view", "I can't read this, zoom in"],
                       "validation": ["magnify this"], "test": ["closer please"]}),
    ("zoom_out", None, {"train": ["zoom out", "make it smaller", "show more of the page"],
                        "validation": ["shrink the view"], "test": ["pull back a bit"]}),
    ("scroll_down", None, {"train": ["scroll down", "keep going down", "show me further down", "move down a bit"],
                           "validation": ["go further down the page"], "test": ["scroll lower"]}),
    ("scroll_up", None, {"train": ["scroll up", "go back up a bit", "move up"], "validation": ["scroll back up"], "test": ["up a little"]}),
    ("scroll_top", None, {"train": ["go to the top", "scroll to the beginning", "back to the start"],
                          "validation": ["jump to the top"], "test": ["all the way up"]}),
    ("scroll_bottom", None, {"train": ["go to the bottom", "scroll to the end", "jump to the end"],
                             "validation": ["all the way down"], "test": ["show the very end"]}),
    ("select_cell", "B3", {"train": ["select B3", "click on cell B3", "tap cell B 3", "go to cell B3", "show me what's in B3"],
                           "validation": ["highlight cell B3"], "test": ["open cell B three"]}),
    ("next_sheet", None, {"train": ["next sheet", "go to the next tab", "show the following sheet"],
                          "validation": ["switch to the next worksheet"], "test": ["next tab please"]}),
    ("previous_sheet", None, {"train": ["previous sheet", "go back one tab", "show the sheet before this"],
                              "validation": ["switch to the previous worksheet"], "test": ["prior tab"]}),
    ("next_photo", None, {"train": ["next photo", "show the next picture", "swipe to the next image"],
                          "validation": ["following photo"], "test": ["next pic"]}),
    ("previous_photo", None, {"train": ["previous photo", "go back to the last picture", "show the image before"],
                              "validation": ["prior photo"], "test": ["back one picture"]}),
    ("rotate_photo", 90, {"train": ["rotate this photo", "turn the picture clockwise", "rotate it"],
                          "validation": ["spin the image a quarter turn"], "test": ["rotate the image"]}),
    ("play", None, {"train": ["play", "play this video", "resume playback", "start the song"], "validation": ["continue playing"], "test": ["go ahead and play"]}),
    ("pause", None, {"train": ["pause", "pause the video", "stop playing for now"], "validation": ["hold playback"], "test": ["pause it"]}),
    ("seek", 30, {"train": ["skip to 30 seconds", "jump to thirty seconds", "seek to 30 seconds"],
                  "validation": ["move to the 30 second mark"], "test": ["go to thirty seconds in"]}),
]

WRAPPERS = {
    "train": ["{}", "Hey Fily, {}", "Fily, {}", "Please {}", "Can you {}?", "Um, {}"],
    "validation": ["{}", "Fily, could you {}?"],
    "test": ["{}", "Would you {}?"],
}

PAGE_COUNT = 12


def viewer_for(op):
    return [name for name, ops in VIEWERS.items() if op in ops]


def done(op, value):
    """The tool result the app returns, and what Fily says (QuickCommands.reply)."""
    result = {"status": "done", "operation": op}
    if op in ("next_page", "previous_page", "go_to_page", "scroll_top", "scroll_bottom"):
        page = {"next_page": 5, "previous_page": 3, "go_to_page": value, "scroll_top": 1, "scroll_bottom": PAGE_COUNT}[op]
        result.update(page=page, pageCount=PAGE_COUNT)
        return result, f"Page {page} of {PAGE_COUNT}."
    if op in ("next_photo", "previous_photo"):
        page = 5 if op == "next_photo" else 3
        result.update(page=page, pageCount=9)
        return result, f"Photo {page} of 9."
    if op == "select_cell":
        result.update(sheet="Budget", cell="B3")
        return result, "Selected B3."
    if op in ("next_sheet", "previous_sheet"):
        result.update(sheet="March")
        return result, "Now on March."
    text = {"zoom_in": "Zoomed in.", "zoom_out": "Zoomed out.", "scroll_down": "Scrolled down.", "scroll_up": "Scrolled up.",
            "rotate_photo": "Rotated.", "play": "Playing.", "pause": "Paused.", "seek": f"Jumped to {value} seconds."}[op]
    return result, text


def state_for(viewer, doc):
    state = {"authorizedIds": [doc], "selectedDocumentId": doc, "readerCapabilities": VIEWERS[viewer]}
    if viewer in ("pdf", "slides"):
        state["pageCount"] = PAGE_COUNT
    return state


def with_control_reader(row):
    """The app offers controlReader whenever a viewer is open, even when the reply needs no tool."""
    if "controlReader" not in row["tools"]:
        row["tools"].append("controlReader")
        row["messages"][0] = msg("system", system(row["tools"]))
    return row


def build(split):
    rng = random.Random(f"{SEED}:{split}")
    rows = []
    index = 0

    def add(family, turns, state, tags=(), force_reader=False):
        nonlocal index
        row = conversation(family, f"screen_{split}", index, turns, state, ["screen_control", *tags])
        rows.append(with_control_reader(row) if force_reader else row)
        index += 1

    for op, value, phrasings in REQUESTS:
        for phrase in phrasings[split]:
            for wrapper in WRAPPERS[split]:
                viewer = rng.choice(viewer_for(op))
                doc = f"doc_S_{index:04d}"
                args = {"operation": op, **({"value": value} if value is not None else {})}
                result, said = done(op, value)
                add("screen_" + op, [("user", wrapper.format(phrase)), ("assistant", tool("controlReader", **args)),
                                     ("tool", result), ("assistant", reply(said))], state_for(viewer, doc))

        # The viewer on screen can't do it: say so, don't call the tool.
        other = [v for v in VIEWERS if op not in VIEWERS[v]]
        for phrase in phrasings[split][:2]:
            viewer = rng.choice(other)
            doc = f"doc_S_{index:04d}"
            add("screen_unsupported_" + op, [("user", phrase), ("assistant", reply("I can't do that in this view."))],
                state_for(viewer, doc), ["unsupported"], force_reader=True)

    # Nothing open: screen commands need a document first.
    for phrase in ["next page", "scroll down", "zoom in", "select B3"][: 4 if split == "train" else 1]:
        add("screen_no_document", [("user", phrase), ("assistant", reply("Open a document first, then I can move around in it."))], {})

    # The viewer refused: report the result truthfully.
    for op, value, reason, said in [("next_page", None, "at_last_page", "That's the last page."),
                                    ("go_to_page", 40, "out_of_range", f"There are only {PAGE_COUNT} pages."),
                                    ("zoom_in", None, "at_limit", "That's as close as it zooms."),
                                    ("select_cell", "Z900", "out_of_range", "That isn't in this file.")]:
        for _ in range(12 if split == "train" else 2):
            viewer = rng.choice(viewer_for(op))
            doc = f"doc_S_{index:04d}"
            args = {"operation": op, **({"value": value} if value is not None else {})}
            add("screen_failed_" + reason, [("user", f"{op.replace('_', ' ')}" if value is None else f"go to {value}"),
                                            ("assistant", tool("controlReader", **args)),
                                            ("tool", {"status": "failed", "reason": reason}), ("assistant", reply(said))],
                state_for(viewer, doc), ["failure"])

    # Open a file, then move around in it: one conversation across screens.
    follow_ups = [("Next page.", "next_page", None), ("Go to page 7.", "go_to_page", 7), ("Zoom in.", "zoom_in", None)]
    for i in range(40 if split == "train" else 4):
        doc = f"doc_S_{index:04d}"
        viewer = rng.choice(["pdf", "slides"])
        said_by_user, op, value = rng.choice([f for f in follow_ups if f[1] in VIEWERS[viewer]])
        result, said = done(op, value)
        args = {"operation": op, **({"value": value} if value is not None else {})}
        add("screen_after_open", [
            ("user", "Open the selected file."), ("assistant", tool("openDocument", documentId=doc)),
            ("tool", {"status": "opened", "documentId": doc}), ("assistant", reply("The file is open.", [doc])),
            ("user", said_by_user), ("assistant", tool("controlReader", **args)), ("tool", result), ("assistant", reply(said)),
        ], state_for(viewer, doc), ["multiturn"])

    # Questions about the page still read the document; screen control doesn't replace grounding.
    for i in range(30 if split == "train" else 3):
        doc = f"doc_S_{index:04d}"
        add("screen_question_reads_text", [("user", rng.choice(["What does this page say about the deadline?", "Summarize what's on screen."])),
                                           ("assistant", tool("getDocumentExcerpt", documentId=doc, maxChars=4000))],
            {**state_for("pdf", doc), "contentAllowed": True}, ["grounding"], force_reader=True)
    return rows


def main():
    source = ROOT / "data/edge_v5"
    dest = ROOT / "data/edge_v6"
    dest.mkdir(parents=True, exist_ok=True)
    report = {"version": 6, "base": "edge_v5", "seed": SEED, "synthetic": True, "personal_files_read": False, "splits": {}}
    for split in ["train", "validation", "test"]:
        out = []
        for line in (source / f"{split}.jsonl").read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            row["messages"][0] = msg("system", system(row["tools"]))
            out.append(row)
        screen = build(split)
        # Screen control is a small share of the corpus; weight it so it is learned, not drowned out.
        repeats = 3 if split == "train" else 1
        mixed = out + screen * repeats
        random.Random(f"{SEED}:{split}:shuffle").shuffle(mixed)
        with (dest / f"{split}.jsonl").open("w", encoding="utf-8", newline="\n") as f:
            for row in mixed:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        with (dest / f"screen_{split}.jsonl").open("w", encoding="utf-8", newline="\n") as f:
            for row in screen:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        report["splits"][split] = {"edge_v5_rows": len(out), "screen_rows": len(screen), "screen_repeats": repeats,
                                   "families": dict(Counter(r["family"] for r in screen))}
    (dest / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    # Kept with this data, not in tool_catalog.json: the app must send the deployed model's schema
    # until the model trained on this data replaces it (see the swap steps in the plan).
    from catalog import TOOLS
    (dest / "tool_catalog.json").write_text(json.dumps(TOOLS, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: {x: v[x] for x in ["edge_v5_rows", "screen_rows"]} for k, v in report["splits"].items()}))


if __name__ == "__main__":
    main()
