package ru.boostyatv.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class AppReleaseTest {
    private static JSONObject release(String tag, String assetName, String url) throws Exception {
        return new JSONObject()
            .put("tag_name", tag)
            .put("body", "Изменения")
            .put("assets", new JSONArray().put(new JSONObject()
                .put("name", assetName)
                .put("browser_download_url", url)));
    }

    @Test public void acceptsOfficialApkAssetOnly() throws Exception {
        JSONObject data = release("v1.2.3", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.2.3/BoostyATV.apk");
        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "a".repeat(64));
        AppRelease release = AppRelease.parse(data);
        assertEquals("1.2.3", release.version());
        assertEquals("Изменения", release.notes());
        assertTrue(release.apkUrl().endsWith("/v1.2.3/BoostyATV.apk"));
        assertEquals("a".repeat(64), release.sha256());
    }

    @Test public void rejectsAssetsWithoutDigest() throws Exception {
        assertThrows(IOException.class, () -> AppRelease.parse(release("v1.2.3", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.2.3/BoostyATV.apk")));
    }

    @Test public void acceptsSha256DigestAndRejectsMalformedOnes() throws Exception {
        JSONObject data = release("v1.2.3", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.2.3/BoostyATV.apk");
        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "a".repeat(64));
        assertEquals("a".repeat(64), AppRelease.parse(data).sha256());

        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:xyz");
        assertThrows(IOException.class, () -> AppRelease.parse(data));
        data.getJSONArray("assets").getJSONObject(0).put("digest", "md5:abc");
        assertThrows(IOException.class, () -> AppRelease.parse(data));
    }

    @Test public void rejectsForeignDownloadUrl() throws Exception {
        assertThrows(IOException.class, () -> AppRelease.parse(release("v1.2.3", "BoostyATV.apk",
            "https://evil.example/BoostyATV.apk")));
    }

    @Test public void rejectsDefaultGradleApkAssetName() throws Exception {
        assertThrows(IOException.class, () -> AppRelease.parse(release("v1.2.3", "app-release.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.2.3/app-release.apk")));
    }

    @Test public void rejectsDraftPrereleaseAndMalformedTags() throws Exception {
        JSONObject draft = release("v1.2.3", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.2.3/BoostyATV.apk")
            .put("draft", true);
        assertThrows(IOException.class, () -> AppRelease.parse(draft));
        JSONObject prerelease = new JSONObject(draft.toString()).put("draft", false).put("prerelease", true);
        assertThrows(IOException.class, () -> AppRelease.parse(prerelease));
        assertThrows(IOException.class, () -> AppRelease.parse(
            release("release-1.2.3", "BoostyATV.apk",
                "https://github.com/cergo666/BoostyATV/releases/download/release-1.2.3/BoostyATV.apk")));
    }

    @Test public void comparesVersionsNumerically() throws Exception {
        JSONObject data = release("v1.10.0", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.10.0/BoostyATV.apk");
        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "b".repeat(64));
        AppRelease release = AppRelease.parse(data);
        assertTrue(release.newerThan("1.9.9"));
        assertFalse(release.newerThan("1.10.0"));
        assertFalse(release.newerThan("2.0.0"));
    }

    @Test public void newerThanToleratesShortAndSuffixedVersions() throws Exception {
        JSONObject data = release("v1.10.0", "BoostyATV.apk",
            "https://github.com/cergo666/BoostyATV/releases/download/v1.10.0/BoostyATV.apk");
        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "c".repeat(64));
        AppRelease release = AppRelease.parse(data);
        assertTrue(release.newerThan("1.9"));
        assertTrue(release.newerThan("1"));
        assertFalse(release.newerThan("1.10"));
        assertFalse(release.newerThan("1.10.0-debug"));
        assertFalse(release.newerThan("2"));
        // A malformed installed version compares as zero, so the update is offered.
        assertTrue(release.newerThan("x.y.z"));
    }
}
