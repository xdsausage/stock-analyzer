import SwiftUI
import SwiftData

/// Per-ticker notes editor.
struct StockNotesView: View {
    let ticker: String
    @Environment(\.modelContext) private var modelContext
    @Query private var allNotes: [StockNote]
    @State private var text: String = ""
    @State private var isEditing = false

    private var note: StockNote? {
        allNotes.first { $0.ticker == ticker }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Notes")
                    .font(.subheadline.bold())
                Spacer()
                Button(isEditing ? "Done" : "Edit") {
                    if isEditing { saveNote() }
                    isEditing.toggle()
                }
                .font(.subheadline)
            }

            if isEditing {
                TextEditor(text: $text)
                    .frame(minHeight: 60, maxHeight: 120)
                    .scrollContentBackground(.hidden)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            } else {
                Text(note?.text ?? "No notes yet. Tap Edit to add.")
                    .font(.subheadline)
                    .foregroundStyle(note?.text.isEmpty ?? true ? .secondary : .primary)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .onAppear { text = note?.text ?? "" }
        .onChange(of: ticker) { _, _ in
            text = note?.text ?? ""
            isEditing = false
        }
    }

    private func saveNote() {
        if let existing = note {
            existing.text = text
        } else if !text.isEmpty {
            let newNote = StockNote(ticker: ticker, text: text)
            modelContext.insert(newNote)
        }
    }
}
