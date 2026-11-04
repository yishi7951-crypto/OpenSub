package com.opensub.app.core

import kotlin.test.*

class TranslationRulesTest {
    @Test fun everyRuleMatchesItsOwnExample() {
        for (rule in SUBTITLE_RULES.rules) {
            assertContains(SUBTITLE_RULES.references(rule.example), "${rule.english} translates to ${rule.chinese}", rule.example)
        }
    }

    @Test fun aVerbMatchesInAnyForm() {
        val rules = TranslationRules("{drive} someone away = 把某人逼走 # I drove her away.")
        for (sentence in listOf("I drove her away.", "You keep driving everyone away", "That talk drives customers away", "He'll drive you away")) {
            assertEquals(listOf("drive someone away translates to 把某人逼走"), rules.references(sentence), sentence)
        }
    }

    @Test fun someoneIsOneToThreeWords() {
        val rules = TranslationRules("{drive} someone away = 把某人逼走 # I drove her away.")
        assertEquals(1, rules.references("you drove the new guy away").size)
        assertEquals(0, rules.references("then she drove away").size)
        assertEquals(0, rules.references("I drove all the way to the coast and back again, away from it all").size)
    }

    @Test fun aPossessiveTakesAnyOwner() {
        val rules = TranslationRules("{pull} someone's leg = 跟某人开玩笑 # She's pulling your leg.")
        assertEquals(1, rules.references("Stop pulling my leg").size)
        assertEquals(1, rules.references("he pulled Tom's leg").size)
        assertEquals(0, rules.references("he pulled a leg muscle").size)
    }

    @Test fun aLongerRuleWinsOverOneInsideIt() {
        val rules = TranslationRules("""
            closed book = 让人看不透的人 # He's a closed book.
            like a closed book = 让人看不透 # Like a closed book.
        """.trimIndent())
        assertEquals(listOf("like a closed book translates to 让人看不透"), rules.references("I'm a hard man to know, like a closed book."))
    }

    @Test fun aSentenceWithoutIdiomsHasNoReferences() {
        assertEquals(emptyList(), SUBTITLE_RULES.references("I didn't pull the trigger."))
        assertEquals(emptyList(), SUBTITLE_RULES.references("We went south for the winter."))
    }

    @Test fun curlyApostrophesCount() {
        assertEquals(1, TranslationRules("{pull} someone's leg = 跟某人开玩笑 # x").references("She’s pulling your leg").size)
    }

    @Test fun verbFormsCoverRegularAndIrregularVerbs() {
        assertTrue(verbForms("drive").containsAll(listOf("drive", "drives", "drove", "driven", "driving")))
        assertTrue(verbForms("stop").containsAll(listOf("stops", "stopped", "stopping")))
        assertTrue(verbForms("try").containsAll(listOf("tries", "tried", "trying")))
        assertTrue(verbForms("go").containsAll(listOf("goes", "went", "gone", "going")))
    }
}
