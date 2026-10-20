import SwiftUI

private enum HomeSheet: String, Identifiable { case accounts, settings; var id: String { rawValue } }

struct HomeScreen: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: AppPreferences
    @Environment(\.scenePhase) private var scenePhase
    @State private var sheet: HomeSheet?

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            VStack(spacing: 0) {
                header.dynamicTypeSize(.medium).padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
                if model.accounts.isEmpty {
                    LoginForm(model: model, allowsDemo: true).padding(.horizontal, 20)
                } else {
                    searchField.padding(.horizontal, 20).padding(.bottom, 12)
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            if let error = model.errorMessage { Text(error).font(.callout).foregroundColor(Palette.amber).serviceCard() }
                            if model.isDemo { Text("Демо-режим · настоящие двери не открываются").font(.caption).foregroundColor(Palette.muted) }
                            if model.loading && model.catalog.streets.isEmpty { ProgressView("Загружаем объекты…").padding(24) }
                            else if !model.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { searchResults }
                            else { addressTree }
                            if !model.loading && model.catalog.streets.isEmpty {
                                Text("Доступных объектов пока нет").foregroundColor(Palette.muted).padding(24)
                            }
                        }.padding(.horizontal, 20).padding(.bottom, 24)
                    }
                }
            }.frame(maxWidth: 720)
        }
        .foregroundColor(Palette.text)
        .sheet(item: $sheet) { item in
            switch item {
            case .accounts: AccountsScreen(model: model)
            case .settings: SettingsScreen(preferences: preferences)
            }
        }
        .sheet(item: $model.shownCode) { code in CodeScreen(code: code) }
        .onChange(of: sheet) { model.camera.setObscured($0 != nil || model.shownCode != nil) }
        .onChange(of: model.shownCode?.id) { model.camera.setObscured($0 != nil || sheet != nil) }
        .onChange(of: scenePhase) { phase in
            // Краткое inactive (например, системное окно) ещё не означает уход в фон.
            if phase == .active { model.foreground() }
            else if phase == .background { model.background() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    // Фиксированные размеры сохраняют компактную шапку при крупном системном шрифте.
                    Text("Домофон").font(.system(size: 16, weight: .semibold)).foregroundColor(Palette.muted)
                    Text("dom.ru/lk.proptech.ru").font(.system(size: 20, weight: .bold)).foregroundColor(Palette.green)
                }
                Spacer(minLength: 6)
                Button { sheet = .settings } label: {
                    Image(systemName: "gearshape").font(.system(size: 22)).frame(width: 44, height: 44)
                }.accessibilityLabel("Настройки").accessibilityIdentifier("settings")
            }
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Служебный доступ").font(.system(size: 27, weight: .bold)).fixedSize(horizontal: false, vertical: true)
                    if !model.accounts.isEmpty {
                        Text("Активных аккаунтов: \(model.usableAccounts.count) из \(model.accounts.count)").font(.caption).foregroundColor(Palette.muted)
                    }
                }
                Spacer(minLength: 0)
                if !model.accounts.isEmpty {
                    Button { sheet = .accounts } label: {
                        HStack(spacing: 6) { Image(systemName: "person.fill"); Text("\(model.accounts.count)") }
                            .font(.system(size: 18, weight: .semibold)).padding(12).background(Palette.soft).clipShape(Capsule())
                    }.accessibilityLabel("Аккаунты").accessibilityIdentifier("accounts")
                }
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass").font(.system(size: 20)).foregroundColor(Palette.muted)
            TextField("Город, улица или дом", text: $model.query).accessibilityIdentifier("address-search")
                .textInputAutocapitalization(.never).disableAutocorrection(true)
            if !model.query.isEmpty { Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 20)).frame(width: 32, height: 44) }.accessibilityLabel("Очистить поиск") }
            else { Button { model.refresh() } label: { Image(systemName: "arrow.clockwise").font(.system(size: 20)).frame(width: 32, height: 44) }.accessibilityLabel("Обновить объекты").disabled(model.loading) }
        }.padding(.horizontal, 16).padding(.vertical, 8).background(Palette.surface).clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var localities: [Locality] {
        var seen = Set<String>()
        return model.catalog.streets.map(\.locality).filter { seen.insert($0.id).inserted }
    }

    @ViewBuilder private var addressTree: some View {
        ForEach(localities) { locality in
            let expanded = model.selection.localityID == locality.id
            Button { model.selectCity(locality) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(locality.name).font(.title3.bold())
                        Text("Улиц: \(model.catalog.streets.filter { $0.locality.id == locality.id }.count)").font(.caption).foregroundColor(Palette.muted)
                    }
                    Spacer(); Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.system(size: 18))
                }.frame(maxWidth: .infinity, alignment: .leading).serviceCard(expanded ? Palette.selected : Palette.surface)
            }.buttonStyle(.plain).accessibilityIdentifier("city-\(locality.name)")
            if expanded {
                ForEach(model.catalog.streets.filter { $0.locality.id == locality.id }) { street in
                    ForEach(model.catalog.houses[street.id] ?? []) { house in
                        houseRow(house, street: street)
                        if model.selection.houseID == house.id {
                            ForEach(model.catalog.entrances[house.id] ?? []) { entrance in
                                EntranceCard(model: model, preferences: preferences, entrance: entrance)
                                    .padding(.leading, 16)
                            }
                            if model.catalog.entrances[house.id]?.isEmpty != false {
                                Text("В этом доме нет доступных домофонов").font(.callout).foregroundColor(Palette.muted).padding()
                            }
                        }
                    }
                }
            }
        }
    }

    private func houseRow(_ house: House, street: Street) -> some View {
        Button { model.selectHouse(house, street: street) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(street.name) · \(house.label)").font(.headline).fixedSize(horizontal: false, vertical: true)
                    Text("Подъездов: \(model.catalog.entrances[house.id]?.count ?? 0)").font(.caption).foregroundColor(Palette.muted)
                }
                Spacer(minLength: 6)
                Image(systemName: model.selection.houseID == house.id ? "chevron.up" : "chevron.down").font(.system(size: 18))
            }.serviceCard(model.selection.houseID == house.id ? Palette.soft : Palette.surface, radius: 19)
        }.buttonStyle(.plain).padding(.leading, 8).accessibilityIdentifier("house-\(street.name)-\(house.label)")
    }

    @ViewBuilder private var searchResults: some View {
        let results = model.catalog.streets.flatMap { street in
            (model.catalog.houses[street.id] ?? []).filter {
                AddressSearch.matches([street.locality.name, street.name, $0.label], query: model.query)
            }.map { (street, $0) }
        }
        if results.isEmpty { Text("Ничего не найдено").foregroundColor(Palette.muted).padding() }
        ForEach(Array(results.prefix(30).enumerated()), id: \.element.1.id) { _, result in
            Button { model.selectSearchResult(house: result.1, street: result.0) } label: {
                VStack(alignment: .leading, spacing: 5) {
                    Text(result.0.locality.name).font(.caption).foregroundColor(Palette.muted)
                    Text("\(result.0.name) · \(result.1.label)").font(.headline)
                }.frame(maxWidth: .infinity, alignment: .leading).serviceCard()
            }.buttonStyle(.plain)
        }
    }
}

private struct EntranceCard: View {
    @ObservedObject var model: AppModel
    @ObservedObject var preferences: AppPreferences
    let entrance: Entrance
    @Environment(\.dynamicTypeSize) private var typeSize
    private var expanded: Bool { model.selection.entranceID == entrance.id }
    private var state: DoorState { model.doors[entrance.id] ?? .idle }
    private var busy: Bool { state == .sending || model.codeLoading == entrance.id }
    private var stacked: Bool { typeSize >= .xxLarge }
    private var openLeft: Bool { preferences.handedness == .right }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                if !expanded && !stacked && openLeft { openButton }
                VStack(alignment: .leading, spacing: 3) {
                    Text(expanded ? entrance.label : entrance.compactLabel).font(expanded ? .title3.bold() : .headline)
                        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                    if entrance.sources.count > 1 { Text("Доступов: \(entrance.sources.count)").font(.caption).foregroundColor(Palette.muted) }
                }
                if !expanded && !stacked && !openLeft { openButton }
                Button { model.selectEntrance(entrance) } label: {
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.system(size: 18)).frame(width: 44, height: 44)
                }.accessibilityLabel(expanded ? "Скрыть камеру" : "Показать камеру").accessibilityIdentifier("expand-\(entrance.id)")
            }
            if !expanded && stacked { HStack { if !openLeft { Spacer() }; openButton; if openLeft { Spacer() } } }
            if expanded {
                if entrance.cameraAvailable {
                    CameraPreview(controller: model.camera, entrance: entrance, accounts: model.usableAccounts, demo: model.isDemo)
                        .id(entrance.id)
                } else {
                    Label("Для этого подъезда камера недоступна", systemImage: "video.slash").font(.callout).foregroundColor(Palette.muted)
                        .frame(maxWidth: .infinity).padding(.vertical, 30).background(Palette.soft).cornerRadius(17)
                }
                if stacked { VStack(spacing: 8) { actions } } else { HStack(spacing: 8) { actions } }
            }
            if let message = state.message { Text(message).font(.caption).foregroundColor(Palette.amber).frame(maxWidth: .infinity, alignment: .leading) }
        }.padding(12).background(Palette.surface).clipShape(RoundedRectangle(cornerRadius: expanded ? 22 : 18))
    }

    @ViewBuilder private var actions: some View {
        if openLeft { openButton.frame(maxWidth: .infinity); codeButton } else { codeButton; openButton.frame(maxWidth: .infinity) }
    }

    private var openButton: some View {
        Button { model.open(entrance) } label: {
            Group { if state == .sending { ProgressView().tint(.white) } else { Text(state.caption) } }
                .frame(maxWidth: expanded ? .infinity : nil)
        }.buttonStyle(ActionButtonStyle(color: state == .opened ? Palette.green : (state.message != nil ? Palette.amber : Palette.red)))
            .disabled(busy || model.usableAccounts.isEmpty).accessibilityIdentifier("open-\(entrance.id)")
    }

    private var codeButton: some View {
        Button { model.requestCode(entrance) } label: {
            Group { if model.codeLoading == entrance.id { ProgressView() } else { Text("КОД") } }.frame(maxWidth: .infinity)
        }.buttonStyle(ActionButtonStyle(color: Palette.soft, foreground: Palette.text)).disabled(busy || model.usableAccounts.isEmpty)
    }
}
