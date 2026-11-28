//! Command-line options. `--root` is required and may repeat; every other option has a default.

use std::path::PathBuf;
use std::time::Duration;

pub const DEFAULT_TIMEOUT_SECS: u64 = 120;
pub const DEFAULT_MAX_INPUT_MB: u64 = 256;

pub const USAGE: &str = "\
xfiles-mcp: Xfiles document tools over the Model Context Protocol (stdio).

USAGE:
    xfiles-mcp --root <folder> [--root <folder> ...] [options]

OPTIONS:
    --root <folder>         Folder the server may read and write. Repeatable. Required.
    --timeout-secs <n>      Stop any single operation after n seconds (1-3600). Default 120.
    --max-input-mb <n>      Refuse input files larger than n megabytes (1-2048). Default 256.
    --version               Print the version and exit.
    -h, --help              Print this help and exit.";

#[derive(Debug, PartialEq, Eq)]
pub enum Command {
    Serve(Config),
    Help,
    Version,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Config {
    pub roots: Vec<PathBuf>,
    pub timeout: Duration,
    pub max_input_bytes: u64,
}

pub fn parse<I>(args: I) -> Result<Command, String>
where
    I: IntoIterator<Item = String>,
{
    let mut roots = Vec::new();
    let mut timeout_secs = DEFAULT_TIMEOUT_SECS;
    let mut max_input_mb = DEFAULT_MAX_INPUT_MB;
    let mut args = args.into_iter();

    while let Some(arg) = args.next() {
        let (flag, inline) = match arg.split_once('=') {
            Some((flag, value)) if flag.starts_with("--") => (flag.to_owned(), Some(value.to_owned())),
            _ => (arg.clone(), None),
        };
        match flag.as_str() {
            "-h" | "--help" => return Ok(Command::Help),
            "--version" => return Ok(Command::Version),
            "--root" => roots.push(PathBuf::from(value_of(&flag, inline, &mut args)?)),
            "--timeout-secs" => {
                let raw = value_of(&flag, inline, &mut args)?;
                timeout_secs = number(&raw, &flag, 1, 3600)?;
            }
            "--max-input-mb" => {
                let raw = value_of(&flag, inline, &mut args)?;
                max_input_mb = number(&raw, &flag, 1, 2048)?;
            }
            other => return Err(format!("unknown option {other}; run with --help")),
        }
    }

    if roots.is_empty() {
        return Err("at least one --root <folder> is required; run with --help".to_owned());
    }
    Ok(Command::Serve(Config {
        roots,
        timeout: Duration::from_secs(timeout_secs),
        max_input_bytes: max_input_mb * 1024 * 1024,
    }))
}

fn value_of(flag: &str, inline: Option<String>, args: &mut impl Iterator<Item = String>) -> Result<String, String> {
    match inline {
        Some(value) if !value.is_empty() => Ok(value),
        Some(_) => Err(format!("{flag} needs a value")),
        None => args.next().ok_or_else(|| format!("{flag} needs a value")),
    }
}

fn number(raw: &str, flag: &str, min: u64, max: u64) -> Result<u64, String> {
    let value: u64 = raw.parse().map_err(|_| format!("{flag} must be a whole number, got {raw:?}"))?;
    if (min..=max).contains(&value) {
        Ok(value)
    } else {
        Err(format!("{flag} must be between {min} and {max}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn collects_repeated_roots_and_defaults() {
        let Command::Serve(config) = parse(args(&["--root", "C:/a", "--root=D:/b"])).unwrap() else {
            panic!("expected serve");
        };
        assert_eq!(config.roots, vec![PathBuf::from("C:/a"), PathBuf::from("D:/b")]);
        assert_eq!(config.timeout, Duration::from_secs(DEFAULT_TIMEOUT_SECS));
        assert_eq!(config.max_input_bytes, DEFAULT_MAX_INPUT_MB * 1024 * 1024);
    }

    #[test]
    fn requires_a_root() {
        assert!(parse(args(&[])).is_err());
        assert!(parse(args(&["--timeout-secs", "5"])).is_err());
    }

    #[test]
    fn bounds_numeric_options() {
        assert!(parse(args(&["--root", "x", "--timeout-secs", "0"])).is_err());
        assert!(parse(args(&["--root", "x", "--timeout-secs", "99999"])).is_err());
        assert!(parse(args(&["--root", "x", "--max-input-mb", "abc"])).is_err());
        let Command::Serve(config) = parse(args(&["--root", "x", "--timeout-secs=30"])).unwrap() else {
            panic!("expected serve");
        };
        assert_eq!(config.timeout, Duration::from_secs(30));
    }

    #[test]
    fn rejects_unknown_flags_and_missing_values() {
        assert!(parse(args(&["--root", "x", "--bogus"])).is_err());
        assert!(parse(args(&["--root"])).is_err());
    }

    #[test]
    fn help_and_version_short_circuit() {
        assert_eq!(parse(args(&["--help"])).unwrap(), Command::Help);
        assert_eq!(parse(args(&["--version"])).unwrap(), Command::Version);
    }
}
