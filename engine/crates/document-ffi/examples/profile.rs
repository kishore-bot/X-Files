//! Times opening and reading documents end to end, through the same FFI entry
//! points the app calls, without UniFFI's Kotlin side.
//!
//! ```sh
//! cargo run --release --example profile -- big.txt big.xlsx big.docx big.pdf
//! ```
//!
//! For each file it prints the open time, the time to read every batch, and
//! the batch and block counts. Run it before and after changing a parser.

use std::time::Instant;

use document_ffi::{NativeBlock, NativeLimits, open_path};

fn main() {
    let paths: Vec<String> = std::env::args().skip(1).collect();
    if paths.is_empty() {
        eprintln!("usage: profile <file>...");
        std::process::exit(2);
    }
    // The app's defaults (ReadLimits.Default): 256 MiB input, 64 KiB batches, 16 MiB total.
    let limits = NativeLimits {
        max_input_bytes: 256 * 1024 * 1024,
        max_batch_bytes: 64 * 1024,
        max_total_output_bytes: 16 * 1024 * 1024,
    };
    println!("{:<28} {:>10} {:>10} {:>8} {:>9} {:>6}", "file", "open ms", "read ms", "batches", "blocks", "trunc");
    for path in paths {
        let name = std::path::Path::new(&path).file_name().map(|n| n.to_string_lossy().into_owned());
        let started = Instant::now();
        let session = match open_path(path.clone(), name, limits, None) {
            Ok(s) => s,
            Err(e) => {
                println!("{path}: {e:?}");
                continue;
            }
        };
        let opened = started.elapsed();
        let (mut batches, mut blocks, mut truncated) = (0usize, 0usize, false);
        let reading = Instant::now();
        loop {
            match session.next_batch() {
                Ok(Some(batch)) => {
                    batches += 1;
                    blocks += batch.blocks.iter().filter(|b| !matches!(b, NativeBlock::Divider)).count();
                    truncated |= batch.truncated;
                    if batch.is_last {
                        break;
                    }
                }
                Ok(None) => break,
                Err(e) => {
                    println!("{path}: read failed: {e:?}");
                    break;
                }
            }
        }
        let read = reading.elapsed();
        println!(
            "{:<28} {:>10.1} {:>10.1} {:>8} {:>9} {:>6}",
            std::path::Path::new(&path).file_name().unwrap().to_string_lossy(),
            opened.as_secs_f64() * 1e3,
            read.as_secs_f64() * 1e3,
            batches,
            blocks,
            truncated
        );
    }
}
