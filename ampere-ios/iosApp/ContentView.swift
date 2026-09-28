import UIKit
import SwiftUI
import shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // FoundationModels ships with iOS 26. On anything older there is no on-device
        // model to bind, and the Kotlin side shows that instead of an engine.
        if #available(iOS 26.0, *) {
            return Main_iosKt.mainViewController(
                localInferenceEngine: FoundationModelsLocalInferenceEngine()
            )
        }
        return Main_iosKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
                .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
    }
}
