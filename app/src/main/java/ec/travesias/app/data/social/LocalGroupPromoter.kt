package ec.travesias.app.data.social

import com.google.firebase.firestore.FirebaseFirestore
import ec.travesias.app.data.local.JsonCodec
import ec.travesias.app.data.local.MyProfileDao
import ec.travesias.app.data.local.SocialGroupDao
import ec.travesias.app.data.local.SocialPostDao
import ec.travesias.app.data.local.toDomain

/**
 * Pasa grupos creados sin red (`local-*`) a un id de Firestore.
 *
 * Sin esto, [CommunityRepository.pushGroup] los ignora para siempre y el resto
 * del viaje no los ve aunque vuelva la señal.
 */
class LocalGroupPromoter(
    private val groupDao: SocialGroupDao,
    private val postDao: SocialPostDao,
    private val profileDao: MyProfileDao
) {

    /**
     * Reescribe cada grupo `local-*` con un id real, remapea sus mensajes
     * y deja `pendingSync` para que se suban en el siguiente `pushPending`.
     *
     * @return grupos ya con id remoto, listos para `pushGroup` + `listenPosts`.
     */
    suspend fun promover(fs: FirebaseFirestore): List<TravelGroup> {
        val locales = groupDao.getAll().filter { it.id.startsWith(CommunityRepository.LOCAL_ID_PREFIX) }
        if (locales.isEmpty()) return emptyList()

        val promovidos = ArrayList<TravelGroup>()
        for (viejo in locales) {
            val nuevoId = fs.collection("groups").document().id
            val actualizado = viejo.copy(id = nuevoId, pendingSync = true)
            groupDao.upsert(actualizado)

            val posts = postDao.listForGroup(viejo.id)
            for (p in posts) {
                val nuevoPostId = if (p.id.startsWith(CommunityRepository.LOCAL_ID_PREFIX)) {
                    fs.collection("groups").document(nuevoId).collection("posts").document().id
                } else {
                    p.id
                }
                postDao.upsert(p.copy(id = nuevoPostId, groupId = nuevoId, pendingSync = true))
                if (nuevoPostId != p.id) postDao.deleteById(p.id)
            }

            groupDao.deleteById(viejo.id)

            val me = profileDao.get()
            if (me != null) {
                val ids = JsonCodec.parseStringList(me.groupIdsJson)
                profileDao.upsert(
                    me.copy(groupIdsJson = JsonCodec.stringList((ids - viejo.id + nuevoId).distinct()))
                )
            }
            promovidos += actualizado.toDomain()
        }
        return promovidos
    }
}
