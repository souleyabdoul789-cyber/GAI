package com.muryar.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageMapperTest {
    @Test
    fun reconnaitUneApplication() {
        assertEquals(
            AssistantCommand.OpenApp("whatsapp"),
            LanguageMapper.analyser("Ouvre WhatsApp")
        )
    }

    @Test
    fun reconnaitUnAppel() {
        assertEquals(
            AssistantCommand.CallContact("moussa"),
            LanguageMapper.analyser("Appelle Moussa")
        )
    }

    @Test
    fun reconnaitUnFichier() {
        assertEquals(
            AssistantCommand.ReadFile("contrat.pdf"),
            LanguageMapper.analyser("Lis mon fichier contrat.pdf")
        )
    }

    @Test
    fun conserveLeTexteDuMessage() {
        assertEquals(
            AssistantCommand.SendMessage("aicha", "Je viens à 18 h.", false),
            LanguageMapper.analyser("Écris à Aicha 'Je viens à 18 h.'")
        )
    }

    @Test
    fun reconnaitWhatsAppSansCasseSensibles() {
        assertEquals(
            AssistantCommand.SendMessage("aicha", "Bonjour", true),
            LanguageMapper.analyser("Ecris à Aicha 'Bonjour' sur WhatsApp")
        )
    }
}
