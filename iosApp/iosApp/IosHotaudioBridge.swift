import Foundation
import ComposeApp

public class IosHotaudioBridge: HotaudioBridge {
    public init() {}

    public func setClearance(cookie: String, userAgent: String) {
        iosApp.setClearance(cookie: cookie, userAgent: userAgent)
    }

    public func clearClearance() {
        iosApp.clearClearance()
    }

    public func setIpFamily(family: String) {
        iosApp.setIpFamily(family: family)
    }

    public func downloadToFile(
        pageUrl: String,
        destinationPath: String,
        initialKeysJson: String?,
        onProgress: @escaping (String, KotlinLong, KotlinLong) -> Void,
        isCancelled: @escaping () -> KotlinBoolean
    ) throws -> HotaudioDownloadResult {
        final class BridgeProgressListener: @unchecked Sendable, ProgressListener {
            let callback: (String, KotlinLong, KotlinLong) -> Void
            init(callback: @escaping (String, KotlinLong, KotlinLong) -> Void) {
                self.callback = callback
            }
            func onProgress(progress: Progress) {
                callback(
                    progress.phase,
                    KotlinLong(value: Int64(progress.loaded)),
                    KotlinLong(value: Int64(progress.total))
                )
            }
        }

        let listener = BridgeProgressListener(callback: onProgress)
        let token = CancellationToken()

        let cancelQueue = DispatchQueue(label: "dev.dawngasm.app.hotaudio.cancel")
        var finished = false
        cancelQueue.async {
            while !finished {
                if isCancelled().boolValue {
                    token.cancel()
                    break
                }
                Thread.sleep(forTimeInterval: 0.1)
            }
        }

        defer {
            finished = true
        }

        let options = DownloadOptions(
            trackId: nil,
            apiBase: "https://hotaudio.net",
            delayMs: 0,
            initialKeysJson: initialKeysJson
        )

        let info = try iosApp.downloadToFile(
            pageUrl: pageUrl,
            path: destinationPath,
            options: options,
            progress: listener,
            cancel: token
        )

        return HotaudioDownloadResult(keysJson: info.keysJson, keyPages: Int32(info.keyPages))
    }
}
