//! Repeatable index persistence and committed query measurements with generated, non-user content.
use document_search::{Added, IndexConfig, SearchIndex};
use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

struct Scratch(PathBuf);

impl Scratch {
    fn new() -> io::Result<Self> {
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
        let path = std::env::temp_dir().join(format!("xfiles-index-{}-{nonce}", std::process::id()));
        fs::create_dir(&path)?;
        Ok(Self(path))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn add_range(index: &SearchIndex, from: usize, to: usize) -> io::Result<()> {
    for number in from..to {
        let key = format!("fixture-{number:05}");
        let text = format!(
            "Invoice customer {} project {} quarterly budget forecast revenue expense category estimate reconciliation.",
            number % 97,
            number % 31,
        );
        if index.add(&key, text.len() as u64, 1, &text, &[])? != Added::Indexed {
            return Err(io::Error::other(format!("Index capacity reached after {number} documents")));
        }
    }
    Ok(())
}

fn ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1000.0
}

fn sample(count: usize) -> io::Result<()> {
    let scratch = Scratch::new()?;
    let config = IndexConfig::default();
    let writer = SearchIndex::open(&scratch.0, config);
    let base = count.saturating_sub(64);
    add_range(&writer, 0, base)?;
    writer.save()?;
    let snapshot_started = Instant::now();
    let committed = SearchIndex::committed(&scratch.0, config);
    let load_time = snapshot_started.elapsed();

    // Query an already-loaded generation before and throughout the next batch and index save.
    let stop = AtomicBool::new(false);
    let (read_latencies, update) = std::thread::scope(|scope| {
        let reader = scope.spawn(|| {
            let mut samples = Vec::new();
            while !stop.load(Ordering::Acquire) || samples.len() < 20 {
                let started = Instant::now();
                std::hint::black_box(committed.query("quarterly budget ", 50));
                samples.push(started.elapsed());
            }
            samples
        });
        let update = (|| -> io::Result<_> {
            add_range(&writer, base, count)?;
            let started = Instant::now();
            writer.save()?;
            Ok(started.elapsed())
        })();
        stop.store(true, Ordering::Release);
        (reader.join().expect("query thread panicked"), update)
    });
    let mut samples = read_latencies;
    samples.sort_unstable();
    println!(
        "{count:>6} docs  snapshot load {:>8.2} ms  commit {:>8.2} ms  query p50 {:>8.2} ms  p95 {:>8.2} ms  ({} samples)",
        ms(load_time),
        ms(update?),
        ms(samples[samples.len() / 2]),
        ms(samples[(samples.len() * 95).div_ceil(100) - 1]),
        samples.len(),
    );
    Ok(())
}

fn main() -> io::Result<()> {
    let counts = std::env::args()
        .skip(1)
        .map(|value| {
            value.parse::<usize>().ok().filter(|&count| (1..=20_000).contains(&count))
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Expected a document count from 1 to 20000"))
        })
        .collect::<io::Result<Vec<_>>>()?;
    for count in if counts.is_empty() { vec![1_000, 10_000, 20_000] } else { counts } {
        sample(count)?;
    }
    Ok(())
}
