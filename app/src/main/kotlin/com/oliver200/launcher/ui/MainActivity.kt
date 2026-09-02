/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/MainActivity.kt
 *  Назначение : единственное активити приложения; переключает четыре экрана.
 *  Безопасность: фрагменты добавляются один раз и переключаются
 *               show/hide — состояние экрана не теряется при переходах,
 *               и не возникает окна, когда токен уже загружен, а экран
 *               ещё пересоздаётся.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.oliver200.launcher.R

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private lateinit var toolbar: MaterialToolbar
    private var currentTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar = findViewById(R.id.toolbar)

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_versions -> switchTo(TAG_VERSIONS, R.string.versions_title)
                R.id.nav_skin -> switchTo(TAG_SKIN, R.string.skin_title)
                R.id.nav_shaders -> switchTo(TAG_SHADERS, R.string.shaders_title)
                R.id.nav_account -> switchTo(TAG_ACCOUNT, R.string.account_title)
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_versions
        } else {
            currentTag = savedInstanceState.getString(STATE_TAG)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAG, currentTag)
    }

    private fun switchTo(tag: String, titleRes: Int) {
        if (tag == currentTag) return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        fm.fragments.forEach { if (it.isAdded && it.tag != tag) tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.container, createFragment(tag), tag)
        } else {
            tx.show(existing)
        }
        tx.setReorderingAllowed(true).commit()

        currentTag = tag
        toolbar.setTitle(titleRes)
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_VERSIONS -> VersionsFragment()
        TAG_SKIN -> SkinFragment()
        TAG_SHADERS -> ShadersFragment()
        TAG_ACCOUNT -> AccountFragment()
        else -> throw IllegalArgumentException("Неизвестный экран: $tag")
    }

    private companion object {
        const val TAG_VERSIONS = "versions"
        const val TAG_SKIN = "skin"
        const val TAG_SHADERS = "shaders"
        const val TAG_ACCOUNT = "account"
        const val STATE_TAG = "current_tag"
    }
}
