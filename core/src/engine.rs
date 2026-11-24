use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use serde::{Serialize, Serializer};

pub mod lent;

// What the core asks of a model, and nothing of how or where it runs: compiled into the core, lent by the app (the
// gateway, Gemini Nano, Apple's), or trained by the team. The core picks one per kind of work. docs/adr/0010-moteurs.md.

#[derive(Serialize)]
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct About {
    // Unique among the engines the core knows, and changed with the model (gemini-nano-2): vectors made under another
    // name are made again, never compared with those of another model.
    pub name: String,
    // What it is given leaves the device. Last choice, whatever the order it was offered in (product rule 1).
    pub remote: bool,
}

pub trait Engine: Send + Sync {
    fn about(&self) -> &About;
}

// What `longest_ms` may say. Shorter than a second holds no sentence. Longer than ten minutes, 19 MB of sound, would be
// held whole in memory to be heard.
pub const SHORTEST_MS: u32 = 1_000;
pub const LONGEST_MS: u32 = 600_000;

pub trait SpeechEngine: Engine {
    // The longest sound one call takes, from SHORTEST_MS to LONGEST_MS: Whisper hears 30 s at once, another model more.
    fn longest_ms(&self) -> u32;

    // The sound of one window, 16 kHz on one channel; times from the start of that window.
    fn transcribe(&self, sound: &[i16], hint: &Hint, stop: &Stop) -> Result<Vec<Segment>, EngineError>;
}

// What may keep a model on track. An engine is free to ignore it.
pub struct Hint<'a> {
    pub language: &'a str,
    // The end of what was heard before this window.
    pub previous: &'a str,
}

// Carries what was said: no Debug outside the tests, as for Meeting.
#[cfg_attr(test, derive(Debug, PartialEq))]
pub struct Segment {
    pub from_ms: u32,
    pub to_ms: u32,
    pub text: String,
}

// What a text engine leaves a prompt once its reply is set aside, at least: below that, the instructions of the minutes
// and a few lines of a transcript do not fit one call.
pub const SHORTEST_PROMPT: usize = 1_024;

pub trait TextEngine: Engine {
    fn window(&self) -> Window;

    // Exact for an engine that holds its tokenizer, an average otherwise: what is cut to fit a window keeps a margin.
    fn tokens(&self, text: &str) -> usize;

    fn complete(&self, system: &str, prompt: &str, stop: &Stop) -> Result<String, EngineError>;
}

// In tokens: what a call may hold, the reply included, and the longest reply.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Window {
    pub context: usize,
    pub reply: usize,
}

pub trait EmbeddingEngine: Engine {
    // One vector per text, in the same order and of one dimension.
    fn embed(&self, texts: &[&str], stop: &Stop) -> Result<Vec<Vec<f32>>, EngineError>;
}

// Carries nothing that was said: it may end up in a log (product rule 1).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum EngineError {
    // Not now: a model still downloading, a gateway out of reach. The work waits for the app to offer its engines
    // again, and nothing is marked.
    Unavailable,
    // This input failed: the work moves on without it.
    Failed,
    // Asked to stop, by a deleted meeting or a closing app.
    Stopped,
}

// Raised by whoever holds the work, read by the engine as often as it can: between two tokens, two windows, or before
// and after a call it cannot cut.
#[derive(Default)]
pub struct Stop(AtomicBool);

impl Stop {
    // Relaxed: the flag publishes no other value.
    pub fn stop(&self) {
        self.0.store(true, Ordering::Relaxed);
    }

    pub fn stopped(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }
}

// The catalogue in force, replaced whole by each offer and read from any thread: by the session, and by the pipeline
// at each step, which keeps the engines it started with.
pub type Engines = Arc<Mutex<Arc<Catalogue>>>;

// The engines the core knows, by kind, the preferred first: the one a work takes. Never changed once made: a new
// offer makes another, and a call already under way keeps the engine it started with.
#[derive(Default)]
pub struct Catalogue {
    speech: Vec<Arc<dyn SpeechEngine>>,
    text: Vec<Arc<dyn TextEngine>>,
    embedding: Vec<Arc<dyn EmbeddingEngine>>,
}

impl Catalogue {
    // In the order given, those of the device first.
    pub fn new(
        mut speech: Vec<Arc<dyn SpeechEngine>>,
        mut text: Vec<Arc<dyn TextEngine>>,
        mut embedding: Vec<Arc<dyn EmbeddingEngine>>,
    ) -> Self {
        // Stable: among the engines of the device, and among the remote ones, the order given stands.
        speech.sort_by_key(|engine| engine.about().remote);
        text.sort_by_key(|engine| engine.about().remote);
        embedding.sort_by_key(|engine| engine.about().remote);
        Self { speech, text, embedding }
    }

    pub fn speech(&self) -> Option<&dyn SpeechEngine> {
        self.speech.first().map(|engine| &**engine)
    }

    pub fn text(&self) -> Option<&dyn TextEngine> {
        self.text.first().map(|engine| &**engine)
    }

    pub fn embedding(&self) -> Option<&dyn EmbeddingEngine> {
        self.embedding.first().map(|engine| &**engine)
    }
}

// As an app reads it: each kind, the chosen engine first.
impl Serialize for Catalogue {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        #[derive(Serialize)]
        struct Told<'a> {
            speech: Vec<&'a About>,
            text: Vec<&'a About>,
            embedding: Vec<&'a About>,
        }
        Told {
            speech: self.speech.iter().map(|engine| engine.about()).collect(),
            text: self.text.iter().map(|engine| engine.about()).collect(),
            embedding: self.embedding.iter().map(|engine| engine.about()).collect(),
        }
        .serialize(serializer)
    }
}

#[cfg(test)]
pub mod testing {
    use super::*;

    // Hears every window as one sentence, says the same text, and gives every text the same vector.
    pub struct Fake(pub About);

    pub fn fake(name: &str, remote: bool) -> Arc<Fake> {
        Arc::new(Fake(About { name: name.into(), remote }))
    }

    impl Engine for Fake {
        fn about(&self) -> &About {
            &self.0
        }
    }

    impl SpeechEngine for Fake {
        fn longest_ms(&self) -> u32 {
            30_000
        }

        fn transcribe(&self, sound: &[i16], _: &Hint, _: &Stop) -> Result<Vec<Segment>, EngineError> {
            let to_ms = (sound.len() / 16) as u32;
            Ok(vec![Segment { from_ms: 0, to_ms, text: self.0.name.clone() }])
        }
    }

    impl TextEngine for Fake {
        fn window(&self) -> Window {
            Window { context: 4_096, reply: 1_024 }
        }

        fn tokens(&self, text: &str) -> usize {
            text.len()
        }

        fn complete(&self, _: &str, _: &str, _: &Stop) -> Result<String, EngineError> {
            Ok(self.0.name.clone())
        }
    }

    impl EmbeddingEngine for Fake {
        fn embed(&self, texts: &[&str], _: &Stop) -> Result<Vec<Vec<f32>>, EngineError> {
            Ok(texts.iter().map(|_| vec![1.0]).collect())
        }
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::testing::fake;
    use super::*;

    fn names(catalogue: &Catalogue) -> serde_json::Value {
        let names = |engines: &serde_json::Value| -> Vec<String> {
            engines.as_array().unwrap().iter().map(|about| about["name"].as_str().unwrap().to_owned()).collect()
        };
        let told = serde_json::to_value(catalogue).unwrap();
        json!({"speech": names(&told["speech"]), "text": names(&told["text"]), "embedding": names(&told["embedding"])})
    }

    #[test]
    fn the_engines_of_the_device_come_before_the_remote_ones_then_the_order_given() {
        // The engines offered, each with whether it is remote, then the order the core keeps.
        type Case = (&'static [(&'static str, bool)], &'static [&'static str]);
        let cases: &[Case] = &[
            (&[], &[]),
            (&[("a", false)], &["a"]),
            (&[("a", true)], &["a"]),
            (&[("gateway", true), ("device", false)], &["device", "gateway"]),
            (&[("a", false), ("b", false)], &["a", "b"]),
            (&[("b", false), ("a", false)], &["b", "a"]),
            (&[("r1", true), ("d1", false), ("r2", true), ("d2", false)], &["d1", "d2", "r1", "r2"]),
        ];
        for (offered, chosen) in cases {
            let engines: Vec<_> = offered.iter().map(|(name, remote)| fake(name, *remote)).collect();
            let catalogue = Catalogue::new(
                engines.iter().map(|engine| engine.clone() as Arc<dyn SpeechEngine>).collect(),
                engines.iter().map(|engine| engine.clone() as Arc<dyn TextEngine>).collect(),
                engines.iter().map(|engine| engine.clone() as Arc<dyn EmbeddingEngine>).collect(),
            );
            assert_eq!(
                names(&catalogue),
                json!({"speech": chosen, "text": chosen, "embedding": chosen}),
                "{offered:?}"
            );
            let first = chosen.first().copied();
            assert_eq!(catalogue.speech().map(|engine| engine.about().name.as_str()), first);
            assert_eq!(catalogue.text().map(|engine| engine.about().name.as_str()), first);
            assert_eq!(catalogue.embedding().map(|engine| engine.about().name.as_str()), first);
        }
    }

    #[test]
    fn each_kind_is_chosen_on_its_own() {
        let catalogue = Catalogue::new(vec![fake("gateway", true)], vec![fake("nano", false)], vec![]);
        assert_eq!(catalogue.speech().unwrap().about(), &About { name: "gateway".into(), remote: true });
        assert_eq!(catalogue.text().unwrap().about().name, "nano");
        assert!(catalogue.embedding().is_none());
        let told = serde_json::to_value(&catalogue).unwrap();
        let expected = json!({
            "speech": [{"name": "gateway", "remote": true}],
            "text": [{"name": "nano", "remote": false}],
            "embedding": [],
        });
        assert_eq!(told, expected);
    }

    #[test]
    fn a_stop_is_seen_from_another_thread() {
        let stop = Arc::new(Stop::default());
        assert!(!stop.stopped());
        let raised = stop.clone();
        std::thread::spawn(move || raised.stop()).join().unwrap();
        assert!(stop.stopped());
    }

    #[test]
    fn an_engine_is_shared_by_the_threads_of_the_core() {
        fn shared<T: Send + Sync + ?Sized>() {}
        shared::<dyn SpeechEngine>();
        shared::<dyn TextEngine>();
        shared::<dyn EmbeddingEngine>();
        shared::<Catalogue>();
        shared::<Stop>();
    }
}
