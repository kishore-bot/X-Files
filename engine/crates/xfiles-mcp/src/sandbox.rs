//! Path policy. Every file the server reads or writes must resolve under a `--root` folder,
//! after symbolic links are followed. Outputs never replace an input.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::error::{Result, ToolError};

#[derive(Debug, Clone)]
pub struct Sandbox {
    roots: Vec<PathBuf>,
}

impl Sandbox {
    /// Canonicalises the roots. Fails when none is given or one is missing or not a folder.
    pub fn new(roots: &[PathBuf]) -> std::result::Result<Self, String> {
        if roots.is_empty() {
            return Err("at least one --root <folder> is required".to_owned());
        }
        let mut canonical: Vec<PathBuf> = Vec::with_capacity(roots.len());
        for root in roots {
            let resolved = fs::canonicalize(root).map_err(|err| format!("--root {}: {err}", root.display()))?;
            if !resolved.is_dir() {
                return Err(format!("--root {} is not a folder", root.display()));
            }
            if !canonical.contains(&resolved) {
                canonical.push(resolved);
            }
        }
        Ok(Self { roots: canonical })
    }

    pub fn roots(&self) -> &[PathBuf] {
        &self.roots
    }

    /// An existing file the request may read.
    pub fn input(&self, raw: &str) -> Result<PathBuf> {
        let requested = self.anchor(raw)?;
        let resolved = fs::canonicalize(&requested).map_err(|err| not_found_or_io(&err, raw))?;
        self.ensure_inside(&resolved, raw)?;
        if !resolved.is_file() {
            return Err(ToolError::invalid(format!("{raw} is a folder, not a file")));
        }
        Ok(resolved)
    }

    /// An existing folder the request may write into.
    pub fn directory(&self, raw: &str) -> Result<PathBuf> {
        let requested = self.anchor(raw)?;
        let resolved = fs::canonicalize(&requested).map_err(|err| not_found_or_io(&err, raw))?;
        self.ensure_inside(&resolved, raw)?;
        if !resolved.is_dir() {
            return Err(ToolError::invalid(format!("{raw} is not a folder")));
        }
        Ok(resolved)
    }

    /// A new output file named by the request. `allowed_extensions` is empty for any name.
    pub fn output(&self, raw: &str, overwrite: bool, allowed_extensions: &[&str], inputs: &[PathBuf]) -> Result<PathBuf> {
        let requested = self.anchor(raw)?;
        self.output_path(&requested, raw, overwrite, allowed_extensions, inputs)
    }

    /// Like [`Sandbox::output`] for a path already built by the server.
    pub fn output_path(
        &self,
        requested: &Path,
        label: &str,
        overwrite: bool,
        allowed_extensions: &[&str],
        inputs: &[PathBuf],
    ) -> Result<PathBuf> {
        let name = requested
            .file_name()
            .and_then(|name| name.to_str())
            .filter(|name| !name.trim().is_empty() && !name.contains(':'))
            .ok_or_else(|| ToolError::invalid(format!("{label} must end with a plain file name")))?;
        let parent = requested
            .parent()
            .ok_or_else(|| ToolError::invalid(format!("{label} has no folder part")))?;
        let parent = fs::canonicalize(parent).map_err(|err| match err.kind() {
            io::ErrorKind::NotFound => ToolError::Missing(format!("the folder for {label} does not exist")),
            _ => ToolError::Io(format!("cannot use the folder for {label}: {err}")),
        })?;
        self.ensure_inside(&parent, label)?;

        let target = parent.join(name);
        if !allowed_extensions.is_empty() {
            let extension = target
                .extension()
                .and_then(|ext| ext.to_str())
                .map(str::to_ascii_lowercase)
                .unwrap_or_default();
            if !allowed_extensions.contains(&extension.as_str()) {
                let wanted: Vec<String> = allowed_extensions.iter().map(|ext| format!(".{ext}")).collect();
                return Err(ToolError::invalid(format!("{label} must end with {}", wanted.join(" or "))));
            }
        }

        match fs::symlink_metadata(&target) {
            Ok(meta) => {
                if meta.file_type().is_symlink() {
                    return Err(ToolError::Access(format!("{label} is a link; refusing to write through it")));
                }
                if meta.is_dir() {
                    return Err(ToolError::invalid(format!("{label} names an existing folder")));
                }
                if inputs.iter().any(|input| input == &target) {
                    return Err(ToolError::Access(format!("{label} would overwrite an input file")));
                }
                if !overwrite {
                    return Err(ToolError::Exists(format!(
                        "{} already exists; set overwrite to true to replace it",
                        display(&target)
                    )));
                }
            }
            Err(err) if err.kind() == io::ErrorKind::NotFound => {}
            Err(err) => return Err(ToolError::Io(format!("cannot inspect {label}: {err}"))),
        }
        Ok(target)
    }

    /// Joins a relative path onto the first root and keeps absolute paths as they are.
    fn anchor(&self, raw: &str) -> Result<PathBuf> {
        if raw.trim().is_empty() {
            return Err(ToolError::invalid("a path is required"));
        }
        if raw.contains('\0') {
            return Err(ToolError::invalid("the path contains a NUL character"));
        }
        let path = Path::new(raw);
        if path.is_absolute() {
            Ok(path.to_path_buf())
        } else {
            Ok(self.roots[0].join(path))
        }
    }

    fn ensure_inside(&self, resolved: &Path, label: &str) -> Result<()> {
        if self.roots.iter().any(|root| resolved.starts_with(root)) {
            Ok(())
        } else {
            Err(ToolError::Access(format!("{label} is outside the allowed folders")))
        }
    }
}

fn not_found_or_io(err: &io::Error, raw: &str) -> ToolError {
    match err.kind() {
        io::ErrorKind::NotFound => ToolError::Missing(format!("no file or folder at {raw}")),
        _ => ToolError::Io(format!("cannot access {raw}: {err}")),
    }
}

/// A path for people to read: Windows verbatim prefixes (`\\?\`) are removed.
pub fn display(path: &Path) -> String {
    let text = path.display().to_string();
    match text.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with("UNC\\") => rest.to_owned(),
        _ => text,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        _base: tempfile::TempDir,
        root: PathBuf,
        outside: PathBuf,
        sandbox: Sandbox,
    }

    fn fixture() -> Fixture {
        let base = tempfile::tempdir().expect("temp dir");
        let root = base.path().join("root");
        let outside = base.path().join("outside");
        fs::create_dir_all(&root).unwrap();
        fs::create_dir_all(&outside).unwrap();
        fs::write(root.join("in.txt"), b"inside").unwrap();
        fs::write(root.join("existing.pdf"), b"old").unwrap();
        fs::write(outside.join("secret.txt"), b"secret").unwrap();
        let sandbox = Sandbox::new(std::slice::from_ref(&root)).unwrap();
        Fixture { _base: base, root, outside, sandbox }
    }

    #[cfg(any(unix, windows))]
    fn try_symlink(target: &Path, link: &Path) -> bool {
        #[cfg(unix)]
        let made = std::os::unix::fs::symlink(target, link);
        #[cfg(windows)]
        let made = std::os::windows::fs::symlink_file(target, link);
        made.is_ok()
    }

    #[test]
    fn accepts_inputs_inside_the_root_by_relative_and_absolute_path() {
        let fx = fixture();
        assert!(fx.sandbox.input("in.txt").is_ok());
        assert!(fx.sandbox.input(&fx.root.join("in.txt").to_string_lossy()).is_ok());
    }

    #[test]
    fn rejects_inputs_outside_the_root() {
        let fx = fixture();
        let secret = fx.outside.join("secret.txt");
        assert!(matches!(fx.sandbox.input(&secret.to_string_lossy()), Err(ToolError::Access(_))));
        assert!(matches!(fx.sandbox.input("../outside/secret.txt"), Err(ToolError::Access(_))));
    }

    #[test]
    fn rejects_missing_inputs_and_folders() {
        let fx = fixture();
        assert!(matches!(fx.sandbox.input("nope.txt"), Err(ToolError::Missing(_))));
        assert!(matches!(fx.sandbox.input("."), Err(ToolError::Invalid(_))));
        assert!(matches!(fx.sandbox.input("  "), Err(ToolError::Invalid(_))));
    }

    #[test]
    fn rejects_symlinks_that_escape_the_root() {
        let fx = fixture();
        if !try_symlink(&fx.outside.join("secret.txt"), &fx.root.join("escape.txt")) {
            eprintln!("skipping: symbolic links need extra privileges on this host");
            return;
        }
        assert!(matches!(fx.sandbox.input("escape.txt"), Err(ToolError::Access(_))));
    }

    #[test]
    fn output_needs_overwrite_for_existing_files() {
        let fx = fixture();
        assert!(matches!(fx.sandbox.output("existing.pdf", false, &["pdf"], &[]), Err(ToolError::Exists(_))));
        assert!(fx.sandbox.output("existing.pdf", true, &["pdf"], &[]).is_ok());
        assert!(fx.sandbox.output("fresh.pdf", false, &["pdf"], &[]).is_ok());
    }

    #[test]
    fn output_never_replaces_an_input() {
        let fx = fixture();
        let input = fx.sandbox.input("in.txt").unwrap();
        assert!(matches!(fx.sandbox.output("in.txt", true, &[], &[input]), Err(ToolError::Access(_))));
    }

    #[test]
    fn output_checks_extension_location_and_folder() {
        let fx = fixture();
        assert!(matches!(fx.sandbox.output("x.txt", false, &["pdf"], &[]), Err(ToolError::Invalid(_))));
        assert!(matches!(
            fx.sandbox.output(&fx.outside.join("new.pdf").to_string_lossy(), false, &["pdf"], &[]),
            Err(ToolError::Access(_))
        ));
        assert!(matches!(fx.sandbox.output("missing-dir/new.pdf", false, &["pdf"], &[]), Err(ToolError::Missing(_))));
        assert!(matches!(fx.sandbox.output("", false, &["pdf"], &[]), Err(ToolError::Invalid(_))));
    }

    #[test]
    fn output_refuses_to_write_through_a_link() {
        let fx = fixture();
        fs::write(fx.outside.join("secret.pdf"), b"x").unwrap();
        if !try_symlink(&fx.outside.join("secret.pdf"), &fx.root.join("link.pdf")) {
            eprintln!("skipping: symbolic links need extra privileges on this host");
            return;
        }
        assert!(matches!(fx.sandbox.output("link.pdf", true, &["pdf"], &[]), Err(ToolError::Access(_))));
    }

    #[test]
    fn sandbox_needs_existing_folder_roots() {
        assert!(Sandbox::new(&[]).is_err());
        assert!(Sandbox::new(&[PathBuf::from("definitely/not/here/xyz")]).is_err());
    }

    #[test]
    fn display_strips_verbatim_prefix() {
        assert_eq!(display(Path::new(r"\\?\C:\data\a.pdf")), r"C:\data\a.pdf");
        assert_eq!(display(Path::new(r"\\?\UNC\srv\share")), r"\\?\UNC\srv\share");
        assert_eq!(display(Path::new("/tmp/a.pdf")), "/tmp/a.pdf");
    }
}
