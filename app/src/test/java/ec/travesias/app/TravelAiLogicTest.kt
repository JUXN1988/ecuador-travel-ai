package ec.travesias.app

import ec.travesias.app.data.ai.GeminiTravelService
import ec.travesias.app.data.model.EcuadorRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelAiLogicTest {

    private val service = GeminiTravelService()

    @Test
    fun testOfflineItineraryGeneration() = runBlocking {
        val plan = service.generateItinerary(
            region = EcuadorRegion.ANDES,
            days = 3,
            budgetStyle = "Moderado",
            travelers = 2,
            customPrompt = "Caminatas en páramo andino y gastronomía tradicional"
        )

        assertNotNull(plan)
        assertEquals(3, plan.days.size)
        assertEquals(EcuadorRegion.ANDES, plan.targetRegion)
        assertTrue(plan.totalBudgetUsd > 0)
        assertTrue(plan.days[0].activities.isNotEmpty())
    }

    @Test
    fun testOfflineAssistantSorocheReply() = runBlocking {
        val reply = service.askAssistant("¿Qué hago si me da soroche en Quito o Cotopaxi?")
        assertTrue(reply.contains("mal de altura", ignoreCase = true) || reply.contains("coca", ignoreCase = true) || reply.contains("hidratad", ignoreCase = true))
    }

    @Test
    fun testOfflineAssistantCurrencyReply() = runBlocking {
        val reply = service.askAssistant("¿Qué moneda se usa en Ecuador?")
        assertTrue(reply.contains("Dólar", ignoreCase = true) || reply.contains("USD", ignoreCase = true))
    }
}
