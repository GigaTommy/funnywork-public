import SwiftUI

struct ConversationSidebarSearch {
    let active: Bool
    let activate: () -> Void
    let cancel: () -> Void
}

/// Keep the conversation mounted while the phone's history drawer opens or closes.
struct ConversationNavigationShell<Sidebar: View, Detail: View>: View {
    @Binding var compactColumn: NavigationSplitViewColumn
    let initiallyOpen: Bool
    let dismissal: UUID
    var contentScope: ConversationScope = .desktop
    var presentation: UUID? = nil
    var search: ConversationSidebarSearch? = nil
    @Environment(\.conversationScope) private var scopeNavigation
    @ViewBuilder let sidebar: () -> Sidebar
    @ViewBuilder let detail: () -> Detail
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var open = false
    @GestureState private var translation: CGFloat = 0
    private var scopeActive: Bool { scopeNavigation == nil || scopeNavigation?.scope == contentScope }
    private var drawer: Bool { UIDevice.current.userInterfaceIdiom == .phone || sizeClass == .compact }

    var body: some View {
        Group {
            if drawer {
                GeometryReader { geometry in
                    let width = min(340, max(240, geometry.size.width - 56))
                    ZStack(alignment: .leading) {
                        NavigationStack {
                            detail()
                                .accessibilityHidden(open || !scopeActive)
                                .allowsHitTesting(!open && scopeActive)
                                .toolbar {
                                    if scopeActive && !open {
                                        if #available(iOS 26.0, *) {
                                        ToolbarItem(placement: .topBarLeading) { menuButton }.sharedBackgroundVisibility(.hidden)
                                        } else {
                                            ToolbarItem(placement: .topBarLeading) { menuButton }
                                        }
                                    }
                                }
                        }
                        .accessibilityHidden(open || !scopeActive)
                        .allowsHitTesting(!open && scopeActive)
                        if open && scopeActive {
                            // Cover the toolbar without changing its inset: reflow moves the
                            // conversation's scroll anchor when a search result opens.
                            Theme.surface.frame(height: 56).frame(maxHeight: .infinity, alignment: .top)
                                .allowsHitTesting(false).accessibilityHidden(true)
                            Color.black.opacity(0.2).ignoresSafeArea()
                                .onTapGesture { open = false }
                                .accessibilityLabel("收起菜单").accessibilityAddTraits(.isButton)
                                .accessibilityIdentifier("sidebar-backdrop")
                            VStack(spacing: 0) {
                                HStack {
                                    Text("FunnyWork").font(.headline).lineLimit(1).minimumScaleFactor(0.75)
                                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                    Spacer(minLength: 8)
                                    searchButton
                                }.padding(.horizontal, 16).padding(.vertical, 8)
                                ConversationScopePicker()
                                sidebar()
                            }
                            .frame(width: width).background(Theme.surface)
                            .offset(x: translation)
                            .transition(.move(edge: .leading))
                            .accessibilityAction(.escape) { open = false }
                            .simultaneousGesture(DragGesture(minimumDistance: 24)
                                .updating($translation) { value, state, _ in
                                    if abs(value.translation.width) > abs(value.translation.height) {
                                        state = min(0, value.translation.width)
                                    }
                                }
                                .onEnded { value in
                                    if value.translation.width < -70 && abs(value.translation.width) > abs(value.translation.height) { open = false }
                                })
                        } else {
                            Color.clear.frame(width: 22).contentShape(Rectangle())
                                .accessibilityHidden(true)
                                .gesture(DragGesture(minimumDistance: 24).onEnded { value in
                                    if value.translation.width > 70 && abs(value.translation.width) > abs(value.translation.height) { open = true }
                                })
                        }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height, alignment: .leading)
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: open)
                    .onAppear { open = initiallyOpen }
                    .onChange(of: open) { _, _ in
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    }
                    .onChange(of: compactColumn) { _, column in if column == .detail { open = false } }
                    .onChange(of: dismissal) { _, _ in open = false }
                    .onChange(of: presentation) { _, _ in open = true }
                    .onChange(of: scopeNavigation?.scope) { _, scope in
                        open = scope == .desktop && contentScope == .desktop
                    }
                }
            } else {
                NavigationSplitView(preferredCompactColumn: $compactColumn) {
                    VStack(spacing: 0) {
                        if scopeActive { ConversationScopePicker() }
                        sidebar()
                    }
                        .toolbar { if scopeActive { ToolbarItem(placement: .primaryAction) { searchButton } } }
                        .accessibilityHidden(!scopeActive).allowsHitTesting(scopeActive)
                } detail: {
                    detail().accessibilityHidden(!scopeActive).allowsHitTesting(scopeActive)
                }
                    .navigationSplitViewStyle(.balanced)
            }
        }
    }
    @ViewBuilder private var searchButton: some View {
        if let search {
            if search.active {
                Button("取消", action: search.cancel).buttonStyle(.plain)
                    .lineLimit(1).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    .accessibilityIdentifier("sidebar-search-cancel")
            } else {
                Button(action: search.activate) {
                    Image(systemName: "magnifyingglass").font(.body).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel("搜索")
                    .accessibilityIdentifier("sidebar-search-toggle")
            }
        }
    }
    private var menuButton: some View {
        Button { open = true } label: {
            VStack(spacing: 5) {
                Capsule().frame(width: 18, height: 2)
                Capsule().frame(width: 18, height: 2)
            }.foregroundStyle(Theme.accent).frame(width: 32, height: 32)
                .background(Theme.line.opacity(0.65), in: RoundedRectangle(cornerRadius: 10))
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("打开菜单")
        .accessibilityIdentifier("open-sidebar")
    }
}
