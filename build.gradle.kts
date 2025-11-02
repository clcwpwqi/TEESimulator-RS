/*
 * Copyright 2025 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ktfmt)
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include("*.gradle.kts", "app/*.gradle.kts")
    dependsOn(":app:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }
