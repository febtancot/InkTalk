package com.inktalk.ime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestParserTest {
    @Test fun parsesValidHttpsManifest() {
        val parsed = UpdateManifestParser.parse(
            """{"schema":1,"versionCode":5,"versionName":"0.5","apkUrl":"https://inktalk.liveby.app/InkTalk-0.5.apk","sha256":"${"a".repeat(64)}","releaseNotes":"更新"}"""
        )
        assertEquals(5, parsed?.versionCode)
    }

    @Test fun rejectsInsecureOrMalformedManifest() {
        assertNull(UpdateManifestParser.parse("{}"))
        assertNull(UpdateManifestParser.parse(
            """{"schema":1,"versionCode":5,"versionName":"0.5","apkUrl":"http://bad/apk","sha256":"${"a".repeat(64)}"}"""
        ))
    }
}
