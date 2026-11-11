import SwiftUI
import AVFoundation
import MediaPlayer
import ComposeApp

final class IosNowPlayingManager: NSObject, IosAudioPlayerObserver, @unchecked Sendable {
    static let shared = IosNowPlayingManager()

    private var currentTrack: Track?
    private var isPlaying: Bool = false
    private var positionMs: Int64 = 0
    private var durationMs: Int64 = 0

    func setup() {
        IosAudioIntegration.shared.observer = self

        // 1. Audio Session
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            print("IosNowPlayingManager: Failed to configure AVAudioSession: \(error)")
        }

        // 2. Remote Command Center
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { _ in
            IosAudioIntegration.shared.activePlayer?.resume()
            return .success
        }

        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { _ in
            IosAudioIntegration.shared.activePlayer?.pause()
            return .success
        }

        commandCenter.togglePlayPauseCommand.isEnabled = true
        commandCenter.togglePlayPauseCommand.addTarget { _ in
            IosAudioIntegration.shared.activePlayer?.togglePlayPause()
            return .success
        }

        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.nextTrackCommand.addTarget { _ in
            IosAudioIntegration.shared.activePlayer?.nextTrack()
            return .success
        }

        commandCenter.previousTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.addTarget { _ in
            IosAudioIntegration.shared.activePlayer?.previousTrack()
            return .success
        }

        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.addTarget { event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            let targetMs = Int64(posEvent.positionTime * 1000.0)
            IosAudioIntegration.shared.activePlayer?.seekTo(positionMs: targetMs)
            return .success
        }

        // 3. Audio Interruptions (e.g. phone call, Siri)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )

        // 4. Audio Route Change (e.g. headphones unplugged)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        if type == .began {
            IosAudioIntegration.shared.activePlayer?.pause()
        } else if type == .ended {
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    IosAudioIntegration.shared.activePlayer?.resume()
                }
            }
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

        if reason == .oldDeviceUnavailable {
            IosAudioIntegration.shared.activePlayer?.pause()
        }
    }

    // MARK: - IosAudioPlayerObserver

    func onTrackChanged(track: Track?) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.currentTrack = track
            self.updateNowPlayingInfo()
        }
    }

    func onPlaybackStateChanged(isPlaying: Bool, positionMs: Int64, durationMs: Int64) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.isPlaying = isPlaying
            self.positionMs = positionMs
            self.durationMs = durationMs
            self.updateNowPlayingInfo()
        }
    }

    private func updateNowPlayingInfo() {
        guard let track = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.author,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(positionMs) / 1000.0,
        ]

        if durationMs > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000.0
        }

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

@main
struct iOSApp: App {
    init() {
        HotaudioBridgeProvider.shared.bridge = IosHotaudioBridge()
        IosNowPlayingManager.shared.setup()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
