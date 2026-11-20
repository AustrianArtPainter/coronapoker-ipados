import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        HostProbe.shared.start()
        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Host validation", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = ProbeViewController()
        window.makeKeyAndVisible()
        self.window = window
    }

    func sceneDidEnterBackground(_ scene: UIScene) { HostProbe.shared.setBackground(true) }
    func sceneWillEnterForeground(_ scene: UIScene) { HostProbe.shared.setBackground(false) }
}

final class ProbeViewController: UIViewController {
    private let status = UILabel()
    private let musicButton = UIButton(type: .system)
    private var refresh: Timer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.03, green: 0.13, blue: 0.10, alpha: 1)
        let title = UILabel()
        title.text = "CoronaPoker · iPad 后台验证"
        title.font = .boldSystemFont(ofSize: 27)
        title.textColor = .white
        title.numberOfLines = 0
        let explanation = UILabel()
        explanation.text = "这是平台验证程序。它持续播放游戏音乐，并检查计时器、TCP、SQLite 和加密操作。\n完整游戏尚在移植中。锁屏前请保持音乐播放。"
        explanation.numberOfLines = 0
        explanation.textColor = .white
        explanation.font = .systemFont(ofSize: 17)
        status.numberOfLines = 0
        status.font = .monospacedSystemFont(ofSize: 16, weight: .regular)
        status.textColor = UIColor(red: 0.72, green: 0.98, blue: 0.80, alpha: 1)
        musicButton.titleLabel?.font = .boldSystemFont(ofSize: 18)
        musicButton.addTarget(self, action: #selector(toggleMusic), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, explanation, status, musicButton])
        stack.axis = .vertical
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -28),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor)
        ])
        refresh = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.update() }
        update()
    }

    deinit { refresh?.invalidate() }

    @objc private func toggleMusic() { HostProbe.shared.toggleMusic(); update() }

    private func update() {
        HostProbe.shared.status { [weak self] snapshot in
            let keys = ["runID", "addresses", "port", "ticks", "backgroundTicks", "databaseCommits",
                        "signatureChecks", "connections", "maximumTickGapSeconds", "musicPlaying", "error"]
            self?.status.text = keys.map { "\($0): \(snapshot[$0] ?? "—")" }.joined(separator: "\n")
            self?.musicButton.setTitle(snapshot["musicPlaying"] as? Bool == true ? "暂停音乐（后台执行可能中断）" : "继续播放音乐", for: .normal)
        }
    }
}
