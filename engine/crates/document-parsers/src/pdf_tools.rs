//! Whole-document PDF operations: merge, rebuild (reorder, rotate, delete,
//! extract), resize, and adding or removing a password.
//!
//! Annotations can be appended to a file (`pdf_annot`); these can't, so each one
//! is a full rewrite. It walks what the chosen pages reach, renumbers it per
//! source, and writes a fresh PDF with a classic cross-reference table and no
//! object streams. Page content, fonts and images are copied without decoding,
//! so text stays selectable. Objects leave memory as soon as they are written.
//!
//! When every page of one document stays, the catalog comes along too: outlines,
//! forms, named destinations and tags survive. Otherwise they can't be carried
//! whole, and [`PdfDropped`] says what went. A reference never dangles: anything
//! it would reach that isn't kept becomes null.

use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{Read, Seek, SeekFrom, Write};

use document_core::{EngineError, ErrorKind, Limits, Result};
use zeroize::Zeroizing;

use crate::pdf_crypt::{Encryptor, MAX_R6_PASSWORD, secure_random};
use crate::pdf_file::{LoadedObject, PageNode, PdfFile};
use crate::pdf_syntax::{Dict, Obj, write_obj};

const MAX_SOURCES: usize = 256;
const MAX_OUTPUT_PAGES: usize = 200_000;
const MAX_OUTPUT_OBJECTS: usize = 2_000_000;
const MAX_OUTPUT_BYTES: u64 = 1024 * 1024 * 1024;
/// Named destinations read to keep links working, and tree nodes visited for them.
const MAX_NAMED_DESTS: usize = 200_000;
const MAX_NAME_TREE_NODES: usize = 100_000;
/// 200 inches, the largest page PDF readers are required to handle.
const MAX_PAGE_SIDE: f64 = 14_400.0;
/// How many objects pass between looks at the cancel flag.
const CANCEL_CHECK_EVERY: u32 = 64;
/// Used by PDFium when a page has no `/MediaBox`.
const DEFAULT_MEDIA_BOX: [u64; 4] = [0, 0, 612, 792];
/// What a form field keeps as a plain annotation once its form is gone.
const WIDGET_KEEPS: [&[u8]; 14] =
    [b"Type", b"Subtype", b"Rect", b"F", b"AP", b"AS", b"BS", b"MK", b"Border", b"C", b"Contents", b"NM", b"M", b"OC"];
/// Coordinates in an annotation that follow the page's content on a resize.
const ANNOTATION_POINTS: [&str; 5] = ["Rect", "QuadPoints", "L", "Vertices", "CL"];

/// One input and the password to read it with. The password is wiped on drop.
pub struct PdfToolSource<R> {
    pub reader: R,
    pub password: Option<Zeroizing<String>>,
}

/// One page of a rebuilt document: a zero-based source page, and extra clockwise rotation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfPagePick {
    pub page: u32,
    pub rotation: i32,
}

pub enum PdfToolOp {
    /// All pages of every source, in order.
    Merge,
    /// Exactly these pages of the one source.
    Rebuild {
        pages: Vec<PdfPagePick>,
    },
    /// All pages of the second source, before zero-based `at_page` of the first.
    InsertPages {
        at_page: u32,
    },
    /// Scales every page's content to this displayed size, in points, centred.
    Resize {
        width: f64,
        height: f64,
    },
    AddPassword {
        password: Zeroizing<String>,
    },
    RemovePassword,
    /// Every page as it is, unprotected, only for the print spooler (a private file deleted after
    /// printing). Needs just the print permission: a watermarked PDF that opens for anyone and allows
    /// printing must print without asking for its owner password.
    PrintCopy,
}

/// Told how a rewrite is going; asked if it should go on. Called on the rewriting thread.
pub trait PdfToolObserver {
    fn on_progress(&mut self, done: u32, total: u32);
    fn is_cancelled(&self) -> bool;
}

/// What the catalog held that couldn't be carried into the output.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PdfDropped {
    pub outlines: bool,
    pub forms: bool,
    pub named_destinations: bool,
    pub structure_tags: bool,
    pub page_labels: bool,
    pub attachments: bool,
    /// Links whose target page isn't in the output.
    pub links: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfToolReport {
    pub page_count: u32,
    pub bytes: u64,
    pub dropped: PdfDropped,
}

/// What a tool needs to know before it starts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PdfToolInfo {
    pub page_count: u32,
    pub encrypted: bool,
    pub signed: bool,
    pub archival: bool,
    /// False only when the file forbids printing to someone without the owner password.
    pub printable: bool,
}

fn password_given(password: Option<&str>) -> Option<&str> {
    password.filter(|p| !p.is_empty())
}

/// Reads what a tool needs. The empty user password is tried when none is
/// given, so a file that opens for anyone but sets owner limits answers
/// `encrypted` with its real page count. A file that needs a password without
/// one is `PasswordRequired`; a wrong one is `WrongPassword`.
pub fn inspect_for_tools<R: Read + Seek>(source: R, password: Option<&str>) -> Result<PdfToolInfo> {
    let mut file = PdfFile::open(source)?;
    let encrypted = file.trailer.get("Encrypt").is_some();
    file.unlock(password_given(password))?;
    let pages = file.pages()?;
    let (signed, archival) = file.warnings()?;
    let printable = file.crypt.as_ref().is_none_or(|crypt| crypt.is_owner || crypt.permissions.may_print());
    Ok(PdfToolInfo { page_count: pages.len() as u32, encrypted, signed, archival, printable })
}

/// Writes the result of `op` to `out`. The sources are only read.
pub fn run_pdf_tool<R: Read + Seek, W: Write>(
    sources: Vec<PdfToolSource<R>>,
    op: &PdfToolOp,
    out: &mut W,
    observer: &mut dyn PdfToolObserver,
) -> Result<PdfToolReport> {
    check_source_count(sources.len(), op)?;
    let encryptor = match op {
        PdfToolOp::AddPassword { password } => Some(new_encryptor(password)?),
        _ => None,
    };
    let mut opened = Vec::with_capacity(sources.len());
    for source in sources {
        if observer.is_cancelled() {
            return Err(EngineError::cancelled());
        }
        opened.push(Source::open(source, op)?);
    }
    let picks = choose_pages(&opened, op)?;
    let every_page_once = keeps_every_page_once(&opened, &picks);
    let whole_document = matches!(op, PdfToolOp::InsertPages { .. }) || every_page_once;

    let mut output = Output { out, pos: 0, offsets: vec![0], encryptor, observer, objects_since_check: 0 };
    let version = output_version(&mut opened, output.encryptor.is_some())?;
    output.write(format!("%PDF-{}.{}\n", version.0, version.1).as_bytes())?;
    // High bytes in a comment mark the file as binary for tools that guess.
    output.write(b"%\xE2\xE3\xCF\xD3\n")?;

    let catalog_number = output.allocate()?;
    let pages_number = output.allocate()?;
    let mut page_numbers = Vec::with_capacity(picks.len());
    for pick in &picks {
        let number = output.allocate()?;
        let source = &mut opened[pick.source];
        let object = source.pages[pick.page].page.object;
        source.kept.entry(object).or_insert(number);
        page_numbers.push(number);
    }

    let resize = match op {
        PdfToolOp::Resize { width, height } => Some((*width, *height)),
        _ => None,
    };
    let mut dropped = PdfDropped::default();
    output.observer.on_progress(0, picks.len() as u32);
    for (index, pick) in picks.iter().enumerate() {
        output.check_cancelled()?;
        let source = &mut opened[pick.source];
        source.whole_document = whole_document && pick.source == 0;
        source.write_page(pick, page_numbers[index], pages_number, resize, &mut dropped, &mut output)?;
        output.observer.on_progress(index as u32 + 1, picks.len() as u32);
    }

    let mut catalog = if whole_document {
        whole_catalog(&mut opened[0], &mut output)?
    } else {
        kept_catalog(&mut opened, &mut dropped, &mut output)?
    };
    catalog.set("Type", Obj::name("Catalog"));
    catalog.set("Pages", Obj::Ref(pages_number, 0));
    output.write_value(catalog_number, Obj::Dict(catalog))?;

    let kids = page_numbers.iter().map(|number| Obj::Ref(*number, 0)).collect();
    let tree = Dict::of(vec![
        ("Type", Obj::name("Pages")),
        ("Kids", Obj::Array(kids)),
        ("Count", Obj::int(picks.len() as u64)),
    ]);
    output.write_value(pages_number, Obj::Dict(tree))?;

    let info = opened[0].write_info(&mut output)?;
    let bytes = output.finish(catalog_number, info)?;
    Ok(PdfToolReport { page_count: picks.len() as u32, bytes, dropped })
}

/// Re-reads a finished output with the password it should open with: the last
/// defence before a file is handed to the user.
pub fn verify_pdf_tool_output<R: Read + Seek>(saved: R, password: Option<&str>, expected_pages: u32) -> Result<()> {
    let not_verified = || EngineError::new(ErrorKind::Internal, "the finished PDF did not verify");
    let mut file = PdfFile::open(saved).map_err(|_| not_verified())?;
    file.unlock(password_given(password)).map_err(|_| not_verified())?;
    let pages = file.pages().map_err(|_| not_verified())?;
    if pages.len() as u64 != u64::from(expected_pages) {
        return Err(not_verified());
    }
    if !dangling_references(&mut file).map_err(|_| not_verified())?.is_empty() {
        return Err(not_verified());
    }
    Ok(())
}

/// Every `(holder, target)` where an object refers to one that doesn't exist.
pub(crate) fn dangling_references<R: Read + Seek>(file: &mut PdfFile<R>) -> Result<Vec<(u32, u32)>> {
    fn visit(value: &Obj, found: &mut Vec<u32>) {
        match value {
            Obj::Ref(number, _) => found.push(*number),
            Obj::Array(items) => items.iter().for_each(|item| visit(item, found)),
            Obj::Dict(dict) => dict.0.iter().for_each(|(_, item)| visit(item, found)),
            _ => {}
        }
    }
    let mut numbers: Vec<u32> = file.xref.keys().copied().filter(|n| file.has_object(*n)).collect();
    numbers.sort_unstable();
    let mut dangling = Vec::new();
    let mut targets = Vec::new();
    for number in numbers {
        targets.clear();
        visit(&file.object(number)?, &mut targets);
        dangling.extend(targets.iter().filter(|t| !file.has_object(**t)).map(|t| (number, *t)));
    }
    let trailer = Obj::Dict(file.trailer.clone());
    targets.clear();
    visit(&trailer, &mut targets);
    dangling.extend(targets.iter().filter(|t| !file.has_object(**t)).map(|t| (0, *t)));
    Ok(dangling)
}

fn check_source_count(count: usize, op: &PdfToolOp) -> Result<()> {
    let wrong = || EngineError::new(ErrorKind::Internal, "this PDF tool got the wrong number of files");
    match op {
        PdfToolOp::Merge => {
            if count == 0 {
                return Err(EngineError::malformed("Choose at least one PDF to merge."));
            }
            if count > MAX_SOURCES {
                return Err(EngineError::limit("too many PDFs to merge at once"));
            }
        }
        PdfToolOp::InsertPages { .. } if count != 2 => return Err(wrong()),
        PdfToolOp::InsertPages { .. } => {}
        _ if count != 1 => return Err(wrong()),
        _ => {}
    }
    Ok(())
}

/// A new password has to survive other readers' handling: they cut it at 127
/// bytes, and control characters aren't allowed in one.
fn new_encryptor(password: &str) -> Result<Encryptor> {
    if password.is_empty() {
        return Err(EngineError::malformed("Enter a password to protect this PDF with."));
    }
    if password.len() > MAX_R6_PASSWORD {
        return Err(EngineError::limit("That password is too long. PDF allows up to 127 bytes."));
    }
    if password.chars().any(char::is_control) {
        return Err(EngineError::malformed("A password can't contain control characters."));
    }
    Encryptor::new(password)
}

/// The output of every operation carries no limits, whichever bit the operation
/// itself needs, so any limit a file sets would be stripped by saving a copy.
/// Without the owner password that's refused. It's a password error, because
/// the owner password is the way forward; the message says so.
fn check_permissions<R: Read + Seek>(file: &PdfFile<R>, op: &PdfToolOp) -> Result<()> {
    let Some(crypt) = &file.crypt else { return Ok(()) };
    if crypt.is_owner {
        return Ok(());
    }
    let permissions = crypt.permissions;
    if matches!(op, PdfToolOp::PrintCopy) {
        return if permissions.may_print() {
            Ok(())
        } else {
            Err(EngineError::new(
                ErrorKind::PasswordRequired,
                "The owner of this PDF doesn't allow printing. Enter the owner password to print it.".to_owned(),
            ))
        };
    }
    let denied = permissions.denied();
    let refused = |what: String| {
        Err(EngineError::new(ErrorKind::PasswordRequired, format!("{what} Enter the owner password to continue.")))
    };
    match op {
        PdfToolOp::Merge | PdfToolOp::Rebuild { .. } | PdfToolOp::InsertPages { .. } if !permissions.may_assemble() => {
            return refused(
                "The owner of this PDF doesn't allow adding, removing or rearranging its pages.".to_owned(),
            );
        }
        PdfToolOp::Resize { .. } if !permissions.may_modify() => {
            return refused("The owner of this PDF doesn't allow changing its content.".to_owned());
        }
        _ => {}
    }
    if denied.is_empty() {
        return Ok(());
    }
    let list = denied.join(", ");
    match op {
        PdfToolOp::RemovePassword => {
            refused(format!("This PDF restricts {list}, and removing its protection would lift that."))
        }
        PdfToolOp::AddPassword { .. } => {
            refused(format!("This PDF restricts {list}, and a new password would replace that."))
        }
        _ => refused(format!("This PDF restricts {list}, and the new file wouldn't keep those limits.")),
    }
}

/// One page of the output, by index into the sources' page lists.
#[derive(Debug, Clone, Copy)]
struct Pick {
    source: usize,
    page: usize,
    rotation: i64,
}

fn choose_pages<R: Read + Seek>(sources: &[Source<R>], op: &PdfToolOp) -> Result<Vec<Pick>> {
    let all = |source: usize| (0..sources[source].pages.len()).map(move |page| Pick { source, page, rotation: 0 });
    let picks: Vec<Pick> = match op {
        PdfToolOp::Merge => (0..sources.len()).flat_map(all).collect(),
        PdfToolOp::Rebuild { pages } => {
            if pages.is_empty() {
                return Err(EngineError::malformed("Choose at least one page."));
            }
            let mut picks = Vec::with_capacity(pages.len().min(MAX_OUTPUT_PAGES));
            for edit in pages {
                if edit.page as usize >= sources[0].pages.len() {
                    return Err(EngineError::malformed("A chosen page isn't in this PDF."));
                }
                if edit.rotation % 90 != 0 {
                    return Err(EngineError::malformed("Pages can only turn in steps of 90 degrees."));
                }
                picks.push(Pick { source: 0, page: edit.page as usize, rotation: i64::from(edit.rotation) });
                if picks.len() > MAX_OUTPUT_PAGES {
                    return Err(EngineError::limit("too many pages for one PDF"));
                }
            }
            picks
        }
        PdfToolOp::InsertPages { at_page } => {
            let at = *at_page as usize;
            if at > sources[0].pages.len() {
                return Err(EngineError::malformed("That page isn't in this PDF."));
            }
            all(0).take(at).chain(all(1)).chain(all(0).skip(at)).collect()
        }
        PdfToolOp::Resize { width, height } => {
            let side = 1.0..=MAX_PAGE_SIDE;
            if !side.contains(width) || !side.contains(height) {
                return Err(EngineError::malformed("That page size isn't valid."));
            }
            all(0).collect()
        }
        PdfToolOp::AddPassword { .. } | PdfToolOp::RemovePassword | PdfToolOp::PrintCopy => all(0).collect(),
    };
    if picks.len() > MAX_OUTPUT_PAGES {
        return Err(EngineError::limit("too many pages for one PDF"));
    }
    Ok(picks)
}

fn keeps_every_page_once<R: Read + Seek>(sources: &[Source<R>], picks: &[Pick]) -> bool {
    if sources.len() != 1 || picks.len() != sources[0].pages.len() {
        return false;
    }
    let mut seen = HashSet::with_capacity(picks.len());
    picks.iter().all(|pick| seen.insert(pick.page))
}

/// The highest version any source declares. Revision 6 encryption is PDF 2.0.
fn output_version<R: Read + Seek>(sources: &mut [Source<R>], encrypted: bool) -> Result<(u8, u8)> {
    if encrypted {
        return Ok((2, 0));
    }
    let mut highest = (1, 4);
    for source in sources {
        highest = highest.max(source.file.header_version()?);
        let catalog = source.file.catalog()?;
        if let Some(declared) = catalog.get("Version") {
            if let Some([major @ b'0'..=b'9', b'.', minor @ b'0'..=b'9']) = source.file.resolve(declared)?.as_name() {
                highest = highest.max((major - b'0', minor - b'0'));
            }
        }
    }
    Ok(highest)
}

/// The output file, written strictly forward.
struct Output<'a, W> {
    out: &'a mut W,
    pos: u64,
    /// Indexed by object number; 0 until written. Number 0 is the free-list head.
    offsets: Vec<u64>,
    encryptor: Option<Encryptor>,
    observer: &'a mut dyn PdfToolObserver,
    objects_since_check: u32,
}

impl<W: Write> Output<'_, W> {
    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.pos += bytes.len() as u64;
        if self.pos > MAX_OUTPUT_BYTES {
            return Err(EngineError::limit("the finished PDF would be too large"));
        }
        self.out.write_all(bytes)?;
        Ok(())
    }

    fn allocate(&mut self) -> Result<u32> {
        if self.offsets.len() >= MAX_OUTPUT_OBJECTS {
            return Err(EngineError::limit("the finished PDF would have too many objects"));
        }
        self.offsets.push(0);
        Ok(self.offsets.len() as u32 - 1)
    }

    fn check_cancelled(&self) -> Result<()> {
        if self.observer.is_cancelled() { Err(EngineError::cancelled()) } else { Ok(()) }
    }

    /// Called once per object: the observer may be slow to reach.
    fn object_done(&mut self) -> Result<()> {
        self.objects_since_check += 1;
        if self.objects_since_check >= CANCEL_CHECK_EVERY {
            self.objects_since_check = 0;
            self.check_cancelled()?;
        }
        Ok(())
    }

    fn begin(&mut self, number: u32) -> Result<()> {
        self.offsets[number as usize] = self.pos;
        self.write(format!("{number} 0 obj\n").as_bytes())
    }

    /// Writes a value with no encryption, as the encryption dictionary must be.
    fn write_plain_value(&mut self, number: u32, value: &Obj) -> Result<()> {
        self.begin(number)?;
        let mut bytes = Vec::new();
        write_obj(&mut bytes, value);
        bytes.extend_from_slice(b"\nendobj\n");
        self.write(&bytes)
    }

    fn write_value(&mut self, number: u32, mut value: Obj) -> Result<()> {
        if let Some(encryptor) = &self.encryptor {
            encryptor.encrypt_strings(&mut value)?;
        }
        self.write_plain_value(number, &value)
    }

    fn begin_stream(&mut self, number: u32, dict: Dict) -> Result<()> {
        let mut value = Obj::Dict(dict);
        if let Some(encryptor) = &self.encryptor {
            encryptor.encrypt_strings(&mut value)?;
        }
        self.begin(number)?;
        let mut bytes = Vec::new();
        write_obj(&mut bytes, &value);
        bytes.extend_from_slice(b"\nstream\n");
        self.write(&bytes)
    }

    fn end_stream(&mut self) -> Result<()> {
        self.write(b"\nendstream\nendobj\n")
    }

    /// Writes stream data held in memory, encrypting it if the output is encrypted.
    fn write_stream(&mut self, number: u32, mut dict: Dict, data: &[u8]) -> Result<()> {
        let encrypted = match &self.encryptor {
            Some(encryptor) => Some(encryptor.encrypt(data)?),
            None => None,
        };
        let data = encrypted.as_deref().unwrap_or(data);
        dict.set("Length", Obj::int(data.len() as u64));
        self.begin_stream(number, dict)?;
        self.write(data)?;
        self.end_stream()
    }

    /// The cross-reference table and trailer. Returns the file's size.
    fn finish(&mut self, catalog: u32, info: Option<u32>) -> Result<u64> {
        let mut id = [0u8; 16];
        secure_random(&mut id)?;
        let mut trailer = Dict::of(vec![("Root", Obj::Ref(catalog, 0))]);
        if let Some(info) = info {
            trailer.set("Info", Obj::Ref(info, 0));
        }
        trailer.set("ID", Obj::Array(vec![Obj::Str(id.to_vec()), Obj::Str(id.to_vec())]));
        if let Some(encryptor) = &self.encryptor {
            let dict = Obj::Dict(encryptor.dict().clone());
            let number = self.allocate()?;
            self.write_plain_value(number, &dict)?;
            trailer.set("Encrypt", Obj::Ref(number, 0));
        }
        if self.offsets.iter().skip(1).any(|offset| *offset == 0) {
            return Err(EngineError::new(ErrorKind::Internal, "an object was numbered but never written"));
        }
        let size = self.offsets.len();
        trailer.set("Size", Obj::int(size as u64));
        let xref_at = self.pos;
        let mut table = Vec::with_capacity(size * 20 + 64);
        table.extend_from_slice(format!("xref\n0 {size}\n0000000000 65535 f \n").as_bytes());
        for offset in self.offsets.iter().skip(1) {
            table.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
        }
        table.extend_from_slice(b"trailer\n");
        write_obj(&mut table, &Obj::Dict(trailer));
        table.extend_from_slice(format!("\nstartxref\n{xref_at}\n%%EOF\n").as_bytes());
        self.write(&table)?;
        self.out.flush()?;
        Ok(self.pos)
    }
}

/// The transform a resize applies to page content and annotation coordinates.
#[derive(Debug, Clone, Copy)]
struct Fit {
    scale: f64,
    dx: f64,
    dy: f64,
    /// The new media box, before the page's rotation.
    width: f64,
    height: f64,
}

impl Fit {
    /// `displayed` is what the reader should see, so a turned page's box swaps sides.
    fn new(node: &PageNode, displayed: (f64, f64)) -> Self {
        let geometry = node.page.geometry;
        let (width, height) = if geometry.rotate % 180 == 0 { displayed } else { (displayed.1, displayed.0) };
        let scale = (width / geometry.width()).min(height / geometry.height());
        Self {
            scale,
            dx: (width - scale * geometry.width()) / 2.0 - scale * geometry.x0,
            dy: (height - scale * geometry.height()) / 2.0 - scale * geometry.y0,
            width,
            height,
        }
    }

    fn apply_to_points(&self, items: &mut [Obj]) {
        for pair in items.chunks_exact_mut(2) {
            if let (Some(x), Some(y)) = (pair[0].as_f64(), pair[1].as_f64()) {
                pair[0] = Obj::number(x * self.scale + self.dx);
                pair[1] = Obj::number(y * self.scale + self.dy);
            }
        }
    }
}

/// One input being rewritten: its object-number map and what's left to write.
struct Source<R> {
    file: PdfFile<R>,
    pages: Vec<PageNode>,
    page_objects: HashSet<u32>,
    /// Kept pages, old number to new. A page used twice answers with its first copy.
    kept: HashMap<u32, u32>,
    map: HashMap<u32, u32>,
    queue: VecDeque<u32>,
    /// Annotations prepared for their page, written in place of the original.
    overrides: HashMap<u32, Dict>,
    named_dests: Option<HashMap<Vec<u8>, Obj>>,
    encrypt_object: Option<u32>,
    /// The catalog comes along, so links and fields stay as they are.
    whole_document: bool,
}

impl<R: Read + Seek> Source<R> {
    fn open(mut source: PdfToolSource<R>, op: &PdfToolOp) -> Result<Self> {
        let size = source.reader.seek(SeekFrom::End(0))?;
        if size > Limits::default().max_input_bytes {
            return Err(EngineError::limit("this PDF is too large to process"));
        }
        let mut file = PdfFile::open(source.reader)?;
        file.unlock(source.password.as_deref().map(String::as_str))?;
        check_permissions(&file, op)?;
        let pages = file.page_nodes()?;
        let page_objects = pages.iter().map(|node| node.page.object).collect();
        let encrypt_object = match file.trailer.get("Encrypt") {
            Some(Obj::Ref(number, _)) => Some(*number),
            _ => None,
        };
        Ok(Self {
            file,
            pages,
            page_objects,
            kept: HashMap::new(),
            map: HashMap::new(),
            queue: VecDeque::new(),
            overrides: HashMap::new(),
            named_dests: None,
            encrypt_object,
            whole_document: false,
        })
    }

    /// The new number for an old one, queueing the object the first time. `None`
    /// if the reference can't be kept: the holder then drops it, so nothing dangles.
    fn map_ref<W: Write>(&mut self, old: u32, output: &mut Output<'_, W>) -> Result<Option<u32>> {
        if self.page_objects.contains(&old) {
            return Ok(self.kept.get(&old).copied());
        }
        if let Some(new) = self.map.get(&old) {
            return Ok(Some(*new));
        }
        if !self.file.has_object(old) || self.encrypt_object == Some(old) {
            return Ok(None);
        }
        let new = output.allocate()?;
        self.map.insert(old, new);
        self.queue.push_back(old);
        Ok(Some(new))
    }

    /// Gives `value` new reference numbers. Depth is bounded by the parser's.
    /// `None` only for a bare reference that can't be kept.
    fn renumber<W: Write>(&mut self, value: Obj, output: &mut Output<'_, W>) -> Result<Option<Obj>> {
        Ok(Some(match value {
            Obj::Ref(old, _) => match self.map_ref(old, output)? {
                Some(new) => Obj::Ref(new, 0),
                None => return Ok(None),
            },
            Obj::Array(items) => {
                let mut out = Vec::with_capacity(items.len());
                for item in items {
                    out.push(self.renumber(item, output)?.unwrap_or(Obj::Null));
                }
                Obj::Array(out)
            }
            Obj::Dict(dict) => Obj::Dict(self.renumber_dict(dict, output)?),
            other => other,
        }))
    }

    fn renumber_dict<W: Write>(&mut self, dict: Dict, output: &mut Output<'_, W>) -> Result<Dict> {
        let mut out = Vec::with_capacity(dict.0.len());
        for (key, value) in dict.0 {
            if let Some(value) = self.renumber(value, output)? {
                out.push((key, value));
            }
        }
        Ok(Dict(out))
    }

    /// Writes everything queued, including what those objects reach in turn.
    fn drain<W: Write>(&mut self, output: &mut Output<'_, W>) -> Result<()> {
        while let Some(old) = self.queue.pop_front() {
            output.object_done()?;
            self.write_object(old, output)?;
        }
        Ok(())
    }

    fn write_object<W: Write>(&mut self, old: u32, output: &mut Output<'_, W>) -> Result<()> {
        let new = self.map[&old];
        if let Some(dict) = self.overrides.remove(&old) {
            let value = Obj::Dict(self.renumber_dict(dict, output)?);
            return output.write_value(new, value);
        }
        let LoadedObject { value, generation, stream_at } = self.file.load(old)?;
        if let Obj::Dict(dict) = &value {
            // Reached by a stray reference: pulling these in would drag the whole
            // old document along, and stale copies of them mean nothing here.
            let structural = matches!(
                dict.get("Type").and_then(Obj::as_name),
                Some(b"Catalog" | b"Pages" | b"Page" | b"ObjStm" | b"XRef")
            );
            if structural {
                return output.write_value(new, Obj::Null);
            }
        }
        match (value, stream_at) {
            (Obj::Dict(dict), Some(at)) => self.write_stream_object(old, new, generation, dict, at, output),
            (value, _) => {
                let value = self.renumber(value, output)?.unwrap_or(Obj::Null);
                output.write_value(new, value)
            }
        }
    }

    /// Compressed data passes through as stored. It is only held in memory when
    /// it has to be decrypted or encrypted, and then within the structure bound.
    fn write_stream_object<W: Write>(
        &mut self,
        old: u32,
        new: u32,
        generation: u16,
        dict: Dict,
        at: u64,
        output: &mut Output<'_, W>,
    ) -> Result<()> {
        let length = match dict.get("Length") {
            Some(length) => self.file.resolve(length)?.as_i64().unwrap_or(0).max(0) as u64,
            None => 0,
        };
        let stored_plain = self.file.crypt.as_ref().is_none_or(|crypt| crypt.stream_is_plain(&dict));
        let source_dict = dict.clone();
        let mut dict = dict;
        dict.remove("Length");
        remove_crypt_filter(&mut dict);
        let mut dict = self.renumber_dict(dict, output)?;
        if stored_plain && output.encryptor.is_none() {
            let extent = self.file.stream_extent(at, length)?;
            dict.set("Length", Obj::int(extent));
            output.begin_stream(new, dict)?;
            self.file.read_chunks(at, extent, |chunk| {
                output.check_cancelled()?;
                output.write(chunk)
            })?;
            return output.end_stream();
        }
        let raw = self.file.stream_raw(at, length)?;
        let plain = match &self.file.crypt {
            Some(crypt) => crypt.decrypt_stream(old, generation, &source_dict, &raw)?,
            None => raw,
        };
        output.write_stream(new, dict, &plain)
    }

    /// One output page, then everything it reaches, so little stays in memory.
    fn write_page<W: Write>(
        &mut self,
        pick: &Pick,
        number: u32,
        parent: u32,
        resize: Option<(f64, f64)>,
        dropped: &mut PdfDropped,
        output: &mut Output<'_, W>,
    ) -> Result<()> {
        let node = self.pages[pick.page].clone();
        let Obj::Dict(mut page) = self.file.object(node.page.object)? else {
            return Err(EngineError::malformed("the PDF's page tree is damaged"));
        };
        for key in ["Parent", "B", "Thumb", "Rotate"] {
            page.remove(key);
        }
        if page.get("MediaBox").is_none() {
            let media = node.inherited.get("MediaBox").cloned();
            page.set("MediaBox", media.unwrap_or(Obj::Array(DEFAULT_MEDIA_BOX.iter().map(|v| Obj::int(*v)).collect())));
        }
        for key in ["CropBox", "Resources"] {
            if page.get(key).is_none() {
                if let Some(value) = node.inherited.get(key) {
                    page.set(key, value.clone());
                }
            }
        }
        if page.get("Resources").is_none() {
            page.set("Resources", Obj::Dict(Dict::default()));
        }
        let rotate = (i64::from(node.page.geometry.rotate) + pick.rotation).rem_euclid(360);
        if rotate != 0 {
            page.set("Rotate", Obj::int(rotate as u64));
        }

        let fit = resize.map(|displayed| Fit::new(&node, displayed));
        if let Some(fit) = &fit {
            for key in ["CropBox", "BleedBox", "TrimBox", "ArtBox", "UserUnit"] {
                page.remove(key);
            }
            let media = vec![Obj::int(0), Obj::int(0), Obj::number(fit.width), Obj::number(fit.height)];
            page.set("MediaBox", Obj::Array(media.clone()));
            page.set("CropBox", Obj::Array(media));
        }
        let annotations = self.prepare_annotations(&mut page, fit.as_ref(), dropped)?;
        let contents = if fit.is_some() { page.get("Contents").cloned() } else { None };
        if fit.is_some() {
            page.remove("Contents");
        }

        let mut page = self.renumber_dict(page, output)?;
        if let Some(annotations) = annotations {
            let renumbered = self.renumber(Obj::Array(annotations), output)?;
            if let Some(Obj::Array(items)) = renumbered {
                let items: Vec<Obj> = items.into_iter().filter(|item| *item != Obj::Null).collect();
                if !items.is_empty() {
                    page.set("Annots", Obj::Array(items));
                }
            }
        }
        page.set("Type", Obj::name("Page"));
        page.set("Parent", Obj::Ref(parent, 0));
        if let Some(fit) = &fit {
            let wrapped = self.wrap_contents(contents, fit, &node, output)?;
            page.set("Contents", Obj::Array(wrapped));
        }
        output.write_value(number, Obj::Dict(page))?;
        self.drain(output)
    }

    /// `q`, the transform and a clip to the old visible box, then the page's own
    /// streams, then `Q`. Without the clip, what the crop box hid would show in
    /// the margins. Returns the new `/Contents`, already renumbered.
    fn wrap_contents<W: Write>(
        &mut self,
        contents: Option<Obj>,
        fit: &Fit,
        node: &PageNode,
        output: &mut Output<'_, W>,
    ) -> Result<Vec<Obj>> {
        let geometry = node.page.geometry;
        let scale = fit.scale;
        let before = format!(
            "q {scale:.6} 0 0 {scale:.6} {:.6} {:.6} cm {:.6} {:.6} {:.6} {:.6} re W n\n",
            fit.dx,
            fit.dy,
            geometry.x0,
            geometry.y0,
            geometry.width(),
            geometry.height(),
        );
        let first = output.allocate()?;
        output.write_stream(first, Dict::default(), before.as_bytes())?;

        let mut wrapped = vec![Obj::Ref(first, 0)];
        let items = match contents {
            None => Vec::new(),
            Some(contents) => match self.file.resolve(&contents)? {
                // A reference to one stream stays one; only an array is opened up.
                Obj::Array(items) => items,
                _ => vec![contents],
            },
        };
        for item in items {
            if let Some(renumbered @ Obj::Ref(..)) = self.renumber(item, output)? {
                wrapped.push(renumbered);
            }
        }
        let last = output.allocate()?;
        output.write_stream(last, Dict::default(), b"\nQ\n")?;
        wrapped.push(Obj::Ref(last, 0));
        Ok(wrapped)
    }

    /// Takes `/Annots` out of `page`, resolved and readied for this output. The
    /// items still have old numbers.
    fn prepare_annotations(
        &mut self,
        page: &mut Dict,
        fit: Option<&Fit>,
        dropped: &mut PdfDropped,
    ) -> Result<Option<Vec<Obj>>> {
        let Some(annots) = page.get("Annots").cloned() else { return Ok(None) };
        page.remove("Annots");
        let Obj::Array(items) = self.file.resolve(&annots)? else { return Ok(None) };
        let mut out = Vec::with_capacity(items.len());
        for item in items {
            match item {
                Obj::Ref(number, generation) => {
                    // Already written with an earlier copy of this page.
                    if self.map.contains_key(&number) {
                        out.push(Obj::Ref(number, generation));
                        continue;
                    }
                    if !self.file.has_object(number) {
                        continue;
                    }
                    let Obj::Dict(dict) = self.file.object(number)? else { continue };
                    if let Some(dict) = self.prepare_annotation(dict, fit, dropped)? {
                        self.overrides.insert(number, dict);
                        out.push(Obj::Ref(number, generation));
                    }
                }
                Obj::Dict(dict) => {
                    if let Some(dict) = self.prepare_annotation(dict, fit, dropped)? {
                        out.push(Obj::Dict(dict));
                    }
                }
                _ => {}
            }
        }
        Ok(Some(out))
    }

    /// `None` means the annotation can't work in the output, and goes.
    fn prepare_annotation(
        &mut self,
        mut dict: Dict,
        fit: Option<&Fit>,
        dropped: &mut PdfDropped,
    ) -> Result<Option<Dict>> {
        let subtype = match dict.get("Subtype") {
            Some(subtype) => self.file.resolve(subtype)?,
            None => Obj::Null,
        };
        if !self.whole_document {
            match subtype.as_name() {
                Some(b"Link") => {
                    if !self.retarget_link(&mut dict)? {
                        dropped.links += 1;
                        return Ok(None);
                    }
                }
                Some(b"Widget") => {
                    // The form is gone: keep how the field looks. That also takes a
                    // signature's value away, which rewriting has voided anyway.
                    dict.0.retain(|(key, _)| WIDGET_KEEPS.contains(&key.as_slice()));
                }
                _ => {}
            }
        }
        if let Some(fit) = fit {
            self.fit_annotation(&mut dict, fit)?;
        }
        Ok(Some(dict))
    }

    fn fit_annotation(&mut self, dict: &mut Dict, fit: &Fit) -> Result<()> {
        for key in ANNOTATION_POINTS {
            let Some(value) = dict.get(key).cloned() else { continue };
            if let Obj::Array(mut items) = self.resolved_array(&value)? {
                fit.apply_to_points(&mut items);
                dict.set(key, Obj::Array(items));
            }
        }
        if let Some(value) = dict.get("InkList").cloned() {
            if let Obj::Array(strokes) = self.file.resolve(&value)? {
                let mut out = Vec::with_capacity(strokes.len());
                for stroke in &strokes {
                    let mut stroke = self.resolved_array(stroke)?;
                    if let Obj::Array(items) = &mut stroke {
                        fit.apply_to_points(items);
                    }
                    out.push(stroke);
                }
                dict.set("InkList", Obj::Array(out));
            }
        }
        Ok(())
    }

    /// An array with its elements direct, so numbers in it can be changed.
    fn resolved_array(&mut self, value: &Obj) -> Result<Obj> {
        let Obj::Array(items) = self.file.resolve(value)? else { return Ok(Obj::Null) };
        let mut out = Vec::with_capacity(items.len());
        for item in &items {
            out.push(self.file.resolve(item)?);
        }
        Ok(Obj::Array(out))
    }

    /// Points a link straight at its page, since the name table isn't carried.
    /// False if it leads to a page that isn't kept. Other actions are left alone.
    fn retarget_link(&mut self, dict: &mut Dict) -> Result<bool> {
        let target = if let Some(dest) = dict.get("Dest") {
            dest.clone()
        } else {
            let Some(action) = dict.get("A").cloned() else { return Ok(true) };
            let Obj::Dict(action) = self.file.resolve(&action)? else { return Ok(true) };
            let kind = match action.get("S") {
                Some(kind) => self.file.resolve(kind)?,
                None => Obj::Null,
            };
            if kind.as_name() != Some(b"GoTo") {
                return Ok(true);
            }
            match action.get("D") {
                Some(dest) => dest.clone(),
                None => return Ok(false),
            }
        };
        let Some(items) = self.destination(&target)? else { return Ok(false) };
        let reaches_kept = matches!(items.first(), Some(Obj::Ref(page, _)) if self.kept.contains_key(page));
        if reaches_kept {
            dict.remove("A");
            dict.set("Dest", Obj::Array(items));
        }
        Ok(reaches_kept)
    }

    /// The explicit `[page /Fit ...]` a destination comes to, following a name
    /// once and a `<</D ...>>` wrapper once on each side of it.
    fn destination(&mut self, target: &Obj) -> Result<Option<Vec<Obj>>> {
        let mut current = self.file.resolve(target)?;
        for _ in 0..4 {
            current = match current {
                Obj::Array(items) => return Ok(Some(items)),
                Obj::Dict(dict) => match dict.get("D") {
                    Some(inner) => self.file.resolve(inner)?,
                    None => return Ok(None),
                },
                Obj::Name(name) | Obj::Str(name) => match self.named_dest(&name)? {
                    Some(value) => self.file.resolve(&value)?,
                    None => return Ok(None),
                },
                _ => return Ok(None),
            };
        }
        Ok(None)
    }

    fn named_dest(&mut self, name: &[u8]) -> Result<Option<Obj>> {
        if self.named_dests.is_none() {
            self.named_dests = Some(self.read_named_dests()?);
        }
        Ok(self.named_dests.as_ref().and_then(|dests| dests.get(name).cloned()))
    }

    /// The catalog's old `/Dests` dictionary and `/Names /Dests` tree, within
    /// bounds. A file past them only loses the links it doesn't get to.
    fn read_named_dests(&mut self) -> Result<HashMap<Vec<u8>, Obj>> {
        let mut dests = HashMap::new();
        let catalog = self.file.catalog()?;
        if let Some(old) = catalog.get("Dests") {
            if let Obj::Dict(old) = self.file.resolve(old)? {
                dests.extend(old.0.into_iter().take(MAX_NAMED_DESTS));
            }
        }
        let Some(names) = catalog.get("Names") else { return Ok(dests) };
        let Obj::Dict(names) = self.file.resolve(names)? else { return Ok(dests) };
        let Some(root) = names.get("Dests") else { return Ok(dests) };
        let mut stack = vec![root.clone()];
        let mut seen = HashSet::new();
        let mut visited = 0;
        while let Some(node) = stack.pop() {
            visited += 1;
            if visited > MAX_NAME_TREE_NODES || dests.len() >= MAX_NAMED_DESTS {
                break;
            }
            if let Obj::Ref(number, _) = node {
                if !seen.insert(number) {
                    continue;
                }
            }
            let Obj::Dict(node) = self.file.resolve(&node)? else { continue };
            if let Some(kids) = node.get("Kids") {
                if let Obj::Array(kids) = self.file.resolve(kids)? {
                    stack.extend(kids);
                }
            }
            if let Some(pairs) = node.get("Names") {
                if let Obj::Array(pairs) = self.file.resolve(pairs)? {
                    for pair in pairs.chunks_exact(2) {
                        if let Obj::Str(name) = self.file.resolve(&pair[0])? {
                            dests.insert(name, pair[1].clone());
                        }
                    }
                }
            }
        }
        Ok(dests)
    }

    /// Writes the document information, returning its new number.
    fn write_info<W: Write>(&mut self, output: &mut Output<'_, W>) -> Result<Option<u32>> {
        let Some(info) = self.file.trailer.get("Info").cloned() else { return Ok(None) };
        let number = match info {
            Obj::Ref(old, _) => {
                if !matches!(self.file.object(old)?, Obj::Dict(_)) {
                    return Ok(None);
                }
                self.map_ref(old, output)?
            }
            Obj::Dict(dict) => {
                let number = output.allocate()?;
                let value = Obj::Dict(self.renumber_dict(dict, output)?);
                output.write_value(number, value)?;
                Some(number)
            }
            _ => None,
        };
        self.drain(output)?;
        Ok(number)
    }
}

/// Removes `/Crypt` from a stream's filters: the output's own encryption, or
/// none, applies to it now.
fn remove_crypt_filter(dict: &mut Dict) {
    let parms_key = if dict.get("DecodeParms").is_some() { "DecodeParms" } else { "DP" };
    match dict.get("Filter").cloned() {
        Some(Obj::Name(name)) if name == b"Crypt" => {
            dict.remove("Filter");
            dict.remove(parms_key);
        }
        Some(Obj::Array(mut filters)) => {
            let Some(at) = filters.iter().position(|f| f.as_name() == Some(b"Crypt")) else { return };
            filters.remove(at);
            if let Some(Obj::Array(mut parms)) = dict.get(parms_key).cloned() {
                if at < parms.len() {
                    parms.remove(at);
                }
                dict.set(parms_key, Obj::Array(parms));
            }
            if filters.is_empty() {
                dict.remove("Filter");
                dict.remove(parms_key);
            } else {
                dict.set("Filter", Obj::Array(filters));
            }
        }
        _ => {}
    }
}

/// The source's own catalog, carried whole: its pages are all in the output, so
/// outlines, forms, names and tags all still point at something. Whatever claims
/// a state this rewrite ended goes.
fn whole_catalog<R: Read + Seek, W: Write>(source: &mut Source<R>, output: &mut Output<'_, W>) -> Result<Dict> {
    let mut catalog = source.file.catalog()?;
    for key in ["Pages", "Version", "Perms"] {
        catalog.remove(key);
    }
    if source.file.metadata_declares_pdfa(&catalog) {
        catalog.remove("Metadata");
        catalog.remove("OutputIntents");
    }
    let catalog = source.renumber_dict(catalog, output)?;
    source.drain(output)?;
    Ok(catalog)
}

/// A new catalog with what still applies per page: language, optional-content
/// layers (or hidden layers would show) and, from a single source, metadata.
fn kept_catalog<R: Read + Seek, W: Write>(
    sources: &mut [Source<R>],
    dropped: &mut PdfDropped,
    output: &mut Output<'_, W>,
) -> Result<Dict> {
    let mut catalog = Dict::default();
    let (mut groups, mut off) = (Vec::new(), Vec::new());
    let single = sources.len() == 1;
    for (index, source) in sources.iter_mut().enumerate() {
        let original = source.file.catalog()?;
        dropped.outlines |= original.get("Outlines").is_some();
        dropped.forms |= original.get("AcroForm").is_some();
        dropped.structure_tags |= original.get("StructTreeRoot").is_some();
        dropped.page_labels |= original.get("PageLabels").is_some();
        dropped.named_destinations |= original.get("Dests").is_some();
        if let Some(names) = original.get("Names") {
            if let Obj::Dict(names) = source.file.resolve(names)? {
                dropped.named_destinations |= names.get("Dests").is_some();
                dropped.attachments |= names.get("EmbeddedFiles").is_some();
            }
        }
        if index == 0 {
            if let Some(language) = original.get("Lang") {
                if let Some(language) = source.renumber(language.clone(), output)? {
                    catalog.set("Lang", language);
                }
            }
            if single && !source.file.metadata_declares_pdfa(&original) {
                if let Some(metadata) = original.get("Metadata") {
                    if let Some(metadata) = source.renumber(metadata.clone(), output)? {
                        catalog.set("Metadata", metadata);
                    }
                }
            }
        }
        source.carry_layers(&original, &mut groups, &mut off, output)?;
        source.drain(output)?;
    }
    if !groups.is_empty() {
        let default = Dict::of(vec![("OFF", Obj::Array(off))]);
        let layers = Dict::of(vec![("OCGs", Obj::Array(groups)), ("D", Obj::Dict(default))]);
        catalog.set("OCProperties", Obj::Dict(layers));
    }
    Ok(catalog)
}

impl<R: Read + Seek> Source<R> {
    fn carry_layers<W: Write>(
        &mut self,
        catalog: &Dict,
        groups: &mut Vec<Obj>,
        off: &mut Vec<Obj>,
        output: &mut Output<'_, W>,
    ) -> Result<()> {
        let Some(layers) = catalog.get("OCProperties") else { return Ok(()) };
        let Obj::Dict(layers) = self.file.resolve(layers)? else { return Ok(()) };
        let Some(all) = layers.get("OCGs") else { return Ok(()) };
        let Obj::Array(all) = self.file.resolve(all)? else { return Ok(()) };
        let default = match layers.get("D") {
            Some(default) => self.file.resolve(default)?,
            None => Obj::Null,
        };
        let Obj::Dict(default) = default else {
            for group in all {
                if let Some(group) = self.renumber(group, output)? {
                    groups.push(group);
                }
            }
            return Ok(());
        };
        let list = |source: &mut Self, key: &str| -> Result<Vec<Obj>> {
            let Some(value) = default.get(key) else { return Ok(Vec::new()) };
            Ok(match source.file.resolve(value)? {
                Obj::Array(items) => items,
                _ => Vec::new(),
            })
        };
        let hidden = if default.get("BaseState").and_then(Obj::as_name) == Some(b"OFF") {
            let on = list(self, "ON")?;
            all.iter().filter(|group| !on.contains(group)).cloned().collect()
        } else {
            list(self, "OFF")?
        };
        for group in all {
            if let Some(group) = self.renumber(group, output)? {
                groups.push(group);
            }
        }
        for group in hidden {
            if let Some(group) = self.renumber(group, output)? {
                off.push(group);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
#[path = "pdf_tools_tests.rs"]
mod tests;
