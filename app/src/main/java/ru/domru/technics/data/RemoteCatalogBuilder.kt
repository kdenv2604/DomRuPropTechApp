package ru.domru.technics.data

import ru.domru.technics.domain.AddressSearch
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.UNKNOWN_LOCALITY_ID
import ru.domru.technics.model.UNKNOWN_LOCALITY_NAME

/** Собирает каталог из ответов портала; сеть и хранение сессий остаются в репозитории. */
internal class RemoteCatalogBuilder {
    private val streets = mutableListOf<Street>()
    private val housesByStreet = mutableMapOf<String, MutableList<House>>()
    private val entrancesByHouse = mutableMapOf<String, List<Entrance>>()
    private val doorsByEntrance = mutableMapOf<String, RemoteDoor>()

    fun addSpecification(
        specification: PortalSpecification,
        companyLocality: String?,
        portalHouses: List<PortalHouse>,
        porches: List<PortalPorch>,
        devices: List<PortalDevice>,
    ) {
        // Устройство с противоречивой привязкой остаётся в «Других объектах».
        val devicesByHouseId = devices.groupBy { device ->
            portalHouses.filter { house -> house.matchesRoute(device.route) }
                .map(PortalHouse::houseId)
                .distinct()
                .singleOrNull()
        }

        portalHouses.forEach { portalHouse ->
            val address = RussianAddressParser.parse(portalHouse.address)
            // Если город есть в полном адресе, он становится отдельной верхней ступенью.
            val localityName = address.locality ?: companyLocality ?: UNKNOWN_LOCALITY_NAME
            val localityKey = if (localityName == UNKNOWN_LOCALITY_NAME) {
                "unknown"
            } else {
                AddressSearch.canonicalLocality(localityName)
            }
            val localityId = remoteId(specification.id, "locality", localityKey)
            val streetKey = "$localityKey|${AddressSearch.canonical(address.street)}"
            val streetId = remoteId(specification.id, "street", streetKey)
            val houseId = remoteId(specification.id, "house", portalHouse.houseId)
            val houseRoute = HouseRoute.decode(portalHouse.route)
            val houseIds = setOfNotNull(portalHouse.houseId, houseRoute?.houseId)
            val housePorches = porches.filter { porch ->
                val hasMatchingParent = portalHouse.route.isNotBlank() &&
                    porch.parentRoute == portalHouse.route
                hasMatchingParent || HouseRoute.decode(porch.route)?.houseId in houseIds
            }
            val houseDevices = devicesByHouseId[portalHouse.houseId].orEmpty()
            val entrances = houseDevices.map { device ->
                val route = HouseRoute.decode(device.route)
                val porch = housePorches.firstOrNull { candidate ->
                    candidate.route == device.route ||
                        route?.porch in setOf(candidate.porchId, candidate.number)
                }
                device.toEntrance(specification.id, houseId, porch).also { entrance ->
                    doorsByEntrance[entrance.id] = device.toRemoteDoor(specification.id)
                }
            }

            if (streets.none { it.id == streetId }) {
                streets += Street(
                    id = streetId,
                    name = address.street,
                    locality = Locality(id = localityId, name = localityName),
                )
            }
            housesByStreet.getOrPut(streetId, ::mutableListOf) += House(
                id = houseId,
                streetId = streetId,
                label = address.house,
                entranceCount = entrances.size,
            )
            entrancesByHouse[houseId] = entrances
        }

        val unusedDevices = devicesByHouseId[null].orEmpty()
        if (unusedDevices.isNotEmpty()) {
            addUnassignedDevices(
                specification = specification,
                devices = unusedDevices,
            )
        }
    }

    private fun PortalHouse.matchesRoute(deviceRoute: String): Boolean {
        val deviceHouseId = HouseRoute.decode(deviceRoute)?.houseId
        val houseIds = setOfNotNull(houseId, HouseRoute.decode(route)?.houseId)
        if (deviceHouseId != null && deviceHouseId in houseIds) return true

        // Старые маршруты могут не разбираться HouseRoute, но всё ещё имеют сегменты через точку.
        // Проверяем границу сегмента: дом 12 не должен включать устройства дома 123.
        val housePath = route.trimEnd('.')
        return housePath.isNotBlank() &&
            (deviceRoute == housePath || deviceRoute.startsWith("$housePath."))
    }

    fun build(complete: Boolean): RemoteCatalog {
        val countedStreets = streets.map { street ->
            street.copy(houseCount = housesByStreet[street.id].orEmpty().size)
        }.sortedWith(
            compareBy(
                { AddressSearch.canonical(it.locality.name) },
                { AddressSearch.canonical(it.name) },
            ),
        )
        return RemoteCatalog(
            streets = countedStreets,
            housesByStreetId = housesByStreet,
            entrancesByHouseId = entrancesByHouse,
            doorsByEntranceId = doorsByEntrance,
            complete = complete,
        )
    }

    /** Не теряет устройства, которые сервер не смог связать с конкретным домом. */
    private fun addUnassignedDevices(
        specification: PortalSpecification,
        devices: List<PortalDevice>,
    ) {
        val streetId = remoteId(specification.id, "street", "other")
        val houseId = remoteId(specification.id, "house", "other")
        if (streets.none { it.id == streetId }) {
            streets += Street(
                id = streetId,
                name = "Другие объекты",
                locality = Locality(UNKNOWN_LOCALITY_ID, UNKNOWN_LOCALITY_NAME),
            )
        }
        val entrances = devices.map { device ->
            device.toEntrance(specification.id, houseId, porch = null).also { entrance ->
                doorsByEntrance[entrance.id] = device.toRemoteDoor(specification.id)
            }
        }
        housesByStreet.getOrPut(streetId, ::mutableListOf) += House(
            id = houseId,
            streetId = streetId,
            label = "Без адресной привязки",
            entranceCount = entrances.size,
        )
        entrancesByHouse[houseId] = entrances
    }

    /** Превращает техническую запись устройства в понятную строку подъезда. */
    private fun PortalDevice.toEntrance(
        specificationId: String,
        houseId: String,
        porch: PortalPorch?,
    ): Entrance = Entrance(
        id = remoteId(specificationId, "door", id),
        houseId = houseId,
        label = porch?.number?.let { "Подъезд $it" } ?: name,
        cameraAvailable = cameraAvailable,
        deviceIdentity = id,
    )

    /** Оставляет серверные номера, нужные только для команд двери. */
    private fun PortalDevice.toRemoteDoor(specificationId: String): RemoteDoor = RemoteDoor(
        specificationId = specificationId,
        deviceId = id,
        accessControlId = accessControlId,
        cameraAvailable = cameraAvailable,
    )

    /** Добавляет компанию к номеру объекта, чтобы два аккаунта не перепутали записи. */
    private fun remoteId(specificationId: String, kind: String, value: String): String =
        "$specificationId::$kind::$value"
}
