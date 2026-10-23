import SwiftUI

/// Reading mode, enhancement strength and colour correction.
struct ReaderSettingsView: View {

    @Binding var prefs: ReaderPrefs
    @Binding var enhance: EnhanceSettings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("阅读模式") {
                    Picker("模式", selection: $prefs.readingMode) {
                        ForEach(ReadingMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    Toggle("从右向左（日漫）", isOn: $prefs.rightToLeft)
                    Toggle("宽页自动拆分双页", isOn: $prefs.splitWidePages)
                    Toggle("双页并排（横屏）", isOn: $prefs.doublePage)
                    Toggle("自动裁掉白边", isOn: $prefs.autoCropWhiteBorders)
                }

                Section {
                    Toggle("锐化（边缘自适应）", isOn: $enhance.sharpen)
                    if enhance.sharpen {
                        numberSlider("锐化强度", value: $enhance.sharpenAmount, range: 5...100)
                    }
                    Toggle("降噪平滑", isOn: $enhance.denoise)
                    if enhance.denoise {
                        numberSlider("降噪强度", value: $enhance.denoiseBlend, range: 5...100)
                    }
                    Toggle("对比度增强", isOn: $enhance.contrastPass)
                    if enhance.contrastPass {
                        numberSlider("增强强度", value: $enhance.contrastBlend, range: 5...100)
                    }
                } header: {
                    Text("画质增强")
                } footer: {
                    Text("增强在 GPU 上实时完成，调节后会立刻重绘当前页。")
                }

                Section("画面校正") {
                    numberSlider("亮度", value: $enhance.brightness, range: 30...180)
                    numberSlider("对比度", value: $enhance.contrastLevel, range: 30...180)
                    numberSlider("伽马", value: $enhance.gamma, range: 40...220)
                }

                Section {
                    Button("重置全部增强参数") {
                        enhance = EnhanceSettings()
                    }
                }
            }
            .navigationTitle("阅读设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func numberSlider(
        _ label: String,
        value: Binding<Double>,
        range: ClosedRange<Double>
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(label).font(.system(size: 14))
                Spacer()
                Text("\(Int(value.wrappedValue))")
                    .font(.system(size: 13))
                    .foregroundColor(.secondary)
            }
            Slider(value: value, in: range, step: 1)
        }
    }
}
