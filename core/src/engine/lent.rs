use std::collections::HashSet;
use std::sync::Arc;

use serde::{Deserialize, Serialize};

use super::{
    About, Catalogue, EmbeddingEngine, Engine, EngineError, Hint, Segment, SpeechEngine, Stop, TextEngine, Window,
};
use crate::audio::SAMPLE_RATE;

// Engines the app runs for the core: the gateway in debug, then Gemini Nano and Apple's. One function crosses for all
// of them, in text, so a new engine touches neither RustCore.kt nor RustCore.swift. tests/fixtures/bridge/engines.json
// holds one offer, a request of each kind, a reply of each kind and the event they lead to, which every app reads.

// Held by the app. Blocks until the engine is done, on the thread of the work, and answers in text: a failure is a
// reply, it never throws back into the core.
pub trait Lender: Send + Sync {
    // The sound, for a transcription, 16-bit little-endian at 16 kHz on one channel. Empty otherwise.
    fn run(&self, engine: &str, request: &str, sound: Vec<u8>) -> String;
}

// Past what a name of an engine needs, a fault of the app.
const LONGEST_NAME: usize = 64;

// A window longer than ten minutes, 19 MB of sound, would be held whole in memory to be lent.
const LONGEST_MS: u32 = 600_000;

// A shorter one, too short for a sentence.
const SHORTEST_MS: u32 = 1_000;

// On average, a token of a model holds three bytes or more of French. Digits and rare words hold fewer, so a text full
// of them may hold more tokens than counted: the minutes keep a margin under the window. An engine of the core counts
// its own.
const BYTES_PER_TOKEN: usize = 3;

const SAMPLES_PER_MS: usize = SAMPLE_RATE / 1_000;

// What the app can run, as it says it. `remote` has no default: an engine that sends what it hears away must say so.
#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum Offered {
    Speech { name: String, remote: bool, longest_ms: u32 },
    Text { name: String, remote: bool, context_tokens: usize, reply_tokens: usize },
    Embedding { name: String, remote: bool },
}

impl Offered {
    fn name(&self) -> &str {
        match self {
            Offered::Speech { name, .. } | Offered::Text { name, .. } | Offered::Embedding { name, .. } => name,
        }
    }

    fn holds(&self) -> bool {
        let named = !self.name().is_empty() && self.name().chars().count() <= LONGEST_NAME;
        named
            && match self {
                Offered::Speech { longest_ms, .. } => (SHORTEST_MS..=LONGEST_MS).contains(longest_ms),
                Offered::Text { context_tokens, reply_tokens, .. } => (1..*context_tokens).contains(reply_tokens),
                Offered::Embedding { .. } => true,
            }
    }
}

// Every engine the app can run now: an offer replaces the one before, and an empty one takes them all back.
pub struct Offer(Vec<Offered>);

impl Offer {
    // None for a text that is not an offer, or an offer the core would have to correct: a fault of the app, which
    // tests show at once, rather than an engine quietly changed.
    pub fn read(text: &str) -> Option<Self> {
        let offered: Vec<Offered> = serde_json::from_str(text).ok()?;
        let mut names = HashSet::new();
        offered.iter().all(|engine| engine.holds() && names.insert(engine.name())).then_some(Self(offered))
    }

    pub fn lend(self, lender: &Arc<dyn Lender>) -> Catalogue {
        let mut speech: Vec<Arc<dyn SpeechEngine>> = Vec::new();
        let mut text: Vec<Arc<dyn TextEngine>> = Vec::new();
        let mut embedding: Vec<Arc<dyn EmbeddingEngine>> = Vec::new();
        for engine in self.0 {
            let lent = |name, remote| Lent { about: About { name, remote }, lender: lender.clone() };
            match engine {
                Offered::Speech { name, remote, longest_ms } => {
                    speech.push(Arc::new(LentSpeech { lent: lent(name, remote), longest_ms }));
                }
                Offered::Text { name, remote, context_tokens, reply_tokens } => {
                    let window = Window { context: context_tokens, reply: reply_tokens };
                    text.push(Arc::new(LentText { lent: lent(name, remote), window }));
                }
                Offered::Embedding { name, remote } => embedding.push(Arc::new(lent(name, remote))),
            }
        }
        Catalogue::new(speech, text, embedding)
    }
}

// What the core asks an engine of the app.
#[derive(Serialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
enum Request<'a> {
    Transcribe { language: &'a str, previous: &'a str },
    Complete { system: &'a str, prompt: &'a str, reply_tokens: usize },
    Embed { texts: &'a [&'a str] },
}

// What it answers. Any reply may be one of the last two.
#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum Reply {
    Heard { segments: Vec<Said> },
    Written { text: String },
    Embedded { vectors: Vec<Vec<f32>> },
    Unavailable,
    Failed,
}

// Wider than a segment: a time past the window is brought back into it, not refused.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Said {
    from_ms: u64,
    to_ms: u64,
    text: String,
}

struct Lent {
    about: About,
    lender: Arc<dyn Lender>,
}

impl Lent {
    // A call the app cannot cut is not made once stopped, and its reply is dropped if stopped meanwhile.
    fn ask(&self, request: &Request, sound: Vec<u8>, stop: &Stop) -> Result<Reply, EngineError> {
        if stop.stopped() {
            return Err(EngineError::Stopped);
        }
        let request = serde_json::to_string(request).map_err(|_| EngineError::Failed)?;
        let reply = self.lender.run(&self.about.name, &request, sound);
        if stop.stopped() {
            return Err(EngineError::Stopped);
        }
        match serde_json::from_str(&reply) {
            Ok(Reply::Unavailable) => Err(EngineError::Unavailable),
            Ok(Reply::Failed) | Err(_) => Err(EngineError::Failed),
            Ok(reply) => Ok(reply),
        }
    }
}

impl Engine for Lent {
    fn about(&self) -> &About {
        &self.about
    }
}

impl EmbeddingEngine for Lent {
    fn embed(&self, texts: &[&str], stop: &Stop) -> Result<Vec<Vec<f32>>, EngineError> {
        if texts.is_empty() {
            return Ok(Vec::new());
        }
        let Reply::Embedded { vectors } = self.ask(&Request::Embed { texts }, Vec::new(), stop)? else {
            return Err(EngineError::Failed);
        };
        // A vector too many, too few or of another size would be compared to the wrong passage: a number past f32
        // reads as infinite.
        let dimension = vectors.first().map_or(0, Vec::len);
        let whole = vectors.len() == texts.len()
            && dimension > 0
            && vectors.iter().all(|vector| vector.len() == dimension && vector.iter().all(|value| value.is_finite()));
        if whole { Ok(vectors) } else { Err(EngineError::Failed) }
    }
}

struct LentSpeech {
    lent: Lent,
    longest_ms: u32,
}

impl Engine for LentSpeech {
    fn about(&self) -> &About {
        &self.lent.about
    }
}

impl SpeechEngine for LentSpeech {
    fn longest_ms(&self) -> u32 {
        self.longest_ms
    }

    fn transcribe(&self, sound: &[i16], hint: &Hint, stop: &Stop) -> Result<Vec<Segment>, EngineError> {
        // The app is never given more than it said it could take: a fault of the core, which the window must fix.
        let longest = self.longest_ms as usize * SAMPLES_PER_MS;
        if sound.len() > longest {
            return Err(EngineError::Failed);
        }
        // Nothing was said, and a model handed no sound may fail: the app is not asked, as for embed.
        if sound.is_empty() {
            return Ok(Vec::new());
        }
        let mut pcm = Vec::with_capacity(sound.len() * 2);
        sound.iter().for_each(|sample| pcm.extend_from_slice(&sample.to_le_bytes()));
        let request = Request::Transcribe { language: hint.language, previous: hint.previous };
        let Reply::Heard { segments } = self.lent.ask(&request, pcm, stop)? else {
            return Err(EngineError::Failed);
        };
        // Bounded by the check above: at most LONGEST_MS.
        let end = (sound.len() / SAMPLES_PER_MS) as u64;
        let mut segments: Vec<Segment> = segments
            .into_iter()
            .map(|said| {
                let from = said.from_ms.min(end);
                Segment { from_ms: from as u32, to_ms: said.to_ms.clamp(from, end) as u32, text: said.text }
            })
            .collect();
        // Stable: two lines said at the same time keep their order.
        segments.sort_by_key(|segment| segment.from_ms);
        Ok(segments)
    }
}

struct LentText {
    lent: Lent,
    window: Window,
}

impl Engine for LentText {
    fn about(&self) -> &About {
        &self.lent.about
    }
}

impl TextEngine for LentText {
    fn window(&self) -> Window {
        self.window
    }

    fn tokens(&self, text: &str) -> usize {
        text.len().div_ceil(BYTES_PER_TOKEN)
    }

    fn complete(&self, system: &str, prompt: &str, stop: &Stop) -> Result<String, EngineError> {
        let request = Request::Complete { system, prompt, reply_tokens: self.window.reply };
        match self.lent.ask(&request, Vec::new(), stop)? {
            Reply::Written { text } => Ok(text),
            _ => Err(EngineError::Failed),
        }
    }
}

#[cfg(test)]
pub mod testing {
    use std::sync::Mutex;

    use super::*;

    // The side of an app: it answers each call with the next reply it was given, and keeps what it was asked.
    #[derive(Default)]
    pub struct App {
        pub replies: Mutex<Vec<String>>,
        pub asked: Mutex<Vec<(String, String, Vec<u8>)>>,
        // Raised during the call, as a deleted meeting would.
        pub stop: Option<Arc<Stop>>,
    }

    impl App {
        pub fn answering(replies: &[&str]) -> Arc<Self> {
            Arc::new(Self {
                replies: Mutex::new(replies.iter().rev().map(|&reply| reply.into()).collect()),
                ..Self::default()
            })
        }

        pub fn asked(&self) -> Vec<(String, String, Vec<u8>)> {
            self.asked.lock().unwrap().clone()
        }
    }

    impl Lender for App {
        fn run(&self, engine: &str, request: &str, sound: Vec<u8>) -> String {
            self.asked.lock().unwrap().push((engine.into(), request.into(), sound));
            if let Some(stop) = &self.stop {
                stop.stop();
            }
            self.replies.lock().unwrap().pop().unwrap_or_else(|| "{\"type\":\"failed\"}".into())
        }
    }
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use super::testing::App;
    use super::*;
    use crate::testing::each_damaged;

    const ENGINES: &str = include_str!("../../tests/fixtures/bridge/engines.json");

    fn fixture() -> Value {
        serde_json::from_str(ENGINES).unwrap()
    }

    fn lent(app: &Arc<App>, offer: Value) -> Catalogue {
        let lender: Arc<dyn Lender> = app.clone();
        Offer::read(&offer.to_string()).unwrap().lend(&lender)
    }

    // One engine of each kind, on the device.
    fn one_of_each(app: &Arc<App>) -> Catalogue {
        lent(
            app,
            json!([
                {"kind": "speech", "name": "s", "remote": false, "longestMs": 30_000},
                {"kind": "text", "name": "t", "remote": false, "contextTokens": 4_096, "replyTokens": 1_024},
                {"kind": "embedding", "name": "e", "remote": false},
            ]),
        )
    }

    fn hint() -> Hint<'static> {
        Hint { language: "fr", previous: "Bonjour à tous." }
    }

    fn heard(catalogue: &Catalogue, sound: &[i16]) -> Result<Vec<Segment>, EngineError> {
        catalogue.speech().unwrap().transcribe(sound, &hint(), &Stop::default())
    }

    fn segment(from_ms: u32, to_ms: u32, text: &str) -> Segment {
        Segment { from_ms, to_ms, text: text.into() }
    }

    #[test]
    fn the_engines_of_the_example_offer_are_chosen_as_the_apps_read_it() {
        let fixture = fixture();
        let catalogue = lent(&App::answering(&[]), fixture["offer"].clone());
        let mut told = fixture["events"][1].clone();
        told.as_object_mut().unwrap().remove("type");
        assert_eq!(serde_json::to_value(&catalogue).unwrap(), told);
        let speech = catalogue.speech().unwrap();
        assert_eq!((speech.about().name.as_str(), speech.longest_ms()), ("speech-analyzer", 600_000));
        assert_eq!(catalogue.text().unwrap().window(), Window { context: 4_096, reply: 1_024 });
    }

    #[test]
    fn each_kind_is_asked_as_the_apps_read_it_and_its_reply_read_as_they_write_it() {
        let fixture = fixture();
        let replies: Vec<String> = fixture["replies"].as_array().unwrap().iter().map(Value::to_string).collect();
        let app = App::answering(&replies[..3].iter().map(String::as_str).collect::<Vec<_>>());
        let catalogue = one_of_each(&app);
        let stop = Stop::default();
        // Two seconds, negative samples among them: a wrong byte order would show.
        let sound: Vec<i16> = (0..32_000).map(|sample: i32| ((sample % 700 - 350) * 90) as i16).collect();
        let segments = catalogue.speech().unwrap().transcribe(&sound, &hint(), &stop).unwrap();
        assert_eq!(segments, vec![segment(0, 1_200, "On commence."), segment(1_200, 1_900, "La démo d'abord.")]);
        let written = catalogue.text().unwrap().complete("Tu écris des comptes rendus.", "Bonjour à tous.", &stop);
        assert_eq!(written.unwrap(), "SUMMARY: Point sur la démo.");
        let vectors = catalogue.embedding().unwrap().embed(&["la démo", "le budget"], &stop).unwrap();
        assert_eq!(vectors, vec![vec![0.6, 0.8], vec![1.0, 0.0]]);

        let asked = app.asked();
        let requests: Vec<Value> = asked.iter().map(|(_, request, _)| serde_json::from_str(request).unwrap()).collect();
        assert_eq!(Value::Array(requests), fixture["requests"]);
        assert_eq!(asked.iter().map(|(engine, _, _)| engine.as_str()).collect::<Vec<_>>(), ["s", "t", "e"]);
        let pcm: Vec<i16> = asked[0].2.chunks(2).map(|bytes| i16::from_le_bytes([bytes[0], bytes[1]])).collect();
        assert_eq!(pcm, sound);
        assert!(asked[1].2.is_empty() && asked[2].2.is_empty());
    }

    #[test]
    fn any_kind_may_be_unavailable_or_fail() {
        let fixture = fixture();
        let (unavailable, failed) = (fixture["replies"][3].to_string(), fixture["replies"][4].to_string());
        for (reply, error) in [(unavailable, EngineError::Unavailable), (failed, EngineError::Failed)] {
            let app = App::answering(&[&reply, &reply, &reply]);
            let catalogue = one_of_each(&app);
            let stop = Stop::default();
            assert_eq!(heard(&catalogue, &[0; 16_000]).err(), Some(error));
            assert_eq!(catalogue.text().unwrap().complete("", "", &stop).err(), Some(error));
            assert_eq!(catalogue.embedding().unwrap().embed(&["a"], &stop).err(), Some(error));
        }
    }

    #[test]
    fn a_reply_of_another_kind_or_unread_fails() {
        let heard_reply = "{\"type\":\"heard\",\"segments\":[]}";
        let written = "{\"type\":\"written\",\"text\":\"a\"}";
        let unread =
            ["", "{}", "null", "{\"type\":\"heard\"}", "{\"type\":\"written\",\"text\":1}", "{\"type\":\"later\"}"];
        for wrong in unread.iter().chain([&written]) {
            assert_eq!(heard(&one_of_each(&App::answering(&[wrong])), &[0; 16]).err(), Some(EngineError::Failed));
        }
        for wrong in unread.iter().chain([&heard_reply]) {
            let catalogue = one_of_each(&App::answering(&[wrong]));
            assert_eq!(catalogue.text().unwrap().complete("", "", &Stop::default()).err(), Some(EngineError::Failed));
            let catalogue = one_of_each(&App::answering(&[wrong]));
            assert_eq!(catalogue.embedding().unwrap().embed(&["a"], &Stop::default()).err(), Some(EngineError::Failed));
        }
    }

    #[test]
    fn segments_are_brought_back_into_their_window_and_put_in_order() {
        let reply = json!({"type": "heard", "segments": [
            {"fromMs": 900, "toMs": 1_400, "text": "b"},
            {"fromMs": 0, "toMs": 500, "text": "a"},
            {"fromMs": 900, "toMs": 700, "text": "c"},
            {"fromMs": 1_800, "toMs": 99_999_999_999u64, "text": "d"},
            {"fromMs": 99_999_999_999u64, "toMs": 0, "text": "e"},
        ]});
        let catalogue = one_of_each(&App::answering(&[&reply.to_string()]));
        // Two seconds of sound.
        let segments = heard(&catalogue, &[0; 32_000]).unwrap();
        let expected = vec![
            segment(0, 500, "a"),
            segment(900, 1_400, "b"),
            segment(900, 900, "c"),
            segment(1_800, 2_000, "d"),
            segment(2_000, 2_000, "e"),
        ];
        assert_eq!(segments, expected);
    }

    #[test]
    fn a_sound_longer_than_the_engine_takes_is_never_lent() {
        let app = App::answering(&["{\"type\":\"heard\",\"segments\":[]}"]);
        let catalogue = one_of_each(&app);
        assert_eq!(heard(&catalogue, &vec![0; 30_000 * 16 + 1]).err(), Some(EngineError::Failed));
        assert!(app.asked().is_empty());
        assert_eq!(heard(&catalogue, &vec![0; 30_000 * 16]).unwrap(), vec![]);
    }

    #[test]
    fn an_empty_sound_is_never_lent() {
        let app = App::answering(&["{\"type\":\"heard\",\"segments\":[{\"fromMs\":0,\"toMs\":0,\"text\":\"a\"}]}"]);
        assert_eq!(heard(&one_of_each(&app), &[]), Ok(vec![]));
        assert!(app.asked().is_empty());
    }

    #[test]
    fn vectors_that_do_not_match_their_texts_fail() {
        let two = ["a", "b"];
        for vectors in [
            json!([[1.0]]),
            json!([[1.0], [1.0], [1.0]]),
            json!([[1.0, 0.0], [1.0]]),
            json!([[], []]),
            json!([[1.0], [1e39]]),
            json!([[1.0], [-1e39]]),
            json!([[1.0], ["x"]]),
        ] {
            let reply = json!({"type": "embedded", "vectors": vectors}).to_string();
            let catalogue = one_of_each(&App::answering(&[&reply]));
            let embedded = catalogue.embedding().unwrap().embed(&two, &Stop::default());
            assert_eq!(embedded.err(), Some(EngineError::Failed), "{vectors}");
        }
        let app = App::answering(&[]);
        assert_eq!(one_of_each(&app).embedding().unwrap().embed(&[], &Stop::default()), Ok(vec![]));
        assert!(app.asked().is_empty());
    }

    #[test]
    fn a_stopped_call_is_not_made_and_a_call_stopped_meanwhile_is_dropped() {
        let written = "{\"type\":\"written\",\"text\":\"a\"}";
        let app = App::answering(&[written]);
        let stop = Stop::default();
        stop.stop();
        let catalogue = one_of_each(&app);
        assert_eq!(heard_with(&catalogue, &stop), Err(EngineError::Stopped));
        assert_eq!(catalogue.text().unwrap().complete("", "", &stop), Err(EngineError::Stopped));
        assert_eq!(catalogue.embedding().unwrap().embed(&["a"], &stop), Err(EngineError::Stopped));
        assert!(app.asked().is_empty());

        let stop = Arc::new(Stop::default());
        let app = Arc::new(App { stop: Some(stop.clone()), ..App::default() });
        app.replies.lock().unwrap().push(written.into());
        let catalogue = one_of_each(&app);
        assert_eq!(catalogue.text().unwrap().complete("", "", &stop), Err(EngineError::Stopped));
        assert_eq!(app.asked().len(), 1);
    }

    fn heard_with(catalogue: &Catalogue, stop: &Stop) -> Result<Vec<Segment>, EngineError> {
        catalogue.speech().unwrap().transcribe(&[0; 16], &hint(), stop)
    }

    #[test]
    fn a_text_is_counted_three_bytes_a_token() {
        let catalogue = one_of_each(&App::answering(&[]));
        let text = catalogue.text().unwrap();
        assert_eq!(text.tokens(""), 0);
        assert_eq!(text.tokens("a"), 1);
        assert_eq!(text.tokens("abc"), 1);
        assert_eq!(text.tokens("abcd"), 2);
        // Two bytes a letter: the estimate counts bytes, not letters.
        assert_eq!(text.tokens("éééééé"), 4);
    }

    #[test]
    fn an_offer_the_core_would_have_to_correct_is_refused_whole() {
        let speech =
            |name: &str, longest: u64| json!({"kind": "speech", "name": name, "remote": false, "longestMs": longest});
        let text = |context: u64, reply: u64| json!({"kind": "text", "name": "t", "remote": false, "contextTokens": context, "replyTokens": reply});
        let refused = [
            json!({}),
            json!([{}]),
            json!([{"kind": "vision", "name": "v", "remote": false}]),
            json!([{"kind": "embedding", "name": "e"}]),
            json!([{"kind": "embedding", "name": "e", "remote": "no"}]),
            json!([speech("", 30_000)]),
            json!([speech(&"x".repeat(65), 30_000)]),
            json!([speech("s", 999)]),
            json!([speech("s", 600_001)]),
            json!([speech("s", 30_000), speech("s", 60_000)]),
            json!([speech("same", 30_000), {"kind": "embedding", "name": "same", "remote": true}]),
            json!([text(4_096, 0)]),
            json!([text(1_024, 1_024)]),
            json!([text(0, 0)]),
        ];
        for offer in refused {
            assert!(Offer::read(&offer.to_string()).is_none(), "{offer}");
        }
        let held = [
            json!([]),
            json!([speech(&"é".repeat(64), 1_000), speech("s", 600_000), text(2, 1)]),
            json!([{"kind": "embedding", "name": "e", "remote": true, "dimension": 384}]),
        ];
        for offer in held {
            assert!(Offer::read(&offer.to_string()).is_some(), "{offer}");
        }
    }

    #[test]
    fn a_damaged_offer_is_refused_or_read() {
        let offer = fixture()["offer"].to_string();
        each_damaged(&[offer.as_bytes()], 2_000, |text| {
            let _ = Offer::read(&String::from_utf8_lossy(text));
        });
    }

    #[test]
    fn a_damaged_reply_fails_or_is_read() {
        let replies: Vec<String> = fixture()["replies"].as_array().unwrap().iter().map(Value::to_string).collect();
        let good: Vec<&[u8]> = replies.iter().map(String::as_bytes).collect();
        let stop = Stop::default();
        each_damaged(&good, 2_000, |reply| {
            let reply = String::from_utf8_lossy(reply);
            let catalogue = one_of_each(&App::answering(&[&reply, &reply, &reply]));
            let _ = catalogue.speech().unwrap().transcribe(&[0; 1_600], &hint(), &stop);
            let _ = catalogue.text().unwrap().complete("", "", &stop);
            let _ = catalogue.embedding().unwrap().embed(&["a", "b"], &stop);
        });
    }
}
