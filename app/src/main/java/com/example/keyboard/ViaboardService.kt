package com.example.keyboard

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.LayoutInflater
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.logkeeper.TheLogKeeper
import com.example.R
import com.example.keyboard.heliboard.*
import com.example.keyboard.heliboard.*
import com.example.data.AppDatabase
import com.example.data.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class ViaboardService : InputMethodService(), KeyboardView.KeyboardListener, DesktopKeyListener {
    private lateinit var logKeeper: TheLogKeeper
    private var mainView: View? = null
    private var desktopKeyboardView: DesktopKeyboardView? = null
    
    private fun switchToDesktopView() {
        val keyboardView = mainView?.findViewById<KeyboardView>(R.id.keyboard_view)
        val desktopContainer = mainView?.findViewById<android.widget.FrameLayout>(R.id.desktop_container)
        val clipboardContainer = mainView?.findViewById<android.view.View>(R.id.clipboard_container)
        val promptContainer = mainView?.findViewById<android.view.View>(R.id.prompt_container)
        val emojiContainer = mainView?.findViewById<android.view.View>(R.id.emoji_container)

        clipboardContainer?.visibility = android.view.View.GONE
        promptContainer?.visibility = android.view.View.GONE
        emojiContainer?.visibility = android.view.View.GONE
        isClipboardModalOpen = false
        isPromptModalOpen = false
        isEmojiModalOpen = false

        if (desktopKeyboardView == null) {
            desktopKeyboardView = DesktopKeyboardView(this)
            desktopKeyboardView?.listener = this
        }
        desktopKeyboardView?.reloadKeys()

        if (desktopContainer != null) {
            desktopContainer.removeAllViews()
            desktopContainer.addView(desktopKeyboardView)
            keyboardView?.visibility = android.view.View.GONE
            desktopContainer.visibility = android.view.View.VISIBLE
        } else {
            setInputView(desktopKeyboardView)
        }
        TheLogKeeper.getInstance(this).log("INFO", "ViaboardService", "Switched to Desktop Shortcuts View")
    }

    override fun onDesktopKey(code: String) {
        TheLogKeeper.getInstance(this).log("INFO", "DesktopShortcuts", "DESKTOP_KEY_PRESSED | code=$code")
        if (code == "MODE_ALPHABET") {
            val keyboardView = mainView?.findViewById<KeyboardView>(R.id.keyboard_view)
            val desktopContainer = mainView?.findViewById<android.widget.FrameLayout>(R.id.desktop_container)
            if (desktopContainer != null && keyboardView != null) {
                desktopContainer.visibility = android.view.View.GONE
                keyboardView.visibility = android.view.View.VISIBLE
            } else {
                setInputView(mainView)
            }
            switchKeyboardLayout(R.xml.kbd_qwerty)
        } else {
            onKeyPress(code)
        }
    }
    
    private lateinit var dictionaryFacilitator: DictionaryFacilitator
    private lateinit var suggestEngine: Suggest
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionJob: Job? = null
    
    private var isToolbarExpanded = false
    private var isAutocorrectEnabled = true
    private var isManualIncognito = false
    private val tempIncognitoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tempIncognitoRunnable = Runnable {
        isManualIncognito = false
        updateIncognitoStateUI()
        updateSuggestions()
        android.widget.Toast.makeText(this@ViaboardService, "Incognito mode ended (20s)", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    enum class ShiftState {
        LOWERCASE, UPPERCASE, CAPS_LOCK
    }
    private var shiftState = ShiftState.LOWERCASE
    
    private var lastSpaceTime = 0L
    private var currentWord = StringBuilder()
    private var previousWord: String? = null
    private var prevPrevWord: String? = null
    private var suggestionGeneration: Int = 0
    private var currentSuggestions = emptyList<String>()
    
    private var isCurrentSuggestionsFuzzy = false
    private var isShowingFuzzyAfterSpace = false
    
    private var tvSuggestion1: android.widget.TextView? = null
    private var tvSuggestion2: android.widget.TextView? = null
    private var tvSuggestion3: android.widget.TextView? = null
    private var suggestionDivider1: android.view.View? = null
    private var suggestionDivider2: android.view.View? = null
    private var suggestionPaste: android.view.View? = null
    private var suggestionPasteDivider: android.view.View? = null
    private var btnIncognito: android.widget.ImageButton? = null
    private var toolbarContainer: android.view.View? = null
    
    private var lastAutocorrectedWord: String = ""
    private var lastCorrectedWord: String = ""
    private var didAutocorrect: Boolean = false
    
    private var tvSuggestionPasteText: android.widget.TextView? = null
    private var btnSuggestionPasteClose: android.widget.ImageView? = null
    private var clipboardLastDismissedText: String? = null
    private var clipboardLastObservedText: String? = null
    private var clipboardObservedTime: Long = 0L
    
    // Clipboard feature
    private var isClipboardModalOpen = false
    private lateinit var clipboardRepository: ClipboardRepository
    private var clipboardAdapter: ClipboardAdapter? = null
    
    // Prompt List feature
    private var isPromptModalOpen = false
    private var promptAdapter: PromptAdapter? = null

    // Emoji feature
    private var isEmojiModalOpen = false
    private var emojiAdapter: com.example.keyboard.EmojiAdapter? = null
    private var clipboardManager: android.content.ClipboardManager? = null
    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    override fun onCreate() {
        super.onCreate()
        
        // Trap all fatals and ensure they go to LogKeeper before the process dies
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val _logKeeper = TheLogKeeper.getInstance(this.applicationContext)
                // Use runBlocking or direct block to ensure log is saved before death
                kotlinx.coroutines.runBlocking {
                    _logKeeper.logDao.insertLog(com.example.logkeeper.data.LogEntry(
                        type = "FATAL",
                        component = "CRASH",
                        message = throwable.message ?: "Unknown crash",
                        stackTrace = throwable.stackTraceToString()
                    ))
                }
            } catch (e: Exception) {
                // Ignore if we can't log
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        logKeeper = TheLogKeeper.getInstance(this)
        logKeeper.log("INFO", "ViaboardService", "Service Created (View-based)")
        
        dictionaryFacilitator = DictionaryFacilitator(this)
        suggestEngine = Suggest(this, dictionaryFacilitator)
        clipboardRepository = ClipboardRepository(ClipboardDatabase.getDatabase(this).clipboardDao())
    }

    private fun switchKeyboardLayout(xmlResId: Int) {
        val root = mainView ?: return
        val keyboardView = root.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
        
        keyboardView.visibility = View.VISIBLE
        
        val parser = KeyboardParser(this)
        val keyboard = parser.parse(xmlResId)
        keyboardView.setKeyboard(keyboard)
        keyboardView.invalidate()
        if (xmlResId == R.xml.kbd_qwerty) {
            updateShiftState()
        }
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        
        mainView = root
        
        val keyboardView = root.findViewById<KeyboardView>(R.id.keyboard_view)
        keyboardView.listener = this
        
        // Parse and set the XML layout
        val parser = KeyboardParser(this)
        val keyboard = parser.parse(R.xml.kbd_qwerty)
        keyboardView.setKeyboard(keyboard)
        
        setupToolbar(root)
        setupClipboard(root)
        setupPromptList(root)
        
        return root
    }
    
    private fun setupClipboard(root: View) {
        val recycler = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.clipboard_recycler)
        recycler.layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL)
        
        clipboardAdapter = ClipboardAdapter(
            onItemClicked = { item ->
                currentInputConnection?.commitText(item.text, 1)
                toggleClipboardModal()
            },
            onItemLongClicked = { item ->
                showClipboardContextMenu(item)
            }
        )
        recycler.adapter = clipboardAdapter
        
        // Bottom/Sidebar actions
        root.findViewById<android.view.View>(R.id.btn_clipboard_abc)?.setOnClickListener {
            toggleClipboardModal()
        }
        root.findViewById<android.view.View>(R.id.btn_clipboard_space)?.setOnClickListener {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_SPACE)
        }
        root.findViewById<android.view.View>(R.id.btn_clipboard_clear_pinned)?.setOnClickListener {
            coroutineScope.launch(Dispatchers.IO) {
                clipboardRepository.deleteAllUnpinned()
            }
        }
        root.findViewById<android.view.View>(R.id.btn_clipboard_enter)?.setOnClickListener {
            handleEnterAction()
        }
        root.findViewById<android.view.View>(R.id.btn_clipboard_backspace)?.setOnClickListener {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
        }

        clipboardManager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        
        // Observe clipboard history
        coroutineScope.launch {
            clipboardRepository.allItems.collect { items ->
                clipboardAdapter?.setItems(items)
            }
        }
    }

    private fun setupPromptList(root: View) {
        val recycler = root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.prompt_recycler)
        recycler?.layoutManager = androidx.recyclerview.widget.StaggeredGridLayoutManager(2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL)

        promptAdapter = PromptAdapter(
            onItemClicked = { item ->
                currentInputConnection?.commitText(item.word, 1)
                togglePromptModal(false)
            },
            onItemLongClicked = { item ->
                showPromptContextMenu(item)
            }
        )
        recycler?.adapter = promptAdapter

        root.findViewById<android.view.View>(R.id.btn_prompt_abc)?.setOnClickListener {
            togglePromptModal(false)
        }
        root.findViewById<android.view.View>(R.id.btn_prompt_space)?.setOnClickListener {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_SPACE)
        }
        root.findViewById<android.view.View>(R.id.btn_prompt_enter)?.setOnClickListener {
            handleEnterAction()
        }
        root.findViewById<android.view.View>(R.id.btn_prompt_backspace)?.setOnClickListener {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
        }

        coroutineScope.launch {
            ClipboardDatabase.getDatabase(this@ViaboardService).personalDictionaryDao().getAllPrompts().collect { items ->
                promptAdapter?.setItems(items)
            }
        }
    }

    private fun showPromptContextMenu(item: PersonalDictionaryItem) {
        val options = arrayOf("Insert Prompt", "Edit Shortcut", "Delete")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Prompt")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    currentInputConnection?.commitText(item.word, 1)
                    togglePromptModal(false)
                }
                1 -> {
                    showEditShortcutDialog(item)
                }
                2 -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        ClipboardDatabase.getDatabase(this@ViaboardService).personalDictionaryDao().delete(item)
                    }
                    android.widget.Toast.makeText(this, "Prompt deleted", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        val dialog = builder.create()
        val window = dialog.window
        if (window != null) {
            val params = window.attributes
            params.token = mainView?.windowToken
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }

    private fun showEditShortcutDialog(item: PersonalDictionaryItem) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. shortcut code"
            setText(item.shortcut ?: "")
            setSingleLine()
            setPadding(40, 30, 40, 30)
        }
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Set Shortcut")
        builder.setMessage("Type this shortcut while typing to expand this prompt:")
        builder.setView(input)
        builder.setPositiveButton("Save") { _, _ ->
            val shortcutText = input.text.toString().trim()
            coroutineScope.launch(Dispatchers.IO) {
                ClipboardDatabase.getDatabase(this@ViaboardService).personalDictionaryDao().insert(
                    item.copy(shortcut = if (shortcutText.isEmpty()) null else shortcutText)
                )
            }
            android.widget.Toast.makeText(this, "Shortcut saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        val dialog = builder.create()
        val window = dialog.window
        if (window != null) {
            val params = window.attributes
            params.token = mainView?.windowToken
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }
    
    private fun showClipboardContextMenu(item: ClipboardItem) {
        val pinAction = if (item.isPinned) "Unpin" else "Pin to Top"
        val options = arrayOf(pinAction, "Add to Prompt List", "Delete")
        
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Clipboard Item")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        clipboardRepository.togglePin(item)
                    }
                }
                1 -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        ClipboardDatabase.getDatabase(this@ViaboardService).personalDictionaryDao().insert(
                            PersonalDictionaryItem(word = item.text, frequency = 250)
                        )
                        logKeeper.log("USER_ACTION", "Clipboard", "ADD_TO_PROMPT | text=${item.text}")
                    }
                    android.widget.Toast.makeText(this, "Added to Prompt List", android.widget.Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        clipboardRepository.delete(item)
                    }
                }
            }
        }
        val dialog = builder.create()
        val window = dialog.window
        if (window != null) {
            val params = window.attributes
            params.token = mainView?.windowToken
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }

    private fun onPrimaryClipChanged() {
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                val isSensitive = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && 
                    clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) || 
                    isIncognitoActive() || 
                    isSensitiveField(currentInputEditorInfo)
                
                clipboardLastObservedText = text
                clipboardObservedTime = System.currentTimeMillis()
                clipboardLastDismissedText = null
                
                updateSuggestions()
                coroutineScope.launch(Dispatchers.IO) {
                    clipboardRepository.insert(text, isSensitive)
                }
            }
        }
    }
    
    private fun toggleEmojiModal(open: Boolean? = null) {
        if (open != null) {
            isEmojiModalOpen = open
        } else {
            isEmojiModalOpen = !isEmojiModalOpen
        }
        
        val keyboardView = mainView?.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
        val emojiContainer = mainView?.findViewById<android.view.View>(R.id.emoji_container) ?: return
        
        if (isEmojiModalOpen) {
            // Close other modals
            isClipboardModalOpen = false
            isPromptModalOpen = false
            mainView?.findViewById<android.view.View>(R.id.clipboard_container)?.visibility = android.view.View.GONE
            mainView?.findViewById<android.view.View>(R.id.prompt_container)?.visibility = android.view.View.GONE

            // Hide keyboard, show emoji container
            keyboardView.visibility = android.view.View.GONE
            val desktopContainer = mainView?.findViewById<android.view.View>(R.id.desktop_container)
            desktopContainer?.visibility = android.view.View.GONE
            emojiContainer.visibility = android.view.View.VISIBLE
            TheLogKeeper.getInstance(this).log("INFO", "ViaboardService", "EMOJI_MODAL_OPENED")
            
            // Set up recycler view & search
            val searchInput = mainView?.findViewById<android.widget.EditText>(R.id.et_emoji_search)
            val searchClear = mainView?.findViewById<android.widget.ImageView>(R.id.btn_emoji_search_clear)
            val recycler = mainView?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoji_recycler)
            
            if (recycler?.adapter == null) {
                recycler?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 7)
                emojiAdapter = EmojiAdapter(
                    emojis = com.example.keyboard.emoji.EmojiDataProvider.SMILEYS,
                    onClick = { emoji ->
                        currentInputConnection?.commitText(emoji, 1)
                        com.example.keyboard.emoji.EmojiDataProvider.addRecent(this, emoji)
                        TheLogKeeper.getInstance(this).log("INFO", "EmojiModal", "EMOJI_COMMITTED | emoji=$emoji")
                    },
                    onLongClick = { emoji, view ->
                        showSkinTonePopup(emoji, view)
                    }
                )
                recycler?.adapter = emojiAdapter
                
                mainView?.findViewById<android.view.View>(R.id.btn_emoji_abc)?.setOnClickListener {
                    toggleEmojiModal(false)
                }
                mainView?.findViewById<android.view.View>(R.id.btn_emoji_backspace)?.setOnClickListener {
                    sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                    TheLogKeeper.getInstance(this).log("INFO", "EmojiModal", "EMOJI_BACKSPACE_CLICK")
                }

                searchInput?.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString()?.trim() ?: ""
                        if (query.isNotEmpty()) {
                            searchClear?.visibility = android.view.View.VISIBLE
                            val filtered = com.example.keyboard.emoji.EmojiDataProvider.searchEmojis(query)
                            emojiAdapter?.updateData(filtered)
                            TheLogKeeper.getInstance(this@ViaboardService).log("INFO", "EmojiModal", "EMOJI_SEARCH | query=$query | count=${filtered.size}")
                        } else {
                            searchClear?.visibility = android.view.View.GONE
                            emojiAdapter?.updateData(com.example.keyboard.emoji.EmojiDataProvider.SMILEYS)
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                searchClear?.setOnClickListener {
                    searchInput?.text?.clear()
                    searchClear.visibility = android.view.View.GONE
                }
                
                setupEmojiCategories()
            }
        } else {
            // Show keyboard, hide emoji container
            keyboardView.visibility = android.view.View.VISIBLE
            emojiContainer.visibility = android.view.View.GONE
        }
    }
    
    private fun showSkinTonePopup(baseEmoji: String, anchorView: View) {
        val variations = com.example.keyboard.emoji.EmojiDataProvider.getSkinToneVariations(baseEmoji) ?: return
        val linearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }
        val popupWindow = android.widget.PopupWindow(
            linearLayout,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true

        for (v in variations) {
            val tv = android.widget.TextView(this).apply {
                text = v
                textSize = 22f
                setPadding(14, 8, 14, 8)
                setOnClickListener {
                    currentInputConnection?.commitText(v, 1)
                    com.example.keyboard.emoji.EmojiDataProvider.addRecent(this@ViaboardService, v)
                    popupWindow.dismiss()
                }
            }
            linearLayout.addView(tv)
        }

        try {
            popupWindow.showAsDropDown(anchorView, 0, -anchorView.height * 2)
        } catch (e: Exception) {
            currentInputConnection?.commitText(baseEmoji, 1)
        }
    }
    
    private fun setupEmojiCategories() {
        val container = mainView?.findViewById<android.widget.LinearLayout>(R.id.emoji_category_container) ?: return
        container.removeAllViews()
        
        val categories = listOf(
            Pair("ic_emoji_recents", "Recents") to { com.example.keyboard.emoji.EmojiDataProvider.getRecents(this) },
            Pair("ic_emoji_smileys_emotion", "Smileys") to { com.example.keyboard.emoji.EmojiDataProvider.SMILEYS },
            Pair("ic_emoji_people_body", "People") to { com.example.keyboard.emoji.EmojiDataProvider.PEOPLE },
            Pair("ic_emoji_animals_nature", "Animals") to { com.example.keyboard.emoji.EmojiDataProvider.ANIMALS },
            Pair("ic_emoji_food_drink", "Food") to { com.example.keyboard.emoji.EmojiDataProvider.FOOD },
            Pair("ic_emoji_activities", "Activities") to { com.example.keyboard.emoji.EmojiDataProvider.ACTIVITIES },
            Pair("ic_emoji_travel_places", "Travel") to { com.example.keyboard.emoji.EmojiDataProvider.TRAVEL },
            Pair("ic_emoji_objects", "Objects") to { com.example.keyboard.emoji.EmojiDataProvider.OBJECTS },
            Pair("ic_emoji_symbols", "Symbols") to { com.example.keyboard.emoji.EmojiDataProvider.SYMBOLS },
            Pair("ic_emoji_flags", "Flags") to { com.example.keyboard.emoji.EmojiDataProvider.FLAGS }
        )
        
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        
        for ((cat, getList) in categories) {
            val resId = resources.getIdentifier(cat.first, "drawable", packageName)
            val ib = android.widget.ImageButton(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(110, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setImageResource(if (resId != 0) resId else R.drawable.ic_emoji_smileys_emotion)
                setBackgroundResource(typedValue.resourceId)
                setPadding(12, 12, 12, 12)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                contentDescription = cat.second
                setOnClickListener {
                    val emojis = getList()
                    emojiAdapter?.updateData(emojis)
                    mainView?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoji_recycler)?.scrollToPosition(0)
                }
            }
            container.addView(ib)
        }
    }
    
    private fun toggleClipboardModal() {
        isClipboardModalOpen = !isClipboardModalOpen
        val keyboardView = mainView?.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
        val clipboardContainer = mainView?.findViewById<android.view.View>(R.id.clipboard_container) ?: return
        val btnClearPinned = mainView?.findViewById<android.view.View>(R.id.btn_clipboard_clear_pinned)
        
        if (isClipboardModalOpen) {
            isEmojiModalOpen = false
            isPromptModalOpen = false
            mainView?.findViewById<android.view.View>(R.id.emoji_container)?.visibility = View.GONE
            mainView?.findViewById<android.view.View>(R.id.prompt_container)?.visibility = View.GONE

            coroutineScope.launch(Dispatchers.IO) {
                clipboardRepository.cleanup()
            }
            keyboardView.visibility = View.GONE
            clipboardContainer.visibility = View.VISIBLE
            btnClearPinned?.visibility = View.VISIBLE
        } else {
            clipboardContainer.visibility = View.GONE
            keyboardView.visibility = View.VISIBLE
            btnClearPinned?.visibility = View.GONE
        }
    }

    private fun togglePromptModal(open: Boolean? = null) {
        if (open != null) {
            isPromptModalOpen = open
        } else {
            isPromptModalOpen = !isPromptModalOpen
        }
        val keyboardView = mainView?.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
        val promptContainer = mainView?.findViewById<android.view.View>(R.id.prompt_container) ?: return
        
        if (isPromptModalOpen) {
            isClipboardModalOpen = false
            isEmojiModalOpen = false
            mainView?.findViewById<android.view.View>(R.id.clipboard_container)?.visibility = View.GONE
            mainView?.findViewById<android.view.View>(R.id.emoji_container)?.visibility = View.GONE

            keyboardView.visibility = View.GONE
            promptContainer.visibility = View.VISIBLE
        } else {
            promptContainer.visibility = View.GONE
            keyboardView.visibility = View.VISIBLE
        }
    }

    private fun setupToolbar(root: View) {
        val btnChevron = root.findViewById<android.widget.ImageButton>(R.id.btn_toolbar_chevron)
        val suggestionContent = root.findViewById<android.view.View>(R.id.suggestion_content)
        val expandedScroll = root.findViewById<android.view.View>(R.id.toolbar_expanded_scroll)
        val expandedContent = root.findViewById<android.widget.LinearLayout>(R.id.toolbar_expanded_content)
        val pinnedContent = root.findViewById<android.widget.LinearLayout>(R.id.toolbar_pinned)
        
        toolbarContainer = root.findViewById(R.id.toolbar_container)
        
        tvSuggestion1 = root.findViewById(R.id.suggestion_1)
        tvSuggestion2 = root.findViewById(R.id.suggestion_2)
        tvSuggestion3 = root.findViewById(R.id.suggestion_3)
        suggestionDivider1 = root.findViewById(R.id.suggestion_divider_1)
        suggestionDivider2 = root.findViewById(R.id.suggestion_divider_2)
        suggestionPaste = root.findViewById(R.id.suggestion_paste)
        suggestionPasteDivider = root.findViewById(R.id.suggestion_paste_divider)
        tvSuggestionPasteText = root.findViewById(R.id.tv_suggestion_paste_text)
        btnSuggestionPasteClose = root.findViewById(R.id.btn_suggestion_paste_close)
        
        tvSuggestion1?.setOnClickListener {
            val typed = tvSuggestion1?.text.toString()
            if (typed.isNotEmpty()) {
                if (!isIncognitoActive()) logKeeper.log("INFO", "ViaboardService", "SLOT1_TAPPED | typed=$typed | action=kept")
                val inputConnection = currentInputConnection
                if (inputConnection != null) {
                    inputConnection.commitText(" ", 1)
                    commitWord(typed)
                    dictionaryFacilitator.addPersonalWord(typed)
                    clearSuggestions()
                }
            }
        }
        tvSuggestion2?.setOnClickListener {
            val suggestion = tvSuggestion2?.text.toString()
            if (suggestion.isNotEmpty()) {
                if (!isIncognitoActive()) {
                    if (isCurrentSuggestionsFuzzy) {
                        logKeeper.log("INFO", "ViaboardService", "FUZZY_CORRECTION_ACCEPTED | typed=$currentWord | accepted=$suggestion | slot=2")
                    } else {
                        logKeeper.log("INFO", "ViaboardService", "SUGGESTION_ACCEPTED | typed=$currentWord | accepted=$suggestion | slot=2")
                    }
                }
                onSuggestionClicked(suggestion)
            }
        }
        tvSuggestion3?.setOnClickListener {
            val suggestion = tvSuggestion3?.text.toString()
            if (suggestion.isNotEmpty()) {
                if (!isIncognitoActive()) {
                    if (isCurrentSuggestionsFuzzy) {
                        logKeeper.log("INFO", "ViaboardService", "FUZZY_CORRECTION_ACCEPTED | typed=$currentWord | accepted=$suggestion | slot=3")
                    } else {
                        logKeeper.log("INFO", "ViaboardService", "SUGGESTION_ACCEPTED | typed=$currentWord | accepted=$suggestion | slot=3")
                    }
                }
                onSuggestionClicked(suggestion)
            }
        }

        tvSuggestion1?.setOnLongClickListener { onSuggestionLongClicked(tvSuggestion1?.text.toString()); true }
        tvSuggestion2?.setOnLongClickListener { onSuggestionLongClicked(tvSuggestion2?.text.toString()); true }
        tvSuggestion3?.setOnLongClickListener { onSuggestionLongClicked(tvSuggestion3?.text.toString()); true }

        // When clicking the main paste chip, we commit the text directly.
        suggestionPaste?.setOnClickListener {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                currentInputConnection?.commitText(text, 1)
            }
            // Once pasted, dismiss it so it doesn't stay
            clipboardLastDismissedText = text
            updateSuggestions()
        }
        
        btnSuggestionPasteClose?.setOnClickListener {
            val text = clipboardManager?.primaryClip?.getItemAt(0)?.text?.toString()
            clipboardLastDismissedText = text
            updateSuggestions()
        }
        
        btnChevron.setOnClickListener {
            isToolbarExpanded = !isToolbarExpanded
            if (isToolbarExpanded) {
                btnChevron.setImageResource(R.drawable.ic_chevron_left)
                suggestionContent.visibility = View.GONE
                pinnedContent.visibility = View.GONE
                expandedScroll.visibility = View.VISIBLE
            } else {
                if (isIncognitoActive()) {
                    btnChevron.setImageResource(R.drawable.ic_incognito_on)
                } else {
                    btnChevron.setImageResource(R.drawable.ic_chevron_right)
                }
                suggestionContent.visibility = View.VISIBLE
                pinnedContent.visibility = View.VISIBLE
                expandedScroll.visibility = View.GONE
            }
        }
        
        populateToolbar(root, expandedContent, pinnedContent)
    }

    private fun populateToolbar(root: View, expandedContent: android.widget.LinearLayout, pinnedContent: android.widget.LinearLayout) {
        expandedContent.removeAllViews()
        pinnedContent.removeAllViews()
        
        val context = expandedContent.context
        val pinnedKeys = com.example.keyboard.toolbar.ToolbarSettingsManager.getPinnedKeys(context).toMutableList()
        val expandedKeys = com.example.keyboard.toolbar.ToolbarSettingsManager.getToolbarKeys(context).toMutableList()
        
        // Remove pinned keys from expanded keys so they don't duplicate
        expandedKeys.removeAll(pinnedKeys)
        
        btnIncognito = null // Reset ref
        
        val buttonSize = (36 * context.resources.displayMetrics.density).toInt()
        val marginEnd = 0
        
        fun createButton(actionId: String, isPinned: Boolean): android.widget.ImageButton? {
            val action = com.example.keyboard.toolbar.ToolbarSettingsManager.ALL_ACTIONS.find { it.id == actionId } ?: return null
            val btn = android.widget.ImageButton(context)
            val params = android.widget.LinearLayout.LayoutParams(buttonSize, buttonSize)
            params.marginEnd = marginEnd
            btn.layoutParams = params
            
            // Set simple transparent background
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
            btn.setBackgroundResource(typedValue.resourceId)
            btn.contentDescription = action.name
            
            // Map icon
            val resId = action.iconResId
            btn.setImageResource(resId)
            
            // Register specific references
            if (actionId == "INCOGNITO") {
                btnIncognito = btn
                if (isIncognitoActive()) {
                    btn.setImageResource(R.drawable.ic_incognito_on)
                } else {
                    btn.setImageResource(R.drawable.ic_incognito_off)
                }
            }
            
            btn.setOnClickListener { handleToolbarAction(actionId) }
            
            btn.setOnLongClickListener {
                when (actionId) {
                    "SELECT_WORD" -> {
                        handleToolbarAction("SELECT_ALL")
                        true
                    }
                    "PASTE" -> {
                        handleToolbarAction("CLIPBOARD")
                        true
                    }
                    "INCOGNITO" -> {
                        tempIncognitoHandler.removeCallbacks(tempIncognitoRunnable)
                        isManualIncognito = true
                        updateIncognitoStateUI()
                        currentWord.clear()
                        wordLengthBeforeCursor = 0
                        wordLengthAfterCursor = 0
                        previousWord = null
                        clearSuggestions()
                        android.widget.Toast.makeText(this@ViaboardService, "Incognito active for 20 seconds", android.widget.Toast.LENGTH_SHORT).show()
                        tempIncognitoHandler.postDelayed(tempIncognitoRunnable, 20000)
                        true
                    }
                    else -> false
                }
            }
            return btn
        }
        
        expandedKeys.forEach { actionId ->
            createButton(actionId, false)?.let { expandedContent.addView(it) }
        }
        
        pinnedKeys.forEach { actionId ->
            createButton(actionId, true)?.let { pinnedContent.addView(it) }
        }
    }
    
    private fun handleToolbarAction(actionId: String) {
        when (actionId) {
            "SETTINGS" -> {
                val intent = android.content.Intent(this, com.example.keyboard.SettingsActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            "SELECT_ALL" -> sendSelectAll()
            "PASTE" -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            "CLIPBOARD" -> toggleClipboardModal()
            "PROMPT_LIST" -> togglePromptModal()
            "ENTER" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
            "COPY" -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            "CUT" -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            "UNDO" -> sendUndo()
            "REDO" -> sendRedo()
            "LEFT" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "RIGHT" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "UP" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "DOWN" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "CLEAR_CLIP" -> {
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    clipboardRepository.deleteAllUnpinned()
                }
                android.widget.Toast.makeText(this, "Unpinned clipboard items cleared", android.widget.Toast.LENGTH_SHORT).show()
                if (isClipboardModalOpen) {
                    toggleClipboardModal()
                }
            }
            "SELECT_WORD" -> {
                val ic = currentInputConnection
                if (ic != null) {
                    val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    if (extracted != null && extracted.text != null) {
                        val pos = extracted.selectionStart
                        val text = extracted.text
                        var start = pos
                        var end = pos
                        while (start > 0 && text[start - 1].isLetterOrDigit()) {
                            start--
                        }
                        while (end < text.length && text[end].isLetterOrDigit()) {
                            end++
                        }
                        ic.setSelection(start, end)
                    }
                }
            }
            "VOICE_INPUT" -> {
                try {
                    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Voice input not supported", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            "EMOJI" -> {
                android.widget.Toast.makeText(this, "Emoji coming soon", android.widget.Toast.LENGTH_SHORT).show()
            }
            "INCOGNITO" -> {
                tempIncognitoHandler.removeCallbacks(tempIncognitoRunnable)
                isManualIncognito = !isManualIncognito
                updateIncognitoStateUI()
                currentWord.clear()
                wordLengthBeforeCursor = 0
                wordLengthAfterCursor = 0
                previousWord = null
                if (isIncognitoActive()) {
                    clearSuggestions()
                } else {
                    updateSuggestions()
                }
                val stateText = if (isIncognitoActive()) "Incognito Mode ON" else "Incognito Mode OFF"
                android.widget.Toast.makeText(this, stateText, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSuggestionClicked(word: String) {
        if (word.isBlank()) return
        val inputConnection = currentInputConnection ?: return
        
        val isCapitalized = currentWord.isNotEmpty() && currentWord[0].isUpperCase()
        val finalWord = if (isCapitalized) {
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
        } else {
            word
        }
        
        inputConnection.deleteSurroundingText(wordLengthBeforeCursor, wordLengthAfterCursor)
        inputConnection.commitText(finalWord + " ", 1)
        commitWord(word)
        wordLengthBeforeCursor = 0
        wordLengthAfterCursor = 0
    }

    private fun onSuggestionLongClicked(word: String) {
        if (word.isBlank()) return
        
        val context = mainView?.context ?: return
        val options = arrayOf("Remove from dictionary", "Add to Prompt List / Shortcuts")
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("Suggestion: $word")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    coroutineScope.launch {
                        if (::dictionaryFacilitator.isInitialized) {
                            dictionaryFacilitator.removeWord(word)
                        }
                        updateSuggestions()
                    }
                    android.widget.Toast.makeText(this@ViaboardService, "'$word' removed from dictionary", android.widget.Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    val promptItem = PersonalDictionaryItem(word = word, frequency = 250)
                    showEditShortcutDialog(promptItem)
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        
        val dialog = builder.create()
        val window = dialog.window
        if (window != null) {
            val params = window.attributes
            params.token = mainView?.windowToken
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
    }

    private fun commitWord(word: String) {
        val finalWord = word.lowercase()
        val prevToSave = previousWord
        val prevPrevToSave = prevPrevWord
        if (!isIncognitoActive() && ::dictionaryFacilitator.isInitialized) {
            dictionaryFacilitator.recordWordUsage(finalWord, prevToSave)
        }
        prevPrevWord = previousWord
        previousWord = finalWord
        currentWord.clear()
        wordLengthBeforeCursor = 0
        wordLengthAfterCursor = 0
        
        updateSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Refresh settings
        val prefs = getSharedPreferences("keyboard_prefs", android.content.Context.MODE_PRIVATE)
        val autocorrectAggressiveness = prefs.getFloat("autocorrect_aggressiveness", 1.0f)
        isAutocorrectEnabled = autocorrectAggressiveness > 0.2f

        // Refresh Toolbar configuration to catch Settings changes
        mainView?.let { root ->
            val expandedContent = root.findViewById<android.widget.LinearLayout>(R.id.toolbar_expanded_content)
            val pinnedContent = root.findViewById<android.widget.LinearLayout>(R.id.toolbar_pinned)
            if (expandedContent != null && pinnedContent != null) {
                populateToolbar(root, expandedContent, pinnedContent)
            }
        }

        val clipboardContainer = mainView?.findViewById<android.view.View>(R.id.clipboard_container)
        val promptContainer = mainView?.findViewById<android.view.View>(R.id.prompt_container)
        val emojiContainer = mainView?.findViewById<android.view.View>(R.id.emoji_container)
        val keyboardView = mainView?.findViewById<com.example.keyboard.KeyboardView>(R.id.keyboard_view)
        clipboardContainer?.visibility = android.view.View.GONE
        promptContainer?.visibility = android.view.View.GONE
        emojiContainer?.visibility = android.view.View.GONE
        keyboardView?.visibility = android.view.View.VISIBLE
        isClipboardModalOpen = false
        isPromptModalOpen = false
        isEmojiModalOpen = false

        // Select initial layout based on input type
        val inputType = info?.inputType ?: android.text.InputType.TYPE_CLASS_TEXT
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isUri = (info != null) && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        )
        val isEmail = (info != null) && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        val imeAction = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION

        keyboardView?.isUriField = isUri
        keyboardView?.isEmailField = isEmail
        keyboardView?.enterAction = imeAction

        when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_DATETIME,
            android.text.InputType.TYPE_CLASS_PHONE -> {
                switchKeyboardLayout(R.xml.kbd_numpad)
            }
            else -> {
                switchKeyboardLayout(R.xml.kbd_qwerty)
            }
        }

        currentWord.clear()
        wordLengthBeforeCursor = 0
        wordLengthAfterCursor = 0
        previousWord = null
        clearSuggestions()
        updateIncognitoStateUI()
    }

    private var wordLengthBeforeCursor = 0
    private var wordLengthAfterCursor = 0

    private fun extractWordAtCursor() {
        val ic = currentInputConnection ?: return
        
        if (isIncognitoActive()) {
            currentWord.clear()
            wordLengthBeforeCursor = 0
            wordLengthAfterCursor = 0
            clearSuggestions()
            return
        }

        val textBefore = ic.getTextBeforeCursor(50, 0) ?: ""
        val textAfter = ic.getTextAfterCursor(50, 0) ?: ""
        
        var beforeIndex = textBefore.length - 1
        while (beforeIndex >= 0 && textBefore[beforeIndex].isLetter()) {
            beforeIndex--
        }
        val wordBefore = textBefore.substring(beforeIndex + 1)
        
        var afterIndex = 0
        while (afterIndex < textAfter.length && textAfter[afterIndex].isLetter()) {
            afterIndex++
        }
        val wordAfter = textAfter.substring(0, afterIndex)
        
        val fullWord = wordBefore + wordAfter
        
        if (fullWord.isNotEmpty()) {
            val hasChanged = fullWord != currentWord.toString()
            currentWord.clear()
            currentWord.append(fullWord)
            wordLengthBeforeCursor = wordBefore.length
            wordLengthAfterCursor = wordAfter.length
            if (hasChanged) {
                updateSuggestions()
            }
        } else {
            if (currentWord.isNotEmpty()) {
                currentWord.clear()
                wordLengthBeforeCursor = 0
                wordLengthAfterCursor = 0
                clearSuggestions()
            }
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateShiftState()
        
        if (newSelStart != newSelEnd) {
            currentWord.clear()
            wordLengthBeforeCursor = 0
            wordLengthAfterCursor = 0
            clearSuggestions()
            isShowingFuzzyAfterSpace = false
            return
        }
        
        if (isShowingFuzzyAfterSpace) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(1, 0) ?: ""
            if (textBefore == " ") {
                // still right after the space, keep fuzzy suggestions
                return
            }
            isShowingFuzzyAfterSpace = false
        }
        
        extractWordAtCursor()
    }

    private fun isIncognitoActive(): Boolean {
        return isManualIncognito || isSensitiveField(currentInputEditorInfo)
    }

    private fun isSensitiveField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val classType = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        
        // Password types check
        if (classType == android.text.InputType.TYPE_CLASS_NUMBER && 
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return true
        }
        if (classType == android.text.InputType.TYPE_CLASS_TEXT && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) {
            return true
        }
        
        // Check personalized learning flag in imeOptions (IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000)
        if (info.imeOptions and 0x1000000 != 0) {
            return true
        }
        
        // Check standard sensitive classifications like email
        if (classType == android.text.InputType.TYPE_CLASS_TEXT && (
            variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )) {
            return true
        }
        
        return false
    }

    private fun updateIncognitoStateUI() {
        val active = isIncognitoActive()
        
        // Update the toggle button icon in toolbar
        btnIncognito?.setImageResource(if (active) R.drawable.ic_incognito_on else R.drawable.ic_incognito_off)
        
        // Update chevron icon to incognito icon when active and collapsed (HeliBoard style)
        val btnChevron = mainView?.findViewById<android.widget.ImageButton>(R.id.btn_toolbar_chevron)
        if (isToolbarExpanded) {
            btnChevron?.setImageResource(R.drawable.ic_chevron_left)
        } else {
            if (active) {
                btnChevron?.setImageResource(R.drawable.ic_incognito_on)
            } else {
                btnChevron?.setImageResource(R.drawable.ic_chevron_right)
            }
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = mainView ?: return
        outInsets.contentTopInsets = view.top
        outInsets.visibleTopInsets = view.top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
    }

    override fun onDestroy() {
        tempIncognitoHandler.removeCallbacks(tempIncognitoRunnable)
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        coroutineScope.launch {
             // cancel all in scope
        } // or just let it die. It's tied to service lifecycle.
        logKeeper.log("INFO", "ViaboardService", "Service Destroyed")
    }
    
    private sealed class AutocorrectDecision {
        object None : AutocorrectDecision()
        data class Correct(val word: String, val editDistance: Int, val source: String) : AutocorrectDecision()
        data class Suggest(val words: List<String>, val source: String) : AutocorrectDecision()
    }

    private fun decideAutocorrect(typed: String, aggressiveness: Float): AutocorrectDecision {
        if (typed.isEmpty() || !::suggestEngine.isInitialized) return AutocorrectDecision.None
        if (dictionaryFacilitator.isValidWord(typed.lowercase())) return AutocorrectDecision.None

        val suggestedWords = suggestEngine.getSuggestedWords(typed, previousWord, prevPrevWord, 3)
        if (suggestedWords.hasAutoCorrectionCandidate) {
            val top = suggestedWords.autoCorrectWord
            if (top != null && !top.equals(typed, ignoreCase = true)) {
                return AutocorrectDecision.Correct(top, 1, "heliboard")
            }
        }

        val topCandidates = suggestedWords.suggestionsList.map { it.word }
        if (topCandidates.isNotEmpty() && !topCandidates.first().equals(typed, ignoreCase = true)) {
            return AutocorrectDecision.Suggest(topCandidates, "heliboard")
        }

        return AutocorrectDecision.None
    }

    private fun performAutocorrectUndo(inputConnection: android.view.inputmethod.InputConnection) {
        val deleteCount = lastCorrectedWord.length + 1
        inputConnection.deleteSurroundingText(deleteCount, 0)
        inputConnection.commitText(lastAutocorrectedWord, 1)
        if (!isIncognitoActive()) logKeeper.log("INFO", "ViaboardService", "AUTOCORRECT_UNDONE | original=$lastAutocorrectedWord | was_corrected_to=$lastCorrectedWord")
        currentWord.clear()
        currentWord.append(lastAutocorrectedWord)
        wordLengthBeforeCursor = lastAutocorrectedWord.length
        didAutocorrect = false
        lastAutocorrectedWord = ""
        lastCorrectedWord = ""
        updateSuggestions()
    }

    private fun updateSuggestions() {
        if (!::dictionaryFacilitator.isInitialized || !dictionaryFacilitator.isReady) return

        suggestionGeneration++
        val myGeneration = suggestionGeneration
        suggestionJob?.cancel()

        val prefix = currentWord.toString()
        val typedLower = prefix.lowercase()
        
        var showPaste = false
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0 && prefix.isEmpty()) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty() && text != clipboardLastDismissedText) {
                if (text != clipboardLastObservedText) {
                    clipboardLastObservedText = text
                    clipboardObservedTime = System.currentTimeMillis()
                }
                val age = System.currentTimeMillis() - clipboardObservedTime
                val isSensitive = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && 
                    clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) || isIncognitoActive()
                val maxAge = if (isSensitive) 60_000L else 300_000L

                if (age < maxAge) {
                    showPaste = true
                    val trimmed = text.trim()
                    val isOtp = trimmed.matches(Regex("^\\d{4,8}$")) || 
                                trimmed.contains(Regex("(?i)\\b(code|otp|passcode)\\s*[:=-]?\\s*\\d{4,8}\\b"))
                    val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://") || 
                                trimmed.matches(Regex("(?i)^[a-z0-9-]+(\\.[a-z]{2,})+(/.*)?$"))

                    val displayText = when {
                        isOtp -> "🔢 OTP: $trimmed"
                        isUrl -> "🔗 $trimmed"
                        else -> trimmed.replace("\n", " ")
                    }
                    tvSuggestionPasteText?.text = displayText
                }
            }
        }
        
        if (showPaste) {
            suggestionPaste?.visibility = View.VISIBLE
        } else {
            suggestionPaste?.visibility = View.GONE
            suggestionPasteDivider?.visibility = View.GONE
        }
        
        suggestionJob = coroutineScope.launch {
            val suggestedWords = suggestEngine.getSuggestedWords(prefix, previousWord, prevPrevWord, 5)

            // Staleness guard
            if (myGeneration != suggestionGeneration) return@launch

            val suggestionsList = suggestedWords.suggestionsList
            currentSuggestions = suggestionsList.map { it.word }
            isCurrentSuggestionsFuzzy = suggestedWords.hasAutoCorrectionCandidate

            val isIncognito = isIncognitoActive()
            val textColorPrimary = if (isIncognito) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#111111")
            val textColorSecondary = if (isIncognito) android.graphics.Color.parseColor("#AAAAAA") else android.graphics.Color.parseColor("#666666")
            val textColorCandidate = if (isIncognito) android.graphics.Color.parseColor("#80D8FF") else android.graphics.Color.parseColor("#000000")

            if (prefix.isBlank()) {
                tvSuggestion1?.visibility = View.GONE
                suggestionDivider1?.visibility = View.GONE
                suggestionDivider2?.visibility = View.GONE
                if (showPaste) suggestionPasteDivider?.visibility = View.GONE
                
                if (suggestionsList.isNotEmpty()) {
                    tvSuggestion2?.text = suggestionsList.getOrNull(0)?.word ?: ""
                    tvSuggestion2?.visibility = View.VISIBLE
                    tvSuggestion2?.setTextColor(textColorPrimary)
                    
                    tvSuggestion3?.text = suggestionsList.getOrNull(1)?.word ?: ""
                    tvSuggestion3?.visibility = if (suggestionsList.size > 1) View.VISIBLE else View.GONE
                    tvSuggestion3?.setTextColor(textColorPrimary)
                } else {
                    tvSuggestion2?.visibility = View.GONE
                    tvSuggestion3?.visibility = View.GONE
                }
            } else {
                tvSuggestion1?.text = prefix
                tvSuggestion1?.visibility = View.VISIBLE
                tvSuggestion1?.setTextColor(textColorSecondary)
                
                val bestCandidate = suggestionsList.firstOrNull()?.word ?: prefix
                val secondCandidate = suggestionsList.drop(1).firstOrNull { it.word.lowercase() != bestCandidate.lowercase() }?.word
                
                tvSuggestion2?.text = bestCandidate
                tvSuggestion2?.visibility = View.VISIBLE
                
                if (suggestedWords.hasAutoCorrectionCandidate) {
                    tvSuggestion2?.setTypeface(null, android.graphics.Typeface.BOLD)
                    tvSuggestion2?.setTextColor(textColorCandidate)
                } else {
                    tvSuggestion2?.setTypeface(null, android.graphics.Typeface.NORMAL)
                    tvSuggestion2?.setTextColor(textColorPrimary)
                }
                
                if (secondCandidate != null) {
                    tvSuggestion3?.text = secondCandidate
                    tvSuggestion3?.visibility = View.VISIBLE
                    tvSuggestion3?.setTextColor(textColorPrimary)
                } else {
                    tvSuggestion3?.visibility = View.GONE
                }

                suggestionDivider1?.visibility = View.VISIBLE
                suggestionDivider2?.visibility = if (secondCandidate != null) View.VISIBLE else View.GONE
                if (showPaste) suggestionPasteDivider?.visibility = View.VISIBLE
            }

            if (!isIncognitoActive()) {
                if (suggestionsList.isEmpty()) {
                    logKeeper.log("INFO", "ViaboardService", "NO_SUGGESTION | typed=$prefix | prefix_length=${prefix.length}")
                } else {
                    logKeeper.log("INFO", "ViaboardService", "SUGGESTION_SHOWN | typed=$prefix | slot1=$prefix | slot2=${tvSuggestion2?.text} | slot3=${tvSuggestion3?.text}")
                }
            }
        }
    }

    private fun clearSuggestions() {
        suggestionJob?.cancel()
        currentSuggestions = emptyList()
        tvSuggestion1?.text = ""
        tvSuggestion1?.visibility = View.GONE
        tvSuggestion2?.text = ""
        tvSuggestion2?.visibility = View.GONE
        tvSuggestion3?.text = ""
        tvSuggestion3?.visibility = View.GONE
        suggestionDivider1?.visibility = View.GONE
        suggestionDivider2?.visibility = View.GONE
    }

    private fun updateShiftState(force: ShiftState? = null) {
        if (force != null) {
            shiftState = force
        } else {
            if (shiftState == ShiftState.CAPS_LOCK) return
            shiftState = if (shouldAutoCapitalize()) ShiftState.UPPERCASE else ShiftState.LOWERCASE
        }
        
        val kv = mainView?.findViewById<com.example.keyboard.KeyboardView>(R.id.keyboard_view) ?: return
        val kbd = kv.getKeyboard() ?: return
        
        for (row in kbd.rows) {
            for (key in row.keys) {
                if (key.codes.length == 1 && key.codes[0].isLetter()) {
                    key.label = if (shiftState == ShiftState.LOWERCASE) key.codes.lowercase() else key.codes.uppercase()
                } else if (key.codes == "SHIFT") {
                    key.label = when (shiftState) {
                        ShiftState.LOWERCASE -> "⇧"
                        ShiftState.UPPERCASE -> "⬆"
                        ShiftState.CAPS_LOCK -> "⇪"
                    }
                }
            }
        }
        kv.invalidate()
    }

    private fun shouldAutoCapitalize(): Boolean {
        val ic = currentInputConnection ?: return false
        val currentInfo = currentInputEditorInfo
        if (currentInfo != null) {
            val type = currentInfo.inputType
            if (type and android.text.InputType.TYPE_CLASS_TEXT != 0) {
                val capsMode = ic.getCursorCapsMode(type)
                if (capsMode and android.text.TextUtils.CAP_MODE_SENTENCES != 0 ||
                    capsMode and android.text.TextUtils.CAP_MODE_WORDS != 0 ||
                    capsMode and android.text.TextUtils.CAP_MODE_CHARACTERS != 0) {
                    return true
                }
            }
        }
        val beforeCursor = ic.getTextBeforeCursor(3, 0) ?: return true
        if (beforeCursor.isEmpty()) return true
        val text = beforeCursor.toString()
        return text.endsWith(". ") || text.endsWith("! ") || text.endsWith("? ") || text.endsWith("\n")
    }

    override fun onKeyPress(key: String) {
        val inputConnection = currentInputConnection ?: return
        
        if (key != "DEL" && key != "DSK_BKSP" && key != "SPACE") {
            didAutocorrect = false
            lastAutocorrectedWord = ""
            lastCorrectedWord = ""
        }
        
        when (key) {
            "DEL" -> {
                if (didAutocorrect && lastAutocorrectedWord.isNotEmpty()) {
                    performAutocorrectUndo(inputConnection)
                    return
                }
                
                val selectedText = inputConnection.getSelectedText(0)
                if (selectedText != null && selectedText.isNotEmpty()) {
                    inputConnection.commitText("", 1)
                    currentWord.clear()
                    wordLengthBeforeCursor = 0
                    wordLengthAfterCursor = 0
                    clearSuggestions()
                } else {
                    sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                    if (currentWord.isNotEmpty() && wordLengthBeforeCursor > 0 && wordLengthBeforeCursor <= currentWord.length) {
                        currentWord.deleteCharAt(wordLengthBeforeCursor - 1)
                        wordLengthBeforeCursor--
                        updateSuggestions()
                    }
                }
                updateShiftState()
            }
            "SPACE" -> {
                didAutocorrect = false
                val originalTyped = currentWord.toString()
                val now = System.currentTimeMillis()
                val textBeforeCursor = inputConnection.getTextBeforeCursor(2, 0) ?: ""
                
                if (now - lastSpaceTime < 500 && currentWord.isEmpty() && textBeforeCursor.endsWith(" ")) {
                    // Double space detected! Replace previous space with period and space
                    inputConnection.deleteSurroundingText(1, 0)
                    inputConnection.commitText(". ", 1)
                    lastSpaceTime = 0L
                    updateShiftState()
                    return
                }

                val prefs = getSharedPreferences("keyboard_prefs", android.content.Context.MODE_PRIVATE)
                val autocorrectAggressiveness = prefs.getFloat("autocorrect_aggressiveness", 1.0f)
                val isIncognito = isIncognitoActive()

                if (autocorrectAggressiveness > 0.2f && currentWord.isNotEmpty()) {
                    val originalTyped = currentWord.toString()
                    when (val decision = decideAutocorrect(originalTyped, autocorrectAggressiveness)) {
                        is AutocorrectDecision.Correct -> {
                            val corrected = if (originalTyped[0].isUpperCase()) {
                                decision.word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                            } else decision.word
                            inputConnection.deleteSurroundingText(wordLengthBeforeCursor, wordLengthAfterCursor)
                            inputConnection.commitText(corrected + " ", 1)
                            if (!isIncognito) logKeeper.log("INFO", "ViaboardService", "AUTOCORRECT_FIRED | typed=$originalTyped | corrected_to=$corrected | edit_distance=${decision.editDistance} | source=${decision.source}")
                            commitWord(decision.word)
                            lastSpaceTime = now
                            wordLengthBeforeCursor = 0
                            wordLengthAfterCursor = 0
                            updateShiftState()
                            didAutocorrect = true
                            lastAutocorrectedWord = originalTyped
                            lastCorrectedWord = corrected
                            return
                        }
                        is AutocorrectDecision.Suggest -> {
                            suggestionGeneration++
                            suggestionJob?.cancel()
                            currentSuggestions = decision.words
                            isCurrentSuggestionsFuzzy = decision.source == "fuzzy"
                            tvSuggestion1?.visibility = View.GONE
                            tvSuggestion2?.text = decision.words.getOrNull(0) ?: ""
                            tvSuggestion2?.visibility = View.VISIBLE
                            tvSuggestion3?.text = decision.words.getOrNull(1) ?: ""
                            tvSuggestion3?.visibility = if (decision.words.size > 1) View.VISIBLE else View.GONE
                            if (!isIncognito) logKeeper.log("INFO", "ViaboardService", "FUZZY_CORRECTION_SHOWN | typed=$originalTyped | suggestion=${decision.words[0]} | source=${decision.source}")
                            
                            inputConnection.commitText(" ", 1)
                            wordLengthBeforeCursor += 1
                            isShowingFuzzyAfterSpace = true
                            lastSpaceTime = now
                            updateShiftState()
                            lastAutocorrectedWord = ""
                            lastCorrectedWord = ""
                            return
                        }
                        AutocorrectDecision.None -> {
                            if (!isIncognito) logKeeper.log("INFO", "ViaboardService", "AUTOCORRECT_BLOCKED | typed=$originalTyped | reason=no_confident_correction")
                        }
                    }
                }
                
                inputConnection.commitText(" ", 1)
                if (currentWord.isNotEmpty()) {
                    commitWord(currentWord.toString())
                }
                lastSpaceTime = now
                updateShiftState()
                lastAutocorrectedWord = ""
                lastCorrectedWord = ""
            }
            "ENTER" -> handleEnterAction()
            "SHIFT" -> {
                when (shiftState) {
                    ShiftState.LOWERCASE -> updateShiftState(ShiftState.UPPERCASE)
                    ShiftState.UPPERCASE -> updateShiftState(ShiftState.LOWERCASE)
                    ShiftState.CAPS_LOCK -> updateShiftState(ShiftState.LOWERCASE)
                }
            }
            "MODE_SYMBOLS" -> switchKeyboardLayout(R.xml.kbd_symbols)
            "MODE_SYMBOLS_SHIFT" -> switchKeyboardLayout(R.xml.kbd_symbols_shift)
            "MODE_ALPHABET" -> switchKeyboardLayout(R.xml.kbd_qwerty)
            "MODE_NAVIGATION" -> switchKeyboardLayout(R.xml.kbd_navigation)
            "MODE_DESKTOP" -> switchToDesktopView()
            "MODE_NUMPAD" -> switchKeyboardLayout(R.xml.kbd_numpad_extended)
            "MODE_EMOJI" -> {
                isEmojiModalOpen = true
                toggleEmojiModal(true)
            }
            "SETTINGS" -> {
                val intent = android.content.Intent(this, SettingsActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            "ONE_HAND" -> { /* TODO */ }
            "CLIPBOARD" -> toggleClipboardModal()
            "PROMPT_LIST" -> togglePromptModal()
            "SELECT_ALL" -> sendSelectAll()
            "COPY" -> inputConnection.performContextMenuAction(android.R.id.copy)
            "PASTE" -> inputConnection.performContextMenuAction(android.R.id.paste)
            "CUT" -> inputConnection.performContextMenuAction(android.R.id.cut)
            "UNDO" -> sendUndo()
            "REDO" -> sendRedo()
            "DIR_UP" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "DIR_DOWN" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "DIR_LEFT" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "DIR_RIGHT" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "TAB" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_TAB)
            "ESC" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ESCAPE)
            "HOME" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_MOVE_HOME)
            "END" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_MOVE_END)
            "PGUP" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_PAGE_UP)
            "PGDN" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_PAGE_DOWN)
            "DSK_ESC" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ESCAPE)
            "DSK_TAB", "DSK_INDENT" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_TAB)
            "DSK_STABF", "DSK_DEDENT" -> sendShiftKey(android.view.KeyEvent.KEYCODE_TAB)
            "DSK_UP" -> sendDesktopArrow(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "DSK_DOWN" -> sendDesktopArrow(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "DSK_LEFT" -> sendDesktopArrow(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "DSK_RIGHT" -> sendDesktopArrow(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "DSK_SEL_UP" -> sendShiftKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
            "DSK_SEL_DOWN" -> sendShiftKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            "DSK_SEL_LEFT" -> sendShiftKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            "DSK_SEL_RIGHT" -> sendShiftKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "DSK_PGUP" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_PAGE_UP)
            "DSK_PGDN" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_PAGE_DOWN)
            "DSK_HOME" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_MOVE_HOME)
            "DSK_END" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_MOVE_END)
            "DSK_F1" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_F1)
            "DSK_BKSP" -> {
                if (didAutocorrect && lastAutocorrectedWord.isNotEmpty()) {
                    performAutocorrectUndo(inputConnection)
                    return
                }
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                checkAndDisarmDesktopSelectMode()
            }
            "DSK_DEL" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_FORWARD_DEL)
            "DSK_ENTER" -> sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
            "DSK_UNDO" -> sendUndo()
            "DSK_REDO" -> sendRedo()
            "DSK_COPY" -> {
                sendCtrlKey(android.view.KeyEvent.KEYCODE_C)
                checkAndDisarmDesktopSelectMode()
            }
            "DSK_CUT" -> {
                sendCtrlKey(android.view.KeyEvent.KEYCODE_X)
                checkAndDisarmDesktopSelectMode()
            }
            "DSK_PASTE" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_V)
            "DSK_SELALL" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_A)
            "DSK_SAVE" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_S)
            "DSK_FINDREPLACE" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_H)
            "DSK_DUPLINE" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_D)
            "DSK_COMMENT", "DSK_COMMENT2" -> sendCtrlSlash()
            "DSK_GOTOLINE", "DSK_GOTOLINE2" -> sendCtrlKey(android.view.KeyEvent.KEYCODE_G)
            "DSK_DELLINE" -> sendCtrlShiftKey(android.view.KeyEvent.KEYCODE_K)
            "DSK_SELWORD" -> sendCtrlShiftKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            "DSK_SEL" -> toggleDesktopSelectMode()
            else -> {
                var finalKey = key
                if (key.length == 1 && key[0].isLetter()) {
                    finalKey = if (shiftState == ShiftState.LOWERCASE) key.lowercase() else key.uppercase()
                }
                
                val handledSmart = applySmartTypingRules(finalKey, inputConnection)
                if (!handledSmart) {
                    inputConnection.commitText(finalKey, 1)
                }
                
                if (shiftState == ShiftState.UPPERCASE && finalKey.length == 1 && finalKey[0].isLetter()) {
                    updateShiftState(ShiftState.LOWERCASE)
                }
                
                if (!handledSmart && finalKey.length == 1 && finalKey[0].isLetter()) {
                    val insertIndex = wordLengthBeforeCursor.coerceIn(0, currentWord.length)
                    currentWord.insert(insertIndex, finalKey)
                    wordLengthBeforeCursor = insertIndex + finalKey.length
                    updateSuggestions()
                } else {
                    currentWord.clear()
                    wordLengthBeforeCursor = 0
                    wordLengthAfterCursor = 0
                    clearSuggestions()
                }
            }
        }
    }

    private fun applySmartTypingRules(key: String, ic: android.view.inputmethod.InputConnection): Boolean {
        if (key.isEmpty()) return false

        // 1. Punctuation Space Snapping (e.g. "word ." -> "word. ")
        if (key in listOf(".", ",", "!", "?", ":", ";")) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.endsWith(" ") && before.length > 1 && !before[before.length - 2].isWhitespace()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(key + " ", 1)
                return true
            }
        }

        // 2. Smart Multiply:
        // Case A: User types '*' after a digit -> '×'
        if (key == "*") {
            val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before.isNotEmpty() && before.last().isDigit()) {
                ic.commitText("×", 1)
                return true
            }
        }
        // Case B: User types a digit following '\d[xX]' (e.g. '5x' + '5' -> '5×5')
        if (key.length == 1 && key[0].isDigit()) {
            val before2 = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before2.length == 2 && before2[0].isDigit() && (before2[1] == 'x' || before2[1] == 'X')) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("×$key", 1)
                return true
            }
        }

        // 3. Smart Fractions: typing denominator digit after '1/', '2/', '3/', '5/', '7/'
        if (key.length == 1 && key[0].isDigit()) {
            val before2 = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            val fraction = when {
                before2 == "1/" && key == "2" -> "½"
                before2 == "1/" && key == "4" -> "¼"
                before2 == "3/" && key == "4" -> "¾"
                before2 == "1/" && key == "3" -> "⅓"
                before2 == "2/" && key == "3" -> "⅔"
                before2 == "1/" && key == "8" -> "⅛"
                before2 == "3/" && key == "8" -> "⅜"
                before2 == "5/" && key == "8" -> "⅝"
                before2 == "7/" && key == "8" -> "⅞"
                else -> null
            }
            if (fraction != null) {
                ic.deleteSurroundingText(2, 0)
                ic.commitText(fraction, 1)
                return true
            }
        }

        // 4. Smart Dashes:
        // Case A: Double hyphen '--' -> em-dash '—'
        if (key == "-") {
            val before1 = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before1 == "-") {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("—", 1)
                return true
            }
        }
        // Case B: Number range en-dash (e.g. '2020-' + '2' -> '2020–2')
        if (key.length == 1 && key[0].isDigit()) {
            val before2 = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before2.length == 2 && before2[0].isDigit() && before2[1] == '-') {
                ic.deleteSurroundingText(1, 0)
                ic.commitText("–$key", 1)
                return true
            }
        }

        // 5. Smart Typographic Quotes:
        if (key == "\"" || key == "“" || key == "”") {
            val before1 = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            val isOpening = before1.isEmpty() || before1.last().isWhitespace() || before1.last() in "([{<\"'\n\r\t"
            val quote = if (isOpening) "“" else "”"
            ic.commitText(quote, 1)
            return true
        }
        if (key == "'" || key == "‘" || key == "’") {
            val before1 = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            val isOpening = before1.isEmpty() || before1.last().isWhitespace() || before1.last() in "([{<\"'\n\r\t"
            val quote = if (isOpening) "‘" else "’"
            ic.commitText(quote, 1)
            return true
        }

        return false
    }

    override fun onLongPressKey(key: String, keyRect: android.graphics.RectF, keyboardView: View) {
        if (key == "SHIFT") {
            updateShiftState(ShiftState.CAPS_LOCK)
        }
        // Accents are handled completely in inline KeyboardView logic now.
    }

    override fun onLongPressBackspace() {
        val inputConnection = currentInputConnection ?: return
        val textBefore = inputConnection.getTextBeforeCursor(1000, 0) ?: return
        if (textBefore.isEmpty()) return

        val newlineIndex = textBefore.lastIndexOf('\n')
        val deleteCount = if (newlineIndex == -1) {
            textBefore.length
        } else if (newlineIndex == textBefore.length - 1) {
            1
        } else {
            textBefore.length - newlineIndex - 1
        }

        if (deleteCount > 0) {
            inputConnection.deleteSurroundingText(deleteCount, 0)
            val deleteFromWord = minOf(deleteCount, wordLengthBeforeCursor)
            val startIndex = maxOf(0, wordLengthBeforeCursor - deleteFromWord)
            val endIndex = minOf(currentWord.length, wordLengthBeforeCursor)
            if (startIndex < endIndex) currentWord.delete(startIndex, endIndex)
            wordLengthBeforeCursor = startIndex
            updateSuggestions()
        }
    }

    override fun onSwipeCursor(dx: Int) {
        val count = kotlin.math.abs(dx)
        for (i in 0 until count) {
            if (dx > 0) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            } else if (dx < 0) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            }
        }
    }

    override fun onSwipeDelete(deleteCount: Int) {
        val inputConnection = currentInputConnection ?: return
        if (deleteCount > 0) {
            inputConnection.deleteSurroundingText(deleteCount, 0)
            if (currentWord.isNotEmpty()) {
                val deleteFromWord = kotlin.math.min(deleteCount, wordLengthBeforeCursor)
                val startIndex = kotlin.math.max(0, wordLengthBeforeCursor - deleteFromWord)
                val endIndex = kotlin.math.min(currentWord.length, wordLengthBeforeCursor)
                if (startIndex < endIndex) {
                    currentWord.delete(startIndex, endIndex)
                }
                wordLengthBeforeCursor = startIndex
                updateSuggestions()
            }
        }
    }

    private fun handleEnterAction() {
        val ic = currentInputConnection ?: return
        val imeOptions = currentInputEditorInfo?.imeOptions ?: 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_ENTER)
        }
        
        if (currentWord.isNotEmpty()) {
            commitWord(currentWord.toString())
        }
        previousWord = null
        updateSuggestions()
        updateShiftState()
    }

    private fun sendUndo() {
        val ic = currentInputConnection
        ic?.performContextMenuAction(android.R.id.undo)
        
        // Desktop Shortcut Undo (Ctrl+Z)
        val metaState = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(
            eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_Z, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        val upEvent = android.view.KeyEvent(
            eventTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_Z, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        ic?.sendKeyEvent(downEvent)
        ic?.sendKeyEvent(upEvent)
    }

    private fun sendRedo() {
        val ic = currentInputConnection
        ic?.performContextMenuAction(android.R.id.redo)
        
        // Desktop Shortcut Redo (Ctrl+Y / Ctrl+Shift+Z, let's use Ctrl+Shift+Z)
        val metaState = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(
            eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_Z, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        val upEvent = android.view.KeyEvent(
            eventTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_Z, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        ic?.sendKeyEvent(downEvent)
        ic?.sendKeyEvent(upEvent)
    }

    private fun sendSelectAll() {
        val ic = currentInputConnection
        ic?.performContextMenuAction(android.R.id.selectAll)
        
        // Desktop Shortcut Select All (Ctrl+A)
        val metaState = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(
            eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_A, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        val upEvent = android.view.KeyEvent(
            eventTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_A, 0, metaState,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_SOFT_KEYBOARD or android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE
        )
        ic?.sendKeyEvent(downEvent)
        ic?.sendKeyEvent(upEvent)
    }

    private fun sendCtrlKey(keyCode: Int) {
        val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
    }

    private fun sendShiftKey(keyCode: Int) {
        val meta = android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
    }

    private fun sendCtrlShiftKey(keyCode: Int) {
        val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON or android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
    }

    private fun sendCtrlSlash() {
        val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SLASH, 0, meta))
            ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_SLASH, 0, meta))
        }
    }

    private var isDesktopSelectMode = false

    private fun toggleDesktopSelectMode() {
        isDesktopSelectMode = !isDesktopSelectMode
        val root = mainView ?: return
        val keyboardView = root.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
        keyboardView.setDesktopSelectMode(isDesktopSelectMode)
    }

    private fun checkAndDisarmDesktopSelectMode() {
        if (isDesktopSelectMode) {
            isDesktopSelectMode = false
            val root = mainView ?: return
            val keyboardView = root.findViewById<KeyboardView>(R.id.keyboard_view) ?: return
            keyboardView.setDesktopSelectMode(false)
        }
    }

    private fun sendDesktopArrow(keyCode: Int) {
        if (isDesktopSelectMode) {
            val meta = android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
            currentInputConnection?.let { ic ->
                ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta))
                ic.sendKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta))
            }
        } else {
            sendDownUpKeyEvents(keyCode)
        }
    }

    override fun onLongPressEnter() {
        logKeeper.log("USER_ACTION", "ViaboardService", "Triggering Log Keeper via Long-Press Enter")
        logKeeper.exportLogsToDownloads()
        android.widget.Toast.makeText(this, "Exporting Logs...", android.widget.Toast.LENGTH_SHORT).show()
    }
}
