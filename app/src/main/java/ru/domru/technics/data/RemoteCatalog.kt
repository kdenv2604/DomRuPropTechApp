package ru.domru.technics.data

import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Street

/** Готовое дерево одного аккаунта и данные, нужные для команды двери. */
internal data class RemoteCatalog(
    val streets: List<Street>,
    val housesByStreetId: Map<String, List<House>>,
    val entrancesByHouseId: Map<String, List<Entrance>>,
    val doorsByEntranceId: Map<String, RemoteDoor>,
    val complete: Boolean = true,
)

/** Минимальные данные, которых достаточно для открытия одной двери. */
internal data class RemoteDoor(
    val specificationId: String,
    val deviceId: String,
    val accessControlId: String,
    val cameraAvailable: Boolean = true,
)

/** Служебная компания, от имени которой разрешены запросы к порталу. */
internal data class PortalSpecification(
    val id: String,
    val companyId: String,
    val localityName: String?,
)

/** Дом в том виде, в котором его вернул служебный портал. */
internal data class PortalHouse(
    val id: String,
    val houseId: String,
    val address: String,
    val route: String,
)

/** Подъезд с серверным маршрутом, связывающим его с домом и устройством. */
internal data class PortalPorch(
    val porchId: String,
    val number: String,
    val route: String,
    val parentRoute: String,
)

/** Домофон и разрешённые для него возможности. */
internal data class PortalDevice(
    val id: String,
    val accessControlId: String,
    val name: String,
    val route: String,
    val cameraAvailable: Boolean,
)
