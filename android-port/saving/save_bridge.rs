//! A save is complete only after Android's document provider accepts the staged file.
use std::io;
use std::sync::{Arc, RwLock};

type Commit = dyn Fn(&str) -> Result<String, String> + Send + Sync;
static COMMIT: RwLock<Option<Arc<Commit>>> = RwLock::new(None);

/// Replaced when Android creates a new activity, so the callback never retains an old bridge.
pub fn install(handler: Arc<Commit>) {
    *COMMIT.write().unwrap_or_else(|e| e.into_inner()) = Some(handler);
}

pub fn commit(path: &str) -> io::Result<()> {
    let handler = COMMIT.read().unwrap_or_else(|e| e.into_inner()).clone();
    let handler = handler
        .ok_or_else(|| io::Error::other("Android document saving is not ready. Try again."))?;
    match handler(path) {
        Ok(status) if status == "OK" || status == "NONE" => Ok(()),
        Ok(status) => Err(io::Error::other(format!(
            "Android could not save the original document: {status}. Your changes remain open; retry or use Save As."
        ))),
        Err(error) => Err(io::Error::other(format!(
            "Android could not save the original document: {error}. Your changes remain open; retry or use Save As."
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[test]
    fn saved_requires_provider_success_and_a_failed_save_can_be_retried() {
        assert!(commit("before-activity").is_err());
        let calls = Arc::new(Mutex::new(Vec::new()));
        let recorded = calls.clone();
        install(Arc::new(move |path| {
            recorded.lock().unwrap().push(path.to_owned());
            match path {
                "private.pdf" => Ok("NONE".into()),
                "writable.pdf" => Ok("OK".into()),
                "denied.pdf" => Ok("ERROR: permission denied".into()),
                "broken.pdf" => Err("provider unavailable".into()),
                _ => Ok(String::new()),
            }
        }));
        assert!(commit("private.pdf").is_ok());
        assert!(commit("writable.pdf").is_ok());
        assert!(
            commit("denied.pdf")
                .unwrap_err()
                .to_string()
                .contains("permission denied")
        );
        assert!(
            commit("broken.pdf")
                .unwrap_err()
                .to_string()
                .contains("provider unavailable")
        );
        assert!(commit("unknown.pdf").is_err());
        assert_eq!(calls.lock().unwrap().len(), 5);
        // Reopening an activity or retrying with a recovered provider replaces the old callback.
        install(Arc::new(|_| Ok("OK".into())));
        assert!(commit("denied.pdf").is_ok());
        assert_eq!(calls.lock().unwrap().len(), 5);
    }
}
