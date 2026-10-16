package cn.qingji.core

import org.junit.Assert.*
import org.junit.Test

class CategoryLayoutTests {
    @Test fun legacyLayoutUsesBuiltInsThenCustomAndNewLayoutUsesPersistedOrder() {
        val custom = Category.custom("学习")
        assertEquals(Category.entries + custom, CategoryLayout.ordered(listOf(custom), emptyList()))
        val prefs = listOf(CategoryPreference(custom, 0), CategoryPreference(Category.SHOPPING, 1, true))
        val ordered = CategoryLayout.ordered(listOf(custom), prefs)
        assertEquals(custom, ordered.first()); assertFalse(ordered.contains(Category.SHOPPING)); assertTrue(ordered.contains(Category.UNCATEGORIZED))
    }
    @Test fun encryptedBackupRetainsOrderAndDeletedBuiltIns() {
        val data = BackupData(entries = emptyList(), rules = emptyList(), imports = emptyList(),
            categoryPreferences = listOf(CategoryPreference(Category.TRANSPORT, 0), CategoryPreference(Category.FOOD, 9, true)))
        assertEquals(data, BackupCodec.decrypt(BackupCodec.encrypt(data, "password1".toCharArray()), "password1".toCharArray()))
    }
    @Test fun versionTwoWithoutPreferencesStillLoadsAndRejectsInvalidLayout() {
        val legacy = ledgerJson.decodeFromString<BackupData>("""{"version":2,"entries":[],"rules":[],"imports":[],"customCategories":["CUSTOM:学习"]}""")
        BackupCodec.validate(legacy)
        assertEquals("学习", CategoryLayout.ordered(legacy.customCategories, legacy.categoryPreferences).last().label)
        listOf(
            listOf(CategoryPreference(Category.UNCATEGORIZED, 0, true)),
            listOf(CategoryPreference(Category.FOOD, -1)),
            listOf(CategoryPreference(Category.FOOD, 0), CategoryPreference(Category.FOOD, 1)),
            listOf(CategoryPreference(Category.custom("不存在"), 0))
        ).forEach { preferences -> assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(legacy.copy(categoryPreferences = preferences)) } }
    }
}
