import AVFoundation
import Foundation
import NotitiaKit
import Observation

/// Lecture d'un enregistrement chiffré, synchronisée avec la transcription.
///
/// L'audio est déchiffré dans un fichier temporaire supprimé dès l'arrêt de la lecture.
@Observable
final class AudioPlaybackController: NSObject, AVAudioPlayerDelegate {
    private(set) var recordingID: UUID?
    private(set) var currentTime: TimeInterval = 0
    private(set) var isPlaying = false
    var errorMessage: String?

    @ObservationIgnored private let vault: AudioVault
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var temporaryFile: URL?
    @ObservationIgnored private var timer: Timer?

    init(vault: AudioVault) {
        self.vault = vault
    }

    func play(_ id: UUID, from time: TimeInterval? = nil) {
        if recordingID != id { stop() }
        do {
            if player == nil {
                let file = try vault.decryptToTemporaryFile(id)
                temporaryFile = file
                let player = try AVAudioPlayer(contentsOf: file)
                player.delegate = self
                player.prepareToPlay()
                self.player = player
                recordingID = id
            }
            if let time { player?.currentTime = max(0, time) }
            player?.play()
            isPlaying = true
            startTimer()
        } catch {
            errorMessage = error.localizedDescription
            stop()
        }
    }

    func pause() {
        player?.pause()
        isPlaying = false
        timer?.invalidate()
    }

    func toggle(_ id: UUID) {
        if recordingID == id, isPlaying { pause() } else { play(id) }
    }

    func stop() {
        player?.stop()
        player = nil
        timer?.invalidate()
        timer = nil
        isPlaying = false
        currentTime = 0
        recordingID = nil
        if let file = temporaryFile {
            try? FileManager.default.removeItem(at: file)
            temporaryFile = nil
        }
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.currentTime = self?.player?.currentTime ?? 0
            }
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}
