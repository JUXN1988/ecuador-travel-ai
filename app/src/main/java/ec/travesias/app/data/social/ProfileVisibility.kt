package ec.travesias.app.data.social

/**
 * Quién puede ver tu perfil y tu álbum de viajes.
 *
 *  - PÚBLICO: cualquiera con cuenta te encuentra y ve tus fotos.
 *  - SOLO AMIGOS: solo quienes agregaste como amigo ven el álbum y los detalles.
 *  - PRIVADO: nadie ve tu perfil ni tus fotos, salvo tú.
 */
enum class ProfileVisibility(val clave: String, val titulo: String, val detalle: String) {
    PUBLIC(
        "PUBLIC",
        "Público",
        "Cualquier viajero puede encontrarte, ver tu perfil y tu álbum de fotos."
    ),
    FRIENDS(
        "FRIENDS",
        "Solo amigos",
        "Solo las personas que agregaste como amigo ven tu perfil y tus fotos."
    ),
    PRIVATE(
        "PRIVATE",
        "Privado",
        "Nadie ve tu perfil ni tu álbum. Sigues pudiendo chatear; eso es aparte."
    );

    companion object {
        fun from(raw: String?): ProfileVisibility =
            entries.firstOrNull { it.clave.equals(raw, true) || it.name.equals(raw, true) } ?: FRIENDS
    }
}
