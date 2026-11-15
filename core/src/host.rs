use std::sync::Arc;
use std::thread;

// Held by the app, called by the core: the system's AI will reach the core through an interface like this one.
#[uniffi::export(with_foreign)]
pub trait HostProbe: Send + Sync {
    fn answer(&self, question: String) -> String;
}

// Asked from a thread the core starts itself, as it will be once the core records and transcribes on its own.
#[uniffi::export]
pub fn ask_host(probe: Arc<dyn HostProbe>, question: String) -> String {
    thread::spawn(move || probe.answer(question)).join().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Shouting;

    impl HostProbe for Shouting {
        fn answer(&self, question: String) -> String {
            question.to_uppercase()
        }
    }

    #[test]
    fn the_answer_of_the_host_comes_back() {
        assert_eq!(ask_host(Arc::new(Shouting), "ping".into()), "PING");
    }
}
