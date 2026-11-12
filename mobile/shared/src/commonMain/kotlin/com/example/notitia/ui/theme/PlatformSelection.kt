package com.example.notitia.ui.theme

// How a list chooses several items, the way each system does it. Android: a long press on a card starts, and
// Material's contextual bar covers the header, closing with its last item, as in WhatsApp and Gmail. iPhone: a long
// press opens the card's menu, which offers « Sélectionner », as does the header; a navigation bar and a toolbar in
// the tab bar's place follow, as in Mail, and the selection stays open at zero until « OK ».
enum class SelectionStyle { ContextualBar, Toolbar }

expect val selectionStyle: SelectionStyle
