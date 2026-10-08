package ru.domru.technics.domain

import ru.domru.technics.model.AccessSource
import ru.domru.technics.model.AddressSearchResult
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.House
import ru.domru.technics.model.Locality
import ru.domru.technics.model.Street
import ru.domru.technics.model.UNKNOWN_LOCALITY_ID
import ru.domru.technics.model.UNKNOWN_LOCALITY_NAME

/**
 * Склеивает одинаковые адреса из разных аккаунтов.
 * Человек видит одну дверь, но приложение помнит все способы получить к ней доступ.
 */
object AddressHierarchyMerger {
    /** Склеивает только улицы с одинаковыми названиями в одном населённом пункте. */
    fun streets(items: List<Street>): List<Street> = items
        .groupBy { streetKey(it) }
        .map { (key, group) ->
            val localityName = group.first().locality.name
            val localityKey = AddressSearch.canonicalLocality(localityName)
            Street(
                id = "street:$key",
                name = group.first().name,
                houseCount = if (group.size == 1) group.first().houseCount else null,
                sources = group.flatMap(Street::sources).distinctSources(),
                locality = Locality(
                    id = if (localityName == UNKNOWN_LOCALITY_NAME) {
                        UNKNOWN_LOCALITY_ID
                    } else {
                        "locality:$localityKey"
                    },
                    name = localityName,
                ),
            )
        }
        .sortedWith(
            compareBy(
                { AddressSearch.canonical(it.locality.name) },
                { AddressSearch.canonical(it.name) },
            ),
        )

    /** Склеивает одинаковые дома выбранной общей улицы. */
    fun houses(parent: Street, items: List<House>): List<House> = items
        .groupBy { AddressSearch.canonical(it.label) }
        .map { (key, group) ->
            House(
                id = "house:${parent.id}:$key",
                streetId = parent.id,
                label = group.first().label,
                entranceCount = if (group.size == 1) group.first().entranceCount else null,
                sources = group.flatMap(House::sources).distinctSources(),
            )
        }
        .sortedWith(compareBy({ houseNumber(it.label) }, { AddressSearch.canonical(it.label) }))

    /** Склеивает одинаковые подъезды, сохраняя все способы доступа к двери. */
    fun entrances(parent: House, items: List<Entrance>): List<Entrance> = items
        .groupBy { it.deviceIdentity?.let { device -> "device:$device" } ?:
            "label:${AddressSearch.canonical(it.label)}" }
        .map { (key, group) ->
            Entrance(
                id = "entrance:${parent.id}:$key",
                houseId = parent.id,
                label = group.first().label,
                cameraAvailable = group.any(Entrance::cameraAvailable),
                deviceIdentity = group.first().deviceIdentity,
                sources = group.flatMap(Entrance::sources).distinctSources(),
            )
        }
        .sortedWith(compareBy({ houseNumber(it.label) }, { AddressSearch.canonical(it.label) }))

    /** Убирает дубли из результатов поиска по нескольким аккаунтам. */
    fun searchResults(items: List<AddressSearchResult>): List<AddressSearchResult> {
        val groupedStreets = streets(items.map(AddressSearchResult::street))
        return items.groupBy {
            streetKey(it.street) + "|" + AddressSearch.canonical(it.house.label)
        }.mapNotNull { (_, group) ->
            val street = groupedStreets.firstOrNull {
                streetKey(it) == streetKey(group.first().street)
            } ?: return@mapNotNull null
            val house = houses(street, group.map(AddressSearchResult::house)).firstOrNull()
                ?: return@mapNotNull null
            AddressSearchResult(
                street,
                house,
                "${street.locality.name} · ${street.name} · ${house.label}",
            )
        }.sortedBy { AddressSearch.canonical(it.subtitle) }
    }

    /** Город входит в ключ, поэтому две Центральные улицы не смешиваются. */
    private fun streetKey(street: Street): String =
        AddressSearch.canonicalLocality(street.locality.name) +
            "|" + AddressSearch.canonical(street.name)

    /** Удаляет точные повторы одного и того же серверного объекта. */
    private fun List<AccessSource>.distinctSources(): List<AccessSource> =
        distinctBy { it.accountId to it.remoteId }

    // Число помогает поставить дом 9 раньше дома 11, а подъезд 2 раньше подъезда 10.
    private fun houseNumber(value: String): Int =
        Regex("\\d+").find(value)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}
