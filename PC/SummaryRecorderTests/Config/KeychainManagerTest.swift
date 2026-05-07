import XCTest
@testable import SummaryRecorder

final class KeychainManagerTest: XCTestCase {
    private var sut: KeychainManager!
    private let testKey = "test_key_\(UUID().uuidString)"

    override func setUp() {
        super.setUp()
        sut = KeychainManager()
    }

    override func tearDown() {
        try? sut.delete(key: testKey)
        sut = nil
        super.tearDown()
    }

    func testSetAndGet_returnsValue() throws {
        try sut.save(key: testKey, value: "test-api-key-12345")

        let result = try sut.load(key: testKey)
        XCTAssertEqual(result, "test-api-key-12345")
    }

    func testGet_nonexistent_returnsNil() throws {
        let result = try sut.load(key: "nonexistent_key_\(UUID().uuidString)")
        XCTAssertNil(result)
    }

    func testSet_overwritesValue() throws {
        try sut.save(key: testKey, value: "first")
        try sut.save(key: testKey, value: "second")

        let result = try sut.load(key: testKey)
        XCTAssertEqual(result, "second")
    }

    func testDelete_removesValue() throws {
        try sut.save(key: testKey, value: "to-delete")
        try sut.delete(key: testKey)

        let result = try sut.load(key: testKey)
        XCTAssertNil(result)
    }

    func testDelete_nonexistent_doesNotThrow() {
        XCTAssertNoThrow(try sut.delete(key: "nonexistent_\(UUID().uuidString)"))
    }
}
