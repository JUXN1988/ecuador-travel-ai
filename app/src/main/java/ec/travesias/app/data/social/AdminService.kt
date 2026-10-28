package ec.travesias.app.data.social

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Herramientas de administrador: encontrar y borrar cuentas HUÉRFANAS.
 *
 * QUÉ ES UNA CUENTA HUÉRFANA
 * Un registro que nunca se llegó a usar: existe el documento en `identities` (alguien
 * eligió un @usuario) pero no hay vitrina, o sea que esa cuenta jamás se conectó de verdad.
 * Pasa sobre todo con los registros a medias y con las pruebas. Ocupan un nombre de usuario
 * que otro podría querer, y por eso conviene poder limpiarlos.
 *
 * QUIÉN ES ADMINISTRADOR
 * Quien tenga un documento con SU HUELLA en la colección `admins` de Firestore. Se crea a
 * mano desde la consola de Firebase, y no se puede escribir desde la app: así nadie se
 * nombra administrador a sí mismo. Para dar de alta al primero:
 *   Firestore → Iniciar colección → id `admins` → id del documento = tu huella `tv_...`
 *   (la huella sale en tu perfil, dentro de la app).
 *
 * LO QUE UN ADMINISTRADOR **NO** PUEDE HACER
 * Leer mensajes, fotos ni cofres ajenos. Todo eso va cifrado con llaves que no tiene.
 * Borrar una cuenta quita su ficha pública; no descifra absolutamente nada.
 */
class AdminService(private val identidad: () -> TravelerIdentity?) {

    /** Una cuenta candidata a limpieza, con el porqué a la vista. */
    data class Candidata(
        val usuario: String,
        val huella: String,
        val creadaEn: Long,
        /** true si nunca publicó vitrina: nunca se conectó con la cuenta de verdad. */
        val sinVitrina: Boolean,
        /** Última vez que se le vio, 0 si nunca. */
        val vistoEn: Long
    ) {
        val diasSinUsar: Long
            get() {
                val ref = if (vistoEn > 0) vistoEn else creadaEn
                if (ref <= 0) return Long.MAX_VALUE
                return (System.currentTimeMillis() - ref) / 86_400_000L
            }

        /** Motivo en una línea, para enseñarlo tal cual. */
        val motivo: String
            get() = when {
                sinVitrina && creadaEn <= 0 -> "Nunca se conectó, sin fecha"
                sinVitrina -> "Nunca se conectó · $diasSinUsar días"
                else -> "Sin abrir la app hace $diasSinUsar días"
            }
    }

    /** ¿Soy administrador? Se comprueba contra Firestore, no contra nada guardado aquí. */
    suspend fun soyAdmin(fs: FirebaseFirestore?): Boolean = withContext(Dispatchers.IO) {
        val huella = identidad()?.fingerprint ?: return@withContext false
        val firestore = fs ?: return@withContext false
        withTimeoutOrNull(12_000L) {
            runCatching { firestore.collection(COL_ADMINS).document(huella).get().await().exists() }
                .getOrDefault(false)
        } ?: false
    }

    /**
     * Cuentas que se pueden limpiar. Se cruzan `identities` (quién existe) con `showcase`
     * (quién se ha conectado de verdad).
     *
     * @param diasMinimos no se propone nada más nuevo que esto, para no borrar a alguien
     *        que se registró hace un rato y todavía no ha abierto la app.
     */
    suspend fun buscarHuerfanas(
        fs: FirebaseFirestore?,
        diasMinimos: Int = 30,
        tope: Int = 200
    ): List<Candidata> = withContext(Dispatchers.IO) {
        val firestore = fs ?: return@withContext emptyList()
        val corte = System.currentTimeMillis() - diasMinimos * 86_400_000L

        val cuentas = withTimeoutOrNull(25_000L) {
            runCatching { firestore.collection(AccountService.COL_IDENTIDADES).limit(tope.toLong()).get().await() }
                .getOrNull()
        } ?: return@withContext emptyList()

        // Una sola lectura de la vitrina entera y se cruza en memoria: mucho más barato
        // que preguntar por cada cuenta, y el plan gratuito se agradece.
        val vitrinas = withTimeoutOrNull(25_000L) {
            runCatching { firestore.collection("showcase").limit(tope.toLong()).get().await() }.getOrNull()
        }
        val vistoPorHuella = HashMap<String, Long>()
        vitrinas?.documents?.forEach { d ->
            val fp = d.getString("fp") ?: d.id
            vistoPorHuella[fp] = d.getLong("vistoEn") ?: d.getLong("createdAt") ?: 0L
        }

        val yo = identidad()?.fingerprint
        cuentas.documents.mapNotNull { d ->
            val huella = d.getString("fp").orEmpty()
            val usuario = d.getString("u").orEmpty().ifBlank { d.id }
            if (huella.isBlank()) return@mapNotNull null
            // Nunca proponerse a uno mismo: sería el error más tonto y más caro.
            if (huella == yo) return@mapNotNull null
            val creada = d.getLong("createdAt") ?: 0L
            val visto = vistoPorHuella[huella]
            val sinVitrina = visto == null
            val referencia = visto ?: creada
            // Solo lo bastante viejo. Si no hay fecha ninguna, se propone igual: es un
            // documento roto de las primeras versiones.
            if (referencia > corte) return@mapNotNull null
            Candidata(
                usuario = usuario,
                huella = huella,
                creadaEn = creada,
                sinVitrina = sinVitrina,
                vistoEn = visto ?: 0L
            )
        }.sortedByDescending { it.diasSinUsar }
    }

    /**
     * Borra todo el rastro público de una cuenta: su ficha, su vitrina, su privacidad y su
     * cofre. Los mensajes que ya escribió en grupos NO se tocan: son de los grupos, van
     * cifrados y borrarlos rompería la conversación de los demás.
     *
     * @return una lista de lo que no se pudo borrar, vacía si salió todo.
     */
    suspend fun borrarCuenta(fs: FirebaseFirestore?, candidata: Candidata): List<String> =
        withContext(Dispatchers.IO) {
            val firestore = fs ?: return@withContext listOf("Sin conexión con la base de datos")
            val fallos = mutableListOf<String>()
            suspend fun borrar(que: String, ruta: com.google.firebase.firestore.DocumentReference) {
                val ok = withTimeoutOrNull(15_000L) { runCatching { ruta.delete().await() }.isSuccess }
                if (ok != true) fallos.add(que)
            }
            borrar("la ficha", firestore.collection(AccountService.COL_IDENTIDADES).document(candidata.usuario))
            borrar("la vitrina", firestore.collection("showcase").document(candidata.huella))
            borrar("la privacidad", firestore.collection("privacy").document(candidata.huella))
            borrar("el cofre", firestore.collection(AccountService.COL_COFRES).document(candidata.huella))
            fallos
        }

    companion object {
        const val COL_ADMINS = "admins"
    }
}
