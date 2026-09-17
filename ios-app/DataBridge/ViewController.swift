import UIKit
import WebKit
import Photos

class ViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate {

    private var webView: WKWebView!
    private var panel: UIView!
    private var urlField: UITextField!
    private let defaults = UserDefaults.standard

    private let bootstrapJS = """
    (function(){
      let cbId = 0; const cbs = {};
      function call(name, args) {
        return new Promise((resolve) => {
          const id = 'cb' + (++cbId);
          cbs[id] = resolve;
          window.webkit.messageHandlers[name].postMessage({id: id, args: args || {}});
        });
      }
      window.__bridgeReply = function(m) {
        if (m && cbs[m.id]) { cbs[m.id](m.value); delete cbs[m.id]; }
      };
      window.NativeBridge = {
        kind: 'ios',
        getClipboard: () => call('getClipboard'),
        setClipboard: (t) => call('setClipboard', {text: t}),
        saveFile: (name, b64, mime) => call('saveFile', {name: name, b64: b64, mime: mime}),
      };
    })();
    """

    override func loadView() {
        let cfg = WKWebViewConfiguration()
        cfg.userContentController.add(self, name: "getClipboard")
        cfg.userContentController.add(self, name: "setClipboard")
        cfg.userContentController.add(self, name: "saveFile")
        cfg.userContentController.addUserScript(
            WKUserScript(source: bootstrapJS, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView = WKWebView(frame: .zero, configuration: cfg)
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        let gear = UIButton(type: .system)
        gear.setTitle("⚙︎", for: .normal)
        gear.titleLabel?.font = UIFont.systemFont(ofSize: 22)
        gear.alpha = 0.45
        gear.frame = CGRect(x: view.bounds.width - 52, y: 12, width: 44, height: 40)
        gear.autoresizingMask = [.flexibleLeftMargin, .flexibleBottomMargin]
        gear.addTarget(self, action: #selector(showPanel), for: .touchUpInside)
        view.addSubview(gear)

        buildPanel()

        if let u = defaults.string(forKey: "serverURL"), !u.isEmpty {
            webView.load(URLRequest(url: URL(string: u)!))
        } else {
            showPanel()
        }
    }

    /* ---------- 服务器设置面板 ---------- */
    private func buildPanel() {
        panel = UIView(frame: view.bounds)
        panel.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        panel.backgroundColor = UIColor(white: 0.97, alpha: 1)

        let box = UIStackView()
        box.axis = .vertical
        box.spacing = 12
        box.frame = CGRect(x: 24, y: 80, width: view.bounds.width - 48, height: 260)
        box.autoresizingMask = [.flexibleWidth, .flexibleBottomMargin]

        let title = UILabel()
        title.text = "连接服务器"
        title.font = .boldSystemFont(ofSize: 22)

        let tip = UILabel()
        tip.numberOfLines = 0
        tip.font = .systemFont(ofSize: 13)
        tip.textColor = .secondaryLabel
        tip.text = "粘贴 PC 端窗口显示的完整地址（含配对码）。手机/iPad 需与电脑在同一 WiFi。"

        urlField = UITextField()
        urlField.placeholder = "http://192.168.x.x:8322/?t=xxxx"
        urlField.borderStyle = .roundedRect
        urlField.autocorrectionType = .no
        urlField.autocapitalizationType = .none
        urlField.keyboardType = .URL

        let go = UIButton(type: .system)
        go.setTitle("连接", for: .normal)
        go.titleLabel?.font = .boldSystemFont(ofSize: 17)
        go.addTarget(self, action: #selector(connectTapped), for: .touchUpInside)

        box.addArrangedSubview(title)
        box.addArrangedSubview(tip)
        box.addArrangedSubview(urlField)
        box.addArrangedSubview(go)
        panel.addSubview(box)
    }

    @objc private func showPanel() { panel.isHidden = false; view.addSubview(panel) }
    @objc private func hidePanel() { panel.isHidden = true; panel.removeFromSuperview() }

    @objc private func connectTapped() {
        var s = (urlField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return }
        if !s.hasPrefix("http") { s = "http://" + s }
        guard let url = URL(string: s) else { return }
        defaults.set(s, forKey: "serverURL")
        hidePanel()
        webView.load(URLRequest(url: url))
    }

    /* ---------- 原生桥 ---------- */
    func userContentController(
        _ userContentController: WKUserContentController, didReceive message: WKScriptMessage
    ) {
        guard let body = message.body as? [String: Any],
              let id = body["id"] as? String else { return }
        let args = body["args"] as? [String: Any] ?? [:]
        switch message.name {
        case "getClipboard":
            respond(id, UIPasteboard.general.string ?? "")
        case "setClipboard":
            UIPasteboard.general.string = args["text"] as? String ?? ""
            respond(id, true)
        case "saveFile":
            handleSave(args) { ok in self.respond(id, ok) }
        default:
            break
        }
    }

    private func respond(_ id: String, _ value: Any) {
        guard let data = try? JSONSerialization.data(withJSONObject: ["id": id, "value": value]),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript("window.__bridgeReply(\(json))")
    }

    private func handleSave(_ args: [String: Any], done: @escaping (Bool) -> Void) {
        guard let b64 = args["b64"] as? String,
              let name = args["name"] as? String,
              let data = Data(base64Encoded: b64) else { done(false); return }
        let mime = args["mime"] as? String ?? "application/octet-stream"
        let safe = name.replacingOccurrences(of: "/", with: "_")
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(safe)
        do { try data.write(to: tmp) } catch { done(false); return }

        let isImage = mime.hasPrefix("image/")
        let isVideo = mime.hasPrefix("video/")
        if isImage || isVideo {
            PHPhotoLibrary.requestAuthorization { status in
                guard status == .authorized || status == .limited else { DispatchQueue.main.async { done(false) }; return }
                PHPhotoLibrary.shared().performChanges({
                    if isImage {
                        PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: tmp)
                    } else {
                        PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: tmp)
                    }
                }, completionHandler: { ok, _ in
                    DispatchQueue.main.async { done(ok) }
                })
            }
        } else {
            let ac = UIActivityViewController(activityItems: [tmp], applicationActivities: nil)
            // iPad 必须提供弹出锚点，否则崩溃
            ac.popoverPresentationController?.sourceView = view
            ac.popoverPresentationController?.sourceRect = CGRect(
                x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
            present(ac, animated: true)
            done(true)
        }
    }
}
