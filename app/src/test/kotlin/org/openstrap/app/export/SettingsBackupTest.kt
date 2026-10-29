package org.openstrap.app.export

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    private val settings: Map<String, Any> = mapOf(
        "profile_birth_year" to 1990,
        "profile_sex" to "Female",
        "profile_weight_kg" to 61.4f,
        "profile_height_cm" to 168,
        "profile_imperial" to false,
        "profile_activity_answered_at" to 1_792_195_200L,
        "profile_body_measured_at" to 1_792_000_000L,
        "profile_beta_blocker" to false,
        "goal_steps" to 9_000,
        "goal_sleep_minutes" to 450,
        "goal_active_hours" to 10,
        "today_hidden" to "Stress,Energy",
        "today_pinned" to "",
        "first_score_day" to 20_380L,
        "live_hr" to true,
        "workout_sides" to "1:1,2:0",
        "buzz_pattern" to "ONE_LONG",
    )

    @Test fun `settings come back as they went, each in its type`() {
        val back = SettingsBackup.fromJson(SettingsBackup.toJson(settings))
        assertEquals(settings, back)
        assertTrue(back["profile_activity_answered_at"] is Long)
        assertTrue(back["profile_weight_kg"] is Float)
        assertTrue(back["goal_steps"] is Int)
    }

    @Test fun `the strap's key, the Zepp sign-in and the backup folder never go in`() {
        val secrets = mapOf(
            "device_address" to "C8:0F:10:AB:CD:EF", "device_auth_key" to "00112233445566778899aabbccddeeff",
            "zepp_app_token" to "token", "zepp_login_token" to "login", "zepp_user_id" to "123",
            "companion_association_id" to 4, "last_sync_hr" to 1L, "strap_last_sync_done" to 1L,
            "backup_folder" to "content://x", "buzz_calls" to true, "strap_sessions" to true,
        )
        val json = SettingsBackup.toJson(settings + secrets)
        for (key in secrets.keys) assertFalse(key, key in json)
        assertFalse("0011223344" in json)
        assertEquals(settings, SettingsBackup.fromJson(json))
    }

    @Test fun `a newer or damaged file restores what it can`() {
        val json = JSONObject().put("version", 9).put("settings", JSONObject()
            .put("goal_steps", 12_000)
            .put("goal_sleep_minutes", "eight hours")
            .put("profile_height_cm", 170.5)
            .put("device_auth_key", "00112233445566778899aabbccddeeff")
            .put("something_new", true)).toString()
        assertEquals(mapOf<String, Any>("goal_steps" to 12_000), SettingsBackup.fromJson(json))
        assertEquals(emptyMap<String, Any>(), SettingsBackup.fromJson("{}"))
    }

    @Test fun `a restore writes only what this phone has never set`() {
        val missing = SettingsBackup.missing(settings, present = setOf("goal_steps", "profile_sex", "device_auth_key"))
        assertFalse("goal_steps" in missing)
        assertFalse("profile_sex" in missing)
        assertEquals(settings.size - 2, missing.size)
    }
}
