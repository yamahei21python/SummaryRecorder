import Foundation

struct TempDirectory {
    let url: URL

    init() {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    func writeWav(named: String, data: Data) -> URL {
        let fileURL = url.appendingPathComponent(named)
        try! data.write(to: fileURL)
        return fileURL
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: url)
    }
}
