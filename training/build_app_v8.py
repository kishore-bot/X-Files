"""App-faithful curriculum (app v8): what the phone app actually sends and returns, on the v5 contract.

Targets the failures seen on the phone (docs/fily-training-needs.md, areas A, E, F, G, H):
  A  never invent IDs: a named file is searched first; IDs come only from tool results or APP_STATE
  E  answers grounded in excerpts, with [page N] citations, and "it doesn't say" when it doesn't
  F  speech-recognition slips and fillers in requests
  G  no needless clarifying ("latest" is already answered by the sort)
  H  short spoken replies; follow-ups ("yes", "share it") use the IDs in the conversation

Faithful to the app: short IDs doc_1, doc_2 … in order of appearance (ToolExecutor.DocumentAliases),
the exact tool-result JSON of ToolExecutor, APP_STATE as FilyPrompt.appState writes it, and the fixed
replies FilyReplies gives after action tools (so history looks like the app's history).

Edge v5 rows are replayed, with their IDs rewritten to the same doc_N style, to keep earlier skills.
"""
import json
import random
import re
from collections import Counter
from pathlib import Path

from build_dataset import clarify, conversation, reply, tool

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "app_v8"
SEED = 17112026

FIRST = ["Kavya", "Arjun", "Meera", "Rohan", "Ishaan", "Nila", "Dev", "Ananya", "Farhan", "Leela", "Omar", "Priya", "Sam", "Tara", "Vikram", "Zoya"]
LAST = ["Rao", "Menon", "Iyer", "Das", "Khan", "Pillai", "Shah", "Nair", "Kapoor", "Bose", "Varma", "Joseph"]
COMPANIES = ["Nimbus Labs", "Aster Works", "Brightline", "Coral Systems", "Delta Forge", "Evergreen Tech", "Falcon Data", "Granite Soft",
             "Harbor Analytics", "Indigo Cloudless", "Juniper Apps", "Kestrel Robotics"]
ROLES = ["backend engineer", "Android developer", "data analyst", "product designer", "QA engineer", "machine learning engineer", "DevOps engineer"]
SKILLS = ["Kotlin", "Python", "Java", "SQL", "Docker", "Kubernetes", "Figma", "PostgreSQL", "React", "Rust", "C++", "TensorFlow", "Go", "AWS"]
SCHOOLS = ["Anna University", "VIT Vellore", "NIT Trichy", "PSG Tech", "IIIT Hyderabad", "Amrita University"]
TODAYS = ["2026-11-14", "2026-11-16", "2026-11-17", "2026-12-02", "2027-01-09"]
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
WORDS = ["blue", "amber", "pine", "orbit", "cedar", "river", "silver", "maple", "comet", "harbor", "violet", "falcon"]

WRAPPERS = {
    "train": ["{}", "{}", "Hey Fily, {}", "Fily, {}", "Please {}", "Can you {}?", "Um, {}", "Okay, {}", "I mean {}", "No, {}", "{} please", "Could you {}?"],
    "validation": ["{}", "Fily, could you {}?", "So {}"],
    "test": ["{}", "Would you {}?", "Right, {}"],
}

# Recognizer slips seen on the phone (and common ones like them).
SLIPS = {"resume": ["receume", "resumay", "rezume"], "notes": ["note", "knots"], "invoice": ["in voice", "invoices"],
         "contract": ["contracts", "con tract"], "the": ["a", "da"], "summarize": ["summarise", "sumrise", "summary"],
         "open": ["opens", "open up"], "experience": ["experiences", "expirience"], "slides": ["slide", "lights"]}


class Split:
    def __init__(self, name):
        self.name = name
        self.rows = []
        self.families = Counter()

    def add(self, family, turns, state=None, tags=()):
        index = self.families[family]
        self.families[family] += 1
        row = conversation(f"app_v8_{family}", self.name, index, turns, state=state, tags=tags)
        # The app (kotlinx.serialization) writes non-ASCII as is ("…"), not as escapes.
        for message in row["messages"][1:]:
            try:
                message["content"] = json.dumps(json.loads(message["content"]), separators=(",", ":"), ensure_ascii=False)
            except ValueError:
                pass
        self.rows.append(row)


def say(rng, split, text, noisy=0.3):
    """The request as the phone hears it: a wrapper, and sometimes a recognizer slip."""
    text = rng.choice(WRAPPERS[split]).format(text)
    if rng.random() < noisy:
        words = text.split(" ")
        for i, word in enumerate(words):
            key = word.lower().strip("?,.")
            if key in SLIPS and rng.random() < 0.5:
                words[i] = rng.choice(SLIPS[key])
        text = " ".join(words)
        if rng.random() < 0.3:
            text = text.lower().rstrip("?.")
    return text[0].upper() + text[1:] if rng.random() < 0.7 else text


def spoken(name):
    """FilyReplies.spoken: how the app reads a file name aloud."""
    stem = name.rsplit(".", 1)[0] if "." in name else name
    return re.sub(" +", " ", re.sub(r"[_\-]+", " ", stem)).strip() or name


def state_for(today, selected=None, page_count=None, capabilities=None, content=True):
    state = {"contentAllowed": content, "today": today}
    if selected:
        state.update({"authorizedIds": [selected], "selectedDocumentId": selected})
    if page_count:
        state["pageCount"] = page_count
    if capabilities:
        state["readerCapabilities"] = capabilities
    return state


def docs_result(names, partial=False):
    return {"documents": [{"id": f"doc_{i + 1}", "name": n} for i, n in enumerate(names)], "partial": partial}


def found_reply(names, latest):
    """FilyReplies.documents for a plain find."""
    shown = [spoken(n) for n in names[:3]]
    if len(names) == 1:
        return reply(f"I found {shown[0]}.", ["doc_1"])
    listing = shown[0] if len(shown) == 1 else ", ".join(shown[:-1]) + " and " + shown[-1]
    lead = "The latest are" if latest else "The top ones are"
    return reply(f"I found {len(names)} files. {lead} {listing}.", [f"doc_{i + 1}" for i in range(len(shown))])


def which_one(names):
    shown = [spoken(n) for n in names[:3]]
    listing = " or ".join(shown) if len(shown) == 2 else ", ".join(shown[:-1]) + " or " + shown[-1]
    return reply(f"I found {len(names)} files: {listing}. Which one?", [f"doc_{i + 1}" for i in range(len(shown))])


# ---------- Synthetic documents with questions whose answers are in (or missing from) the text ----------

def person(rng):
    return f"{rng.choice(FIRST)} {rng.choice(LAST)}"


def make_resume(rng):
    who = person(rng)
    role = rng.choice(ROLES)
    years = rng.randrange(2, 12)
    jobs = rng.sample(COMPANIES, 2)
    skills = rng.sample(SKILLS, 4)
    school = rng.choice(SCHOOLS)
    grad = 2026 - years - rng.randrange(0, 2)
    email = f"{who.split()[0].lower()}.{who.split()[1].lower()}@example.com"
    pages = [
        f"{who}. {role.title()}. Email: {email}. Summary: {years} years building reliable software.",
        f"Experience: {role.title()} at {jobs[0]} ({2026 - years + 2}-present); Junior {role} at {jobs[1]} ({2026 - years}-{2026 - years + 2}).",
        f"Skills: {', '.join(skills)}. Education: B.E. Computer Science, {school}, {grad}.",
    ]
    name = rng.choice([f"{who.replace(' ', '_')}_Resume.pdf", f"{who.split()[0]}_CV.pdf", f"{who.replace(' ', '-')}-resume.docx"])
    qa = [
        (["what is my experience", "how many years of experience does it show", "where have I worked"],
         f"{years} years: {role} at {jobs[0]}, and before that at {jobs[1]}.", 2),
        (["what are my skills", "which skills are listed", "what skills does the resume mention"], f"The skills listed are {', '.join(skills[:-1])} and {skills[-1]}.", 3),
        (["where did I study", "what is my education", "which college is on it"], f"B.E. in Computer Science from {school}, {grad}.", 3),
        (["what is the email on it", "what email is listed"], f"The email is {email}.", 1),
    ]
    missing = [(["what is my salary", "how much do I earn", "what does it say about the payment"], "This resume doesn't mention salary or payment."),
               (["what is my phone number", "is there a phone number"], "This resume doesn't list a phone number.")]
    return name, "resume", pages, qa, missing, f"{who}'s resume: {role} with {years} years of experience, skilled in {skills[0]} and {skills[1]}."


def make_invoice(rng):
    vendor = rng.choice(COMPANIES)
    number = rng.randrange(1000, 9999)
    amount = rng.randrange(12, 480) * 100
    due = f"{rng.randrange(1, 28)} {rng.choice(MONTHS)} 2026"
    items = rng.sample(["design work", "hosting", "support hours", "licences", "consulting", "testing"], 2)
    pages = [f"Invoice {number} from {vendor}. Bill to: Xfiles user.",
             f"Items: {items[0]} and {items[1]}. Total due: INR {amount:,}. Due date: {due}. Pay by bank transfer."]
    name = rng.choice([f"Invoice-{number}-{vendor.split()[0]}.pdf", f"invoice_{number}.pdf", f"{vendor.split()[0]}_invoice_{number}.pdf"])
    qa = [(["how much is the invoice", "what is the total", "how much do I have to pay", "what does it say about the payment"],
           f"The total due is INR {amount:,}, by {due}.", 2),
          (["when is it due", "what is the due date"], f"It's due on {due}.", 2),
          (["who is it from", "which company sent it"], f"It's from {vendor}, invoice {number}.", 1)]
    missing = [(["what is the GST number", "is there a tax number"], "This invoice doesn't show a tax number."),
               (["who signed it", "is it signed"], "The invoice doesn't mention a signature.")]
    return name, "invoice", pages, qa, missing, f"Invoice {number} from {vendor} for {items[0]} and {items[1]}, INR {amount:,} due {due}."


def make_contract(rng):
    a, b = rng.sample(COMPANIES, 2)
    fee = rng.randrange(20, 900) * 1000
    notice = rng.choice([15, 30, 45, 60, 90])
    start = f"{rng.randrange(1, 28)} {rng.choice(MONTHS)} 2026"
    pages = [f"Services Agreement between {a} and {b}, starting {start}.",
             "Clause 2: both parties keep information confidential.",
             f"Clause 4: the total fee is INR {fee:,}, paid in two instalments.",
             f"Clause 7: either party may terminate with {notice} days written notice."]
    name = rng.choice([f"{a.split()[0]}_{b.split()[0]}_Agreement.pdf", f"services-contract-{a.split()[0].lower()}.pdf", "Signed_Contract.pdf"])
    qa = [(["how much is the payment", "what is the fee", "what does it say about the payment"], f"Clause 4 sets a total fee of INR {fee:,}, paid in two instalments.", 3),
          (["how do I terminate it", "what is the notice period", "what does it say about termination"], f"Either party can end it with {notice} days written notice.", 4),
          (["who is it between", "who are the parties"], f"It's between {a} and {b}.", 1),
          (["when does it start", "what is the start date"], f"It starts on {start}.", 1)]
    missing = [(["is there a penalty for late payment", "what is the late fee"], "The contract doesn't mention a late payment penalty.")]
    return name, "contract", pages, qa, missing, f"A services agreement between {a} and {b}: INR {fee:,} fee, {notice} days notice to end it."


def make_notes(rng):
    word = f"{rng.choice(WORDS).upper()}-{rng.choice(WORDS).upper()}-{rng.randrange(10, 99)}"
    tasks = rng.sample(["buy milk", "call the bank", "renew the passport", "book train tickets", "pay electricity bill", "email the landlord"], 3)
    meet = f"{rng.choice(['Monday', 'Tuesday', 'Thursday', 'Friday'])} at {rng.randrange(9, 12)} am"
    pages = [f"To do: {tasks[0]}, {tasks[1]}, {tasks[2]}. Team meeting on {meet}. The code word is {word}."]
    name = rng.choice(["random_notes.txt", "my-notes.md", "todo_list.txt", "Notes_Week.txt"])
    qa = [(["what's on my to do list", "what do I have to do", "what tasks are in it"], f"Your list: {tasks[0]}, {tasks[1]} and {tasks[2]}.", 1),
          (["when is the meeting", "what time is the team meeting"], f"The team meeting is on {meet}.", 1),
          (["what is the code word", "what's the code word in it"], f"The code word is {word}.", 1)]
    missing = [(["what is the wifi password", "is there a password"], "Your notes don't include a password.")]
    return name, "notes", pages, qa, missing, f"A to-do list ({tasks[0]}, {tasks[1]}, {tasks[2]}) and a team meeting on {meet}."


def make_code(rng):
    lang, ext, fn = rng.choice([("Python", "py", "def"), ("Java", "java", "static int"), ("C++", "cpp", "int"), ("Kotlin", "kt", "fun")])
    game = rng.choice(["number guessing game", "todo manager", "temperature converter", "file renamer"])
    funcs = rng.sample(["read_input", "check_guess", "show_score", "save_items", "convert", "rename_all", "main"], 3)
    pages = [f"// {game} in {lang}\n{fn} {funcs[0]}(...)\n{fn} {funcs[1]}(...)\n{fn} {funcs[2]}(...)\n// asks the user for input in a loop until they quit"]
    name = f"{game.replace(' ', '_')}.{ext}"
    qa = [(["what does this program do", "what is this code", "explain this file"], f"It's a {game} in {lang} that keeps asking for input until you quit.", 1),
          (["which functions are there", "what functions does it have"], f"It has {funcs[0]}, {funcs[1]} and {funcs[2]}.", 1),
          (["what language is it", "which language is this written in"], f"It's written in {lang}.", 1)]
    missing = [(["does it connect to the internet", "does it use a database"], "Nothing in the code mentions that.")]
    return name, "code", pages, qa, missing, f"A {lang} {game} with {funcs[0]}, {funcs[1]} and {funcs[2]}."


MAKERS = [make_resume, make_invoice, make_contract, make_notes, make_code]
CATEGORY = {"resume": ("resume", ["resume", "CV"]), "invoice": ("invoice", ["invoice", "bill"]), "contract": ("contract", ["contract", "agreement"])}


def excerpt_text(rng, pages, kind, want_page=None):
    """Like Passages.select: PDF pages labelled, passages joined by the app's separator."""
    labelled = kind in ("resume", "invoice", "contract") and rng.random() < 0.6
    parts = [f"[page {i + 1}] {p}" if labelled else p for i, p in enumerate(pages)]
    if len(parts) > 2 and rng.random() < 0.4:
        keep = sorted(rng.sample(range(len(parts)), 2) if want_page is None else {0, want_page - 1})
        return "\n…\n".join(parts[i] for i in keep), True, labelled
    return "\n".join(parts), False, labelled


def answer_with_page(text, page, labelled):
    return f"On page {page}, {text[0].lower()}{text[1:]}" if labelled and page > 1 else text


def doc(rng):
    name, kind, pages, qa, missing, summary = rng.choice(MAKERS)(rng)
    return {"name": name, "kind": kind, "pages": pages, "qa": qa, "missing": missing, "summary": summary}


def other_names(rng, d, n):
    out = []
    while len(out) < n:
        x = doc(rng)
        if x["name"] != d["name"] and x["name"] not in out:
            out.append(x["name"])
    return out


def named(d):
    """How a person says the file's name: "random notes", "Kavya resume"."""
    stem = spoken(d["name"]).lower()
    return " ".join(w for w in stem.split() if not w.isdigit()) or stem


def search_args(d, rng, latest=False):
    if d["kind"] in CATEGORY and rng.random() < 0.6:
        return {"query": "", "category": CATEGORY[d["kind"]][0], "sort": "modified_desc" if latest else "relevance"}
    return {"query": named(d), "sort": "modified_desc" if latest else "relevance"}


# ---------- Families ----------

def fam_open_named(s, rng):
    d = doc(rng)
    args = search_args(d, rng)
    what = named(d) if args["query"] else rng.choice(CATEGORY[d["kind"]][1])
    user = say(rng, s.name, rng.choice([f"open {what}", f"open my {what}", f"show me {what}", f"open the {what} file"]))
    if rng.random() < 0.7:
        names = [d["name"]]
        turns = [("user", user), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result(names)),
                 ("assistant", tool("openDocument", documentId="doc_1")), ("tool", {"status": "opened", "documentId": "doc_1"}),
                 ("assistant", reply(f"Opening {spoken(d['name'])}.", ["doc_1"]))]
    else:
        names = [d["name"]] + other_names(rng, d, rng.choice([1, 2]))
        turns = [("user", user), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result(names)), ("assistant", which_one(names))]
    s.add("open_named", turns, state_for(rng.choice(TODAYS)), ("ids", "search_first"))


def fam_open_latest(s, rng):
    d = doc(rng)
    while d["kind"] not in CATEGORY:
        d = doc(rng)
    word = rng.choice(CATEGORY[d["kind"]][1])
    user = say(rng, s.name, rng.choice([f"open my latest {word}", f"open the newest {word}", f"show my most recent {word}", f"open the last {word} I got"]))
    names = [d["name"]] + other_names(rng, d, 2)
    turns = [("user", user), ("assistant", tool("searchLibrary", query="", category=CATEGORY[d["kind"]][0], sort="modified_desc")),
             ("tool", docs_result(names)), ("assistant", tool("openDocument", documentId="doc_1")),
             ("tool", {"status": "opened", "documentId": "doc_1"}), ("assistant", reply(f"Opening {spoken(names[0])}.", ["doc_1"]))]
    s.add("open_latest", turns, state_for(rng.choice(TODAYS)), ("no_clarify", "temporal"))


def fam_find(s, rng):
    d = doc(rng)
    latest = rng.random() < 0.4
    args = search_args(d, rng, latest)
    what = named(d) if args["query"] else rng.choice(CATEGORY[d["kind"]][1]) + "s"
    lead = "my latest " if latest else rng.choice(["my ", "the ", ""])
    user = say(rng, s.name, rng.choice([f"find {lead}{what}", f"search for {lead}{what}", f"do I have any {what}", f"look for {lead}{what}"]))
    names = [d["name"]] + other_names(rng, d, rng.choice([0, 1, 2, 3]))
    if rng.random() < 0.12:
        turns = [("user", user), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result([])),
                 ("assistant", reply("I couldn't find a matching file."))]
    else:
        turns = [("user", user), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result(names)), ("assistant", found_reply(names, latest))]
    s.add("find", turns, state_for(rng.choice(TODAYS)), ("search", "short_reply"))


def ask_turns(rng, d, question, doc_id, missing=False):
    page = None
    if missing:
        q, answer = rng.choice(d["missing"])[0], None
    else:
        qs, answer, page = rng.choice(d["qa"])
    text, truncated, labelled = excerpt_text(rng, d["pages"], d["kind"], page)
    if missing:
        answer = rng.choice(d["missing"])[1]
    elif page is not None:
        answer = answer_with_page(answer, page, labelled)
    return [("assistant", tool("getDocumentExcerpt", documentId=doc_id, maxChars=4000)),
            ("tool", {"documentId": doc_id, "text": text, "available": True, "truncated": truncated}),
            ("assistant", reply(answer, [doc_id]))]


def fam_ask_open(s, rng):
    d = doc(rng)
    missing = rng.random() < 0.2
    if missing:
        q, answer = rng.choice(d["missing"])
        question = rng.choice(q)
        text, truncated, _ = excerpt_text(rng, d["pages"], d["kind"])
        turns = [("user", say(rng, s.name, question)), ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
                 ("tool", {"documentId": "doc_1", "text": text, "available": True, "truncated": truncated}), ("assistant", reply(answer, ["doc_1"]))]
    else:
        qs, answer, page = rng.choice(d["qa"])
        text, truncated, labelled = excerpt_text(rng, d["pages"], d["kind"], page)
        turns = [("user", say(rng, s.name, rng.choice(qs))), ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
                 ("tool", {"documentId": "doc_1", "text": text, "available": True, "truncated": truncated}),
                 ("assistant", reply(answer_with_page(answer, page, labelled), ["doc_1"]))]
    pages = len(d["pages"]) if d["kind"] in ("resume", "invoice", "contract") else None
    s.add("ask_open", turns, state_for(rng.choice(TODAYS), "doc_1", pages), ("grounded", "missing" if missing else "answer"))


def fam_ask_unreadable(s, rng):
    d = doc(rng)
    if rng.random() < 0.5:
        result, answer = {"documentId": "doc_1", "text": "", "available": False, "truncated": False}, \
            rng.choice(["I can't read the text in this file. It may be a scan or a picture.", "This file has no readable text, so I can't answer that."])
        turns = [("user", say(rng, s.name, rng.choice(rng.choice(d["qa"])[0]))), ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
                 ("tool", result), ("assistant", reply(answer))]
        s.add("ask_unreadable", turns, state_for(rng.choice(TODAYS), "doc_1"), ("honest",))
    else:
        # Content access off: say so plainly, without calling a tool.
        turns = [("user", say(rng, s.name, rng.choice(rng.choice(d["qa"])[0]))),
                 ("assistant", reply("I'm not allowed to read inside files. You can allow it in Xfiles Info."))]
        s.add("ask_content_off", turns, state_for(rng.choice(TODAYS), "doc_1", content=False), ("honest",))


def fam_ask_named(s, rng):
    d = doc(rng)
    qs, answer, page = rng.choice(d["qa"])
    what = named(d) if rng.random() < 0.5 or d["kind"] not in CATEGORY else rng.choice(CATEGORY[d["kind"]][1])
    q = rng.choice(qs).replace(" it", f" my {what}").replace(" this", f" my {what}")
    if what not in q:
        q = f"{q} in my {what}"
    args = {"query": what if what in named(d) else "", "sort": "relevance"}
    if not args["query"]:
        args = {"query": "", "category": CATEGORY[d["kind"]][0], "sort": "relevance"}
    text, truncated, labelled = excerpt_text(rng, d["pages"], d["kind"], page)
    turns = [("user", say(rng, s.name, q)), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result([d["name"]])),
             ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
             ("tool", {"documentId": "doc_1", "text": text, "available": True, "truncated": truncated}),
             ("assistant", reply(answer_with_page(answer, page, labelled), ["doc_1"]))]
    s.add("ask_named", turns, state_for(rng.choice(TODAYS)), ("ids", "grounded"))


def fam_summary(s, rng):
    d = doc(rng)
    text, truncated, _ = excerpt_text(rng, d["pages"], d["kind"])
    user = say(rng, s.name, rng.choice(["summarize this", "summarize it", "give me a summary", "what is this file about", "tell me what this says", "sum it up"]))
    answer = d["summary"] if not truncated else d["summary"] + " That's from parts of the file."
    turns = [("user", user), ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
             ("tool", {"documentId": "doc_1", "text": text, "available": True, "truncated": truncated}), ("assistant", reply(answer, ["doc_1"]))]
    s.add("summary", turns, state_for(rng.choice(TODAYS), "doc_1"), ("summary", "short_reply"))


def fam_follow_up(s, rng):
    d = doc(rng)
    names = [d["name"]] + other_names(rng, d, rng.choice([1, 2]))
    args = search_args(d, rng)
    what = named(d) if args["query"] else rng.choice(CATEGORY[d["kind"]][1])
    earlier = [("user", say(rng, s.name, f"find {what}", 0)), ("assistant", tool("searchLibrary", **args)),
               ("tool", docs_result(names)), ("assistant", found_reply(names, False))]
    kind = rng.random()
    if kind < 0.35:
        index = rng.randrange(len(names))
        word = ["first", "second", "third"][index]
        user = say(rng, s.name, rng.choice([f"open the {word} one", f"open the {word}", f"the {word} one please"]))
        turns = earlier + [("user", user), ("assistant", tool("openDocument", documentId=f"doc_{index + 1}")),
                           ("tool", {"status": "opened", "documentId": f"doc_{index + 1}"}),
                           ("assistant", reply(f"Opening {spoken(names[index])}.", [f"doc_{index + 1}"]))]
    elif kind < 0.6:
        user = say(rng, s.name, rng.choice(["share it", "share the first one", "send it to someone"]))
        turns = earlier + [("user", user), ("assistant", tool("prepareShareDocument", documentId="doc_1")),
                           ("tool", {"status": "chooser_opened", "documentId": "doc_1"}), ("assistant", reply("Choose where to share it."))]
    elif kind < 0.8:
        user = say(rng, s.name, rng.choice(["open it", "yes open it", "yeah open that"]))
        turns = earlier + [("user", user), ("assistant", tool("openDocument", documentId="doc_1")),
                           ("tool", {"status": "opened", "documentId": "doc_1"}), ("assistant", reply(f"Opening {spoken(names[0])}.", ["doc_1"]))]
    else:
        user = say(rng, s.name, rng.choice(["no thanks", "never mind", "no, that's all", "leave it"]))
        turns = earlier + [("user", user), ("assistant", reply(rng.choice(["Okay.", "Sure, I'll leave it.", "Okay, nothing else then."])))]
    s.add("follow_up", turns, state_for(rng.choice(TODAYS)), ("follow_up", "ids"))


def fam_open_then_ask(s, rng):
    """The app's own history (open → fixed reply), then a question about the opened file."""
    d = doc(rng)
    args = search_args(d, rng)
    what = named(d) if args["query"] else rng.choice(CATEGORY[d["kind"]][1])
    qs, answer, page = rng.choice(d["qa"])
    text, truncated, labelled = excerpt_text(rng, d["pages"], d["kind"], page)
    turns = [("user", say(rng, s.name, f"open {what}", 0)), ("assistant", tool("searchLibrary", **args)), ("tool", docs_result([d["name"]])),
             ("assistant", tool("openDocument", documentId="doc_1")), ("tool", {"status": "opened", "documentId": "doc_1"}),
             ("assistant", reply(f"Opening {spoken(d['name'])}.", ["doc_1"])),
             ("user", say(rng, s.name, rng.choice(qs))), ("assistant", tool("getDocumentExcerpt", documentId="doc_1", maxChars=4000)),
             ("tool", {"documentId": "doc_1", "text": text, "available": True, "truncated": truncated}),
             ("assistant", reply(answer_with_page(answer, page, labelled), ["doc_1"]))]
    s.add("open_then_ask", turns, state_for(rng.choice(TODAYS), "doc_1"), ("follow_up", "grounded"))


FAMILIES = [(fam_open_named, 1500), (fam_open_latest, 700), (fam_find, 700), (fam_ask_open, 2600), (fam_ask_unreadable, 500),
            (fam_ask_named, 1400), (fam_summary, 700), (fam_follow_up, 900), (fam_open_then_ask, 800)]
SPLIT_SHARE = {"train": 1.0, "validation": 0.05, "test": 0.05}


# ---------- Edge v5 replay, with app-style IDs ----------

ID = re.compile(r"\bdoc_[A-Z]_\d{4}(?:_[a-z0-9]+)?\b")


def app_ids(row):
    mapping = {}

    def alias(m):
        return mapping.setdefault(m.group(0), f"doc_{len(mapping) + 1}")

    row = json.loads(json.dumps(row))
    for message in row["messages"]:
        message["content"] = ID.sub(alias, message["content"])
    row["state"] = json.loads(ID.sub(alias, json.dumps(row["state"])))
    row["source"] = row.get("source", "") + "+app_ids"
    return row


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)
    splits = {name: Split(name) for name in SPLIT_SHARE}
    for name, split in splits.items():
        srng = random.Random(f"{SEED}:{name}")
        for family, count in FAMILIES:
            for _ in range(max(8, int(count * SPLIT_SHARE[name]))):
                family(split, srng)
    replay = {"train": 13000, "validation": 500}
    for name, n in replay.items():
        rows = [json.loads(x) for x in (ROOT / "data" / "edge_v5" / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()]
        splits[name].rows += [app_ids(r) for r in random.Random(f"{SEED}:replay:{name}").sample(rows, min(n, len(rows)))]
    # Exact duplicate conversations never cross splits.
    seen = set()
    manifest = {}
    for name in ("test", "validation", "train"):
        unique = []
        for row in splits[name].rows:
            key = json.dumps(row["messages"], sort_keys=True)
            if key in seen:
                continue
            seen.add(key)
            unique.append(row)
        random.Random(f"{SEED}:shuffle:{name}").shuffle(unique)
        (OUT / f"{name}.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in unique), encoding="utf-8")
        manifest[name] = {"rows": len(unique), "families": dict(Counter(r["family"] for r in unique if r["family"].startswith("app_v8")))}
    # The five-hour supervisor evaluates <data>/screen_test.jsonl automatically: use the new test set.
    (OUT / "screen_test.jsonl").write_text((OUT / "test.jsonl").read_text(encoding="utf-8"), encoding="utf-8")
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
