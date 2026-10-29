package org.openstrap.app.update

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A file attached to a release. [sha256] is GitHub's digest of it, lowercase hex, when GitHub gives one. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** One published OpenStrap release on GitHub, as the Updates page and the daily check use it. */
data class Release(
    /** The tag, "v2.1.0". */
    val tag: String,
    val version: List<Int>,
    /** The release notes, as the Markdown GitHub keeps ([ReleaseNotes] reads it). */
    val notes: String,
    val publishedAt: Instant?,
    /** The release's page on GitHub. */
    val page: String,
    /** The signed APK, or null when the release has none. */
    val apk: ReleaseAsset?,
    /** SHA256SUMS.txt, for an APK GitHub gives no digest for. */
    val sums: ReleaseAsset?,
) {
    /** "2.1.0". */
    val versionName: String get() = version.joinToString(".")
}

/** OpenStrap's releases on GitHub: where they're read from, and which are newer than the one installed. */
object Releases {
    const val REPO = "sayed0am/openstrap"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val PAGE = "https://github.com/$REPO/releases"

    /**
     * "v2.1.0", "2.1", "2.2.0-rc1" as [major, minor, patch], anything after a `-` or `+` dropped, or null when it isn't
     * a version.
     */
    fun version(s: String): List<Int>? {
        val parts = s.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+').split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        return numbers + List(3 - numbers.size) { 0 }
    }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * The releases in GitHub's `GET /repos/{repo}/releases` reply, newest first. Drafts, pre-releases and tags that
     * aren't versions are left out.
     */
    fun parse(json: String): List<Release> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val r = array.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) return@mapNotNull null
            val tag = r.optString("tag_name")
            val version = version(tag) ?: return@mapNotNull null
            val assets = r.optJSONArray("assets")?.let { a -> (0 until a.length()).map { asset(a.getJSONObject(it)) } }.orEmpty()
            Release(
                tag = tag,
                version = version,
                notes = r.optString("body").takeUnless { r.isNull("body") }.orEmpty(),
                publishedAt = r.optString("published_at").takeIf { it.isNotEmpty() && !r.isNull("published_at") }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                page = r.optString("html_url").ifEmpty { "$PAGE/tag/$tag" },
                apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) },
                sums = assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) },
            )
        }.sortedWith { a, b -> compare(b.version, a.version) }
    }

    private fun asset(a: JSONObject) = ReleaseAsset(
        name = a.optString("name"),
        url = a.optString("browser_download_url"),
        size = a.optLong("size"),
        sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
    )

    /** The releases newer than [installed] (a version name), newest first, from [releases] as [parse] orders them. */
    fun newer(releases: List<Release>, installed: String): List<Release> {
        val mine = version(installed) ?: return emptyList()
        return releases.filter { compare(it.version, mine) > 0 }
    }

    /**
     * A newer release is out that the Updates page hasn't shown yet ([seen] is the tag of the newest one it has):
     * the avatar and the Updates row carry a dot until it's opened.
     */
    fun waiting(releases: List<Release>, installed: String, seen: String?): Boolean =
        newer(releases, installed).firstOrNull()?.let { it.tag != seen } ?: false

    /** The release [installed] came from, when it's one of [releases]. */
    fun current(releases: List<Release>, installed: String): Release? {
        val mine = version(installed) ?: return null
        return releases.firstOrNull { compare(it.version, mine) == 0 }
    }

    /** [name]'s hash in a SHA256SUMS.txt ("<hex>  <name>" per line), lowercase, or null when it isn't listed. */
    fun sumFor(sums: String, name: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }
        ?.get(0)?.lowercase()
}
