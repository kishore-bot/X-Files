//! Writes the page a viewer would show for a document, for checking layout
//! in a desktop browser: `cargo run --example render_page -- in.docx out.html`.
//! Word documents give print-layout pages, Markdown its preview.

use std::fs::File;

use document_core::Limits;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (input, output) = (&args[1], &args[2]);
    let resource = if input.ends_with(".md") { "ezfiles:preview.html" } else { "ezfiles:pages.html" };
    let mut session = document_parsers::engine(Limits::default())
        .open_named(File::open(input).expect("input"), Some(input.clone()))
        .expect("open");
    let page = session.resource(resource).expect("render");
    std::fs::write(output, page).expect("write");
}
