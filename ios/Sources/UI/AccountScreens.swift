import SwiftUI

struct LoginForm: View {
    @ObservedObject var model: AppModel
    var allowsDemo = false
    var account: AccountProfile?
    var onSuccess: () -> Void = {}
    @State private var login = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var busy = false
    @State private var error: String?
    @State private var loginTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(account == nil ? "Вход для сервисных служб" : "Новый пароль").font(.title2.bold())
                Text("Используй логин и пароль от lk.proptech.ru. Можно сохранить несколько служебных аккаунтов.")
                    .font(.callout).foregroundColor(Palette.muted)
                if let account { Text("Логин: \(account.login)").font(.headline) }
                else {
                    TextField("Логин", text: $login).textContentType(.username).textInputAutocapitalization(.never)
                        .disableAutocorrection(true).padding(14).background(Palette.soft).cornerRadius(14).accessibilityIdentifier("login")
                }
                HStack {
                    Group {
                        if showPassword { TextField("Пароль", text: $password) }
                        else { SecureField("Пароль", text: $password) }
                    }.textContentType(.password).textInputAutocapitalization(.never).disableAutocorrection(true).accessibilityIdentifier("password")
                    Button { showPassword.toggle() } label: { Image(systemName: showPassword ? "eye.slash" : "eye") }
                        .accessibilityLabel(showPassword ? "Скрыть пароль" : "Показать пароль")
                }.padding(14).background(Palette.soft).cornerRadius(14)
                Text("Сессия и пароль хранятся в защищённом хранилище iOS. Пароль нужен для автоматического восстановления входа.")
                    .font(.caption).foregroundColor(Palette.muted)
                if let error { Text(error).font(.callout).foregroundColor(Palette.amber) }
                Button { submit() } label: {
                    Group { if busy { ProgressView().tint(.white) } else { Text(account == nil ? "ВОЙТИ" : "ПРОВЕРИТЬ И СОХРАНИТЬ") } }.frame(maxWidth: .infinity)
                }.buttonStyle(ActionButtonStyle()).disabled(busy || password.isEmpty || (account == nil && login.trimmingCharacters(in: .whitespaces).isEmpty))
                if allowsDemo {
                    Button { model.enterDemo() } label: { Text("ПОСМОТРЕТЬ ДЕМО").frame(maxWidth: .infinity) }
                        .buttonStyle(ActionButtonStyle(color: Palette.soft, foreground: Palette.text)).disabled(busy).accessibilityIdentifier("demo")
                }
            }.serviceCard().disabled(busy).padding(.vertical, 12)
        }.interactiveDismissDisabled(busy)
            .onDisappear { loginTask?.cancel(); password = "" }
    }

    private func submit() {
        busy = true; error = nil
        loginTask = Task {
            do {
                if let account { try await model.renew(account, password: password) }
                else { try await model.signIn(login: login, password: password) }
                password = ""; onSuccess()
            } catch { if !Task.isCancelled { self.error = userMessage(error) } }
            busy = false
        }
    }
}

struct AccountsScreen: View {
    @ObservedObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var adding = false
    @State private var renewing: AccountProfile?
    @State private var removing: AccountProfile?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    ForEach(model.accounts) { account in
                        let status = model.statuses[account.id] ?? .checking
                        VStack(alignment: .leading, spacing: 8) {
                            Text(account.title).font(.headline)
                            Text(account.login).font(.callout).foregroundColor(Palette.muted)
                            Text(account.isDemo ? "Демо-доступ" : status.caption).font(.caption)
                                .foregroundColor(status == .active ? Palette.green : Palette.amber)
                            if status == .needsPassword && !account.isDemo {
                                HStack {
                                    Button("НОВЫЙ ПАРОЛЬ") { renewing = account }
                                    Spacer()
                                    Button("УДАЛИТЬ", role: .destructive) { removing = account }
                                }.font(.caption.bold()).padding(.top, 8)
                            }
                        }.frame(maxWidth: .infinity, alignment: .leading).serviceCard()
                    }
                    if let error = model.errorMessage { Text(error).font(.callout).foregroundColor(Palette.amber) }
                    if model.isDemo {
                        Button("ВЫЙТИ ИЗ ДЕМО") { Task { await model.leaveDemo(); dismiss() } }.buttonStyle(ActionButtonStyle())
                    } else {
                        Button { model.refresh() } label: { Text("ПРОВЕРИТЬ ДОСТУПЫ").frame(maxWidth: .infinity) }
                            .buttonStyle(ActionButtonStyle(color: Palette.soft, foreground: Palette.text)).disabled(model.loading)
                        Button { adding = true } label: { Label("ДОБАВИТЬ ЛОГИН И ПАРОЛЬ", systemImage: "plus").frame(maxWidth: .infinity) }
                            .buttonStyle(ActionButtonStyle())
                    }
                }.padding(20)
            }.background(Palette.background).navigationTitle("Аккаунты").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
                .sheet(isPresented: $adding) {
                    NavigationView {
                        LoginForm(model: model, onSuccess: { adding = false }).padding(20).background(Palette.background)
                            .navigationTitle("Добавить аккаунт").navigationBarTitleDisplayMode(.inline)
                            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { adding = false } } }
                    }.navigationViewStyle(.stack)
                }
                .sheet(item: $renewing) { account in
                    NavigationView {
                        LoginForm(model: model, account: account, onSuccess: { renewing = nil }).padding(20).background(Palette.background)
                            .navigationTitle("Восстановить доступ").navigationBarTitleDisplayMode(.inline)
                            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { renewing = nil } } }
                    }.navigationViewStyle(.stack)
                }
                .alert("Удалить потерянный доступ?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
                    Button("Удалить", role: .destructive) { if let account = removing { Task { await model.remove(account) } }; removing = nil }
                    Button("Отмена", role: .cancel) { removing = nil }
                } message: { Text("Перед удалением приложение ещё раз проверит, что вход действительно потерян.") }
        }.navigationViewStyle(.stack).tint(Palette.red).foregroundColor(Palette.text)
    }
}

struct SettingsScreen: View {
    @ObservedObject var preferences: AppPreferences
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Оформление")) {
                    Picker("Тема", selection: $preferences.theme) {
                        Text("Как в системе").tag(ThemeMode.system)
                        Text("Светлая").tag(ThemeMode.light)
                        Text("Тёмная").tag(ThemeMode.dark)
                    }
                }
                Section(header: Text("Управление одной рукой")) {
                    Picker("Удобная рука", selection: $preferences.handedness) {
                        Text("Правая").tag(Handedness.right)
                        Text("Левая").tag(Handedness.left)
                    }.pickerStyle(.inline).accessibilityIdentifier("handedness")
                }
                Section(header: Text("О приложении")) {
                    Text("Домофон · Служебный доступ").font(.headline)
                    Text("Версия \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—") · сборка \(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—")")
                    Link("Исходный код", destination: URL(string: "https://github.com/kdenv2604/DomRuPropTechApp")!)
                    NavigationLink("Лицензии компонентов") { LicensesScreen() }
                }
            }.navigationTitle("Настройки").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }.navigationViewStyle(.stack).tint(Palette.red)
    }
}

private struct LicensesScreen: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("MobileVLCKit 3.7.3 · VideoLAN").font(.headline)
                Text("LGPL 2.1 или более поздняя версия. Используется для воспроизведения камер.")
                Link("Исходный код VLCKit", destination: URL(string: "https://github.com/videolan/vlckit")!)
                if let url = Bundle.main.url(forResource: "MobileVLCKit-COPYING", withExtension: "txt"),
                   let license = try? String(contentsOf: url, encoding: .utf8) { Text(license).font(.caption) }
            }.padding(20)
        }.navigationTitle("Лицензии")
    }
}

struct CodeScreen: View {
    let code: TemporalCode
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Text(code.value).font(.system(size: 44, weight: .bold, design: .monospaced))
                    .minimumScaleFactor(0.5).lineLimit(1).textSelection(.enabled).accessibilityIdentifier("door-code")
                if let date = code.updatedAt { Text("Обновлён: \(date.formatted(date: .numeric, time: .shortened))").foregroundColor(Palette.muted) }
                if let date = code.validUntil { Text("Действует до: \(date.formatted(date: .numeric, time: .shortened))").foregroundColor(Palette.muted) }
                Text("Код показан сервером для выбранной двери").font(.callout).multilineTextAlignment(.center).foregroundColor(Palette.muted)
            }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity).background(Palette.background)
                .navigationTitle("Код открытия").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } } }
        }.navigationViewStyle(.stack).tint(Palette.red).foregroundColor(Palette.text)
    }
}
