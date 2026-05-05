package com.kohei.summaryrecorder.recorder

/**
 * GaplessRecorder テスト用拡張関数。
 *
 * 本番コードに混在していたテスト専用メソッドをExtensionとして分離。
 * GaplessRecorder の internal ブリッジメソッド経由で private メンバーにアクセス。
 */

/**
 * テスト用: バイト配列をPCMデータとして書込む。
 * AudioProviderを使わずにファイル書込みロジックを検証する。
 */
fun GaplessRecorder.writeTestPcmData(data: ByteArray) {
    performWriteTestPcmData(data)
}

/**
 * テスト用: 現在のチャンクを確定して停止。
 * AudioProviderへの依存なし。非suspend（runBlocking内でmutex使用）。
 */
fun GaplessRecorder.stopForTest() {
    performStopForTest()
}
