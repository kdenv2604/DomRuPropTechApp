package ru.domru.technics.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import java.io.File
import java.security.MessageDigest

/** Только адреса и маршруты устройств. Токены, коды двери и ссылки видео сюда не попадают. */
internal interface CatalogCipher {
    fun encryptCatalog(plainText: String): String
    fun decryptCatalog(cipherText: String): String
}

internal class CatalogDiskStore(
    private val directory: File,
    private val cipher: CatalogCipher,
) {
    fun read(accountId: String, binding: String): RemoteCatalog? = try {
        val encrypted = file(accountId).readFully().toString(Charsets.UTF_8)
        val json = JSONObject(cipher.decryptCatalog(encrypted))
        require(json.getInt("version") == 1 && json.getString("binding") == binding)
        CatalogCodec.decode(json.getJSONObject("catalog"))
    } catch (_: Exception) {
        // Повреждённый или чужой снимок не мешает обычной загрузке с сервера.
        null
    }

    fun write(accountId: String, binding: String, catalog: RemoteCatalog) {
        directory.mkdirs()
        val target = file(accountId)
        val bytes = JSONObject().put("version", 1).put("binding", binding)
            .put("catalog", CatalogCodec.encode(catalog)).toString()
            .let(cipher::encryptCatalog).toByteArray(Charsets.UTF_8)
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
    }

    fun remove(accountId: String) = file(accountId).delete()

    private fun file(accountId: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }
}

/** Иерархический формат сохраняет связь каждого подъезда с его домом и компанией. */
internal object CatalogCodec {
    fun encode(catalog: RemoteCatalog): JSONObject = JSONObject()
        .put("complete", catalog.complete)
        .put("streets", JSONArray().apply {
            catalog.streets.forEach { street ->
                put(JSONObject().put("id", street.id).put("name", street.name)
                    .put("localityId", street.locality.id).put("locality", street.locality.name)
                    .put("houses", JSONArray().apply {
                        catalog.housesByStreetId[street.id].orEmpty().forEach { house ->
                            put(JSONObject().put("id", house.id).put("label", house.label)
                                .put("entrances", JSONArray().apply {
                                    catalog.entrancesByHouseId[house.id].orEmpty().forEach { entrance ->
                                        val door = checkNotNull(catalog.doorsByEntranceId[entrance.id])
                                        put(JSONObject().put("id", entrance.id).put("label", entrance.label)
                                            .put("identity", entrance.deviceIdentity ?: JSONObject.NULL)
                                            .put("camera", entrance.cameraAvailable)
                                            .put("specification", door.specificationId)
                                            .put("device", door.deviceId).put("accessControl", door.accessControlId))
                                    }
                                }))
                        }
                    }))
            }
        })

    fun decode(json: JSONObject): RemoteCatalog {
        val streets = mutableListOf<Street>()
        val houses = linkedMapOf<String, List<House>>()
        val entrances = linkedMapOf<String, List<Entrance>>()
        val doors = linkedMapOf<String, RemoteDoor>()
        json.getJSONArray("streets").objects().forEach { street ->
            val streetId = street.getString("id")
            require(streets.none { it.id == streetId })
            houses[streetId] = street.getJSONArray("houses").objects().map { house ->
                val houseId = house.getString("id")
                require(houseId !in entrances)
                entrances[houseId] = house.getJSONArray("entrances").objects().map { entrance ->
                    val id = entrance.getString("id")
                    require(id !in doors)
                    val camera = entrance.getBoolean("camera")
                    val specification = entrance.getString("specification")
                    require(id.startsWith("$specification::door::") && houseId.startsWith("$specification::house::"))
                    doors[id] = RemoteDoor(specification, entrance.getString("device"),
                        entrance.getString("accessControl"), camera)
                    Entrance(id, houseId, entrance.getString("label"), camera,
                        if (entrance.isNull("identity")) null else entrance.getString("identity"))
                }
                House(houseId, streetId, house.getString("label"), entrances.getValue(houseId).size)
            }
            streets += Street(streetId, street.getString("name"), houses.getValue(streetId).size,
                locality = Locality(street.getString("localityId"), street.getString("locality")))
        }
        return RemoteCatalog(streets, houses, entrances, doors, json.getBoolean("complete"))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}
