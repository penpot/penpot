(ns frontend-tests.ui.settings-shortcuts-test
  (:require
   [app.config :as cf]
   [app.main.data.profile :as du]
   [app.main.ui.settings.import-shortcuts-diff-modal :as diff-modal]
   [app.main.ui.settings.restore-shortcuts-modal :as restore-modal]
   [app.main.ui.settings.shortcuts :as sut]
   [app.main.ui.shortcuts :as ui-shortcuts]
   [app.util.strings :refer [matches-search]]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(t/deftest validate-imported-shortcuts-accepts-valid-workspace
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:workspace {:escape "escape"
                         :increase-zoom "+"
                         :zoom-lense-decrease "alt+z"}}))))

(t/deftest validate-imported-shortcuts-accepts-optional-contexts
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:workspace {:escape "escape"}
             :dashboard {:toggle-theme "alt+m"}
             :viewer {:next-frame "right"}}))))

(t/deftest validate-imported-shortcuts-accepts-empty-string-command
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:workspace {:escape ""}}))))

(t/deftest validate-imported-shortcuts-rejects-nil
  (let [result (sut/validate-imported-shortcuts nil)]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-non-map
  (t/is (= false (:valid? (sut/validate-imported-shortcuts "shortcuts"))))
  (t/is (= false (:valid? (sut/validate-imported-shortcuts 42))))
  (t/is (= false (:valid? (sut/validate-imported-shortcuts []))))
  (t/is (= false (:valid? (sut/validate-imported-shortcuts true)))))

(t/deftest validate-imported-shortcuts-accepts-missing-workspace
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts {:dashboard {:toggle-theme "alt+m"}}))))

(t/deftest validate-imported-shortcuts-rejects-non-map-context
  (let [result (sut/validate-imported-shortcuts {:workspace "not-a-map"})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-non-string-command
  (let [result (sut/validate-imported-shortcuts {:workspace {:escape 123}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace/escape" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-vector-command
  (let [result (sut/validate-imported-shortcuts {:workspace {:escape ["ctrl+e"]}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace/escape" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-unknown-top-level-key
  (let [result (sut/validate-imported-shortcuts {:workspace {:escape "escape"}
                                                 :unknown-context {}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(str/includes? (:path %) "unknown-context") (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-unknown-workspace-key
  (let [result (sut/validate-imported-shortcuts {:workspace {:not-a-real-shortcut "x"}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace/not-a-real-shortcut" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-unknown-optional-context-key
  (let [result (sut/validate-imported-shortcuts {:viewer {:not-a-real-shortcut "x"}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "viewer/not-a-real-shortcut" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-never-throws
  (t/is (= false (:valid? (sut/validate-imported-shortcuts (js/Date.)))))
  (t/is (= false (:valid? (sut/validate-imported-shortcuts #{:workspace}))))
  (t/is (= false (:valid? (sut/validate-imported-shortcuts (fn []))))))

(t/deftest import-custom-shortcuts-updates-multiple-contexts
  (let [imported {:workspace {:add-comment "alt+c"}
                  :viewer {:select-all "ctrl+alt+a"}}
        all-raw {:add-comment {:command "alt+c"}
                 :select-all {:command "ctrl+a"}}]
    (with-redefs [du/update-profile-props identity]
      (t/is (= {:custom-shortcuts {:workspace {:add-comment "alt+c"}
                                   :viewer {:select-all "ctrl+alt+a"}}}
               (ui-shortcuts/import-custom-shortcuts imported all-raw))))))

(t/deftest import-custom-shortcuts-preserves-unimported-contexts
  (let [current {:workspace {:existing "ctrl+e"}
                 :dashboard {:toggle-theme "alt+m"}
                 :viewer {:select-all "ctrl+a"}}
        imported {:workspace {:add-comment "alt+c"}}
        all-raw {:add-comment {:command "alt+c"}
                 :existing {:command "ctrl+e"}
                 :toggle-theme {:command "alt+m"}
                 :select-all {:command "ctrl+a"}}]
    (with-redefs [du/update-profile-props identity]
      (t/is (= {:custom-shortcuts {:workspace {:add-comment "alt+c"}
                                   :dashboard {:toggle-theme "alt+m"}
                                   :viewer {:select-all "ctrl+a"}}}
               (ui-shortcuts/import-custom-shortcuts
                {:profile {:props {:custom-shortcuts current}}
                 :workspace (:workspace imported)}
                all-raw))))))

(t/deftest import-custom-shortcuts-clears-context-when-empty
  (let [current {:workspace {:existing "ctrl+e"}}
        imported {:workspace {}}
        all-raw {}]
    (with-redefs [du/update-profile-props identity]
      (t/is (= {:custom-shortcuts {:workspace {}}}
               (ui-shortcuts/import-custom-shortcuts
                {:profile {:props {:custom-shortcuts current}}
                 :workspace (:workspace imported)}
                all-raw))))))

(t/deftest import-custom-shortcuts-disables-conflicting-default
  (let [imported {:workspace {:select-all "ctrl+a"}}
        all-raw {:select-all {:command "ctrl+a"}
                 :add-comment {:command "ctrl+a"}}]
    (with-redefs [du/update-profile-props identity]
      (t/is (= {:custom-shortcuts {:workspace {:select-all "ctrl+a"
                                               :add-comment ""}}}
               (ui-shortcuts/import-custom-shortcuts imported all-raw))))))

(t/deftest validate-imported-shortcuts-accepts-viewer-only-import
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:viewer {:next-frame "right"
                      :prev-frame "left"}}))))

(t/deftest import-custom-shortcuts-disables-duplicate-imported-bindings
  (let [imported {:workspace {:move-up "ctrl+up"
                              :move-to-top "ctrl+up"}}
        all-raw {:move-up {:command "ctrl+shift+up"}
                 :move-to-top {:command "ctrl+shift+top"}}]
    (with-redefs [du/update-profile-props identity]
      (let [customs (get-in (ui-shortcuts/import-custom-shortcuts imported all-raw)
                            [:custom-shortcuts :workspace])]
        (t/is (= "" (get customs :move-up))
              "First imported entry with duplicate command should be cleared")
        (t/is (= "ctrl+up" (get customs :move-to-top))
              "Last imported entry with duplicate command should survive")))))

(t/deftest compute-diff-shows-new-shortcut-changes
  (let [imported {:workspace {:add-comment "alt+c"}}
        diff     (diff-modal/compute-diff imported {})]
    (t/is (= 1 (count diff)))
    (let [entry (first diff)]
      (t/is (= :workspace (:context entry)))
      (t/is (= :add-comment (:key entry)))
      (t/is (= "alt+c" (:imported entry)))
      (t/is (not= "alt+c" (:current entry))))))

(t/deftest compute-diff-shows-conflict-as-disabled
  (let [imported {:workspace {:select-all "ctrl+shift+a"}}
        diff     (diff-modal/compute-diff imported {})]
    (t/is (pos? (count diff)))
    (let [select-all-entry (first (filter #(= :select-all (:key %)) diff))]
      (t/is (= :workspace (:context select-all-entry)))
      (t/is (= "ctrl+shift+a" (:imported select-all-entry))))))

(t/deftest compute-diff-shows-empty-string-as-disabled-shortcut
  (let [imported {:workspace {:escape ""}}
        current  {}
        diff     (diff-modal/compute-diff imported current)]
    (t/is (pos? (count diff)))
    (let [entry (first (filter #(= :escape (:key %)) diff))]
      (t/is (= :workspace (:context entry)))
      (t/is (= "" (:imported entry))))))

(t/deftest compute-diff-excludes-unchanged-shortcuts
  (let [imported {:workspace {:escape ""}}
        current  {:workspace {:escape ""}}
        diff     (diff-modal/compute-diff imported current)]
    (t/is (empty? diff))))

(t/deftest compute-diff-detects-multiple-contexts
  (let [imported {:workspace {:add-comment "alt+c"}
                  :viewer {:next-frame "right"}}
        diff     (diff-modal/compute-diff imported {})]
    (t/is (pos? (count diff)))
    (let [contexts (set (map :context diff))]
      (t/is (contains? contexts :workspace))
      (t/is (contains? contexts :viewer)))))

(t/deftest extract-shortcut-keys-uses-correct-context-override
  (let [customs {:workspace {:undo "shift+z"
                             :move-nodes "shift+m"}
                 :dashboard {:toggle-theme "alt+t"}
                 :viewer    {:next-frame "right"}}]
    (t/is (= "shift+z" (nth (restore-modal/extract-shortcut-keys :undo customs :workspace) 3))
          "Workspace override should be used for :undo")
    (t/is (= "alt+t" (nth (restore-modal/extract-shortcut-keys :toggle-theme customs :dashboard) 3))
          "Dashboard override should be used for :toggle-theme")
    (t/is (= "right" (nth (restore-modal/extract-shortcut-keys :next-frame customs :viewer) 3))
          "Viewer override should be used for :next-frame")
    (t/is (= "shift+m" (nth (restore-modal/extract-shortcut-keys :move-nodes customs :workspace) 3))
          "Workspace override should be used for path-specific :move-nodes")))

(t/deftest extract-shortcut-keys-returns-default-for-each-context
  (let [result (restore-modal/extract-shortcut-keys :move-nodes {} :workspace)]
    (t/is (string? (nth result 3))
          "Should return default command string for :move-nodes via :workspace context")
    (t/is (not= "" (nth result 3))
          "Default command should not be empty"))
  (let [result (restore-modal/extract-shortcut-keys :toggle-theme {} :dashboard)]
    (t/is (string? (nth result 3))
          "Should return default command string for :toggle-theme in :dashboard context")
    (t/is (not= "" (nth result 3))
          "Default command should not be empty"))
  (let [result (restore-modal/extract-shortcut-keys :next-frame {} :viewer)]
    (t/is (nth result 3)
          "Should return a default command for :next-frame in :viewer context")))

;; --- shortcut->command-string + command-based search --------------------
;; The search in both the settings shortcuts page and the workspace sidebar
;; matches shortcut entries by their translated name AND by their key-combo
;; string. `shortcut->command-string` (in `app.main.ui.shortcuts`) extracts the
;; searchable form from `:command`/`:show-command`; `matches-search` does the
;; case-insensitive substring match. These tests pin that contract so searching
;; e.g. "ctrl" surfaces every shortcut whose combo includes ctrl.

(t/deftest shortcut->command-string-extracts-string-command
  (t/testing "a plain string command is returned lowercased"
    (t/is (= "ctrl+z" (ui-shortcuts/shortcut->command-string
                       {:command "ctrl+z"})))))

(t/deftest shortcut->command-string-joins-vector-command
  (t/testing "a vector command (key sequence) is joined with spaces so every
              token is individually searchable"
    (t/is (= "g v" (ui-shortcuts/shortcut->command-string
                    {:command ["g" "v"]})))))

(t/deftest shortcut->command-string-prefers-show-command
  (t/testing ":show-command (display override) wins over :command"
    (t/is (= "shift+x" (ui-shortcuts/shortcut->command-string
                        {:command "ctrl+z" :show-command "shift+x"})))))

(t/deftest shortcut->command-string-empty-for-section-node
  (t/testing "a node without :command/:show-command (e.g. a section or
              subsection heading) yields an empty string so it never matches a
              non-blank command search"
    (t/is (= "" (ui-shortcuts/shortcut->command-string
                 {:translation "workspace"})))))

(t/deftest shortcut->command-string-lowercases
  (t/testing "the result is lowercased so search is case-insensitive"
    (t/is (= "ctrl+shift+z" (ui-shortcuts/shortcut->command-string
                             {:command "Ctrl+Shift+Z"})))))

(t/deftest command-search-matches-ctrl-prefix
  (t/testing "searching 'ctrl' matches a shortcut whose command contains ctrl"
    (let [shortcut {:command "ctrl+shift+s"
                    :translation "Save all"}]
      (t/is (matches-search (ui-shortcuts/shortcut->command-string shortcut)
                            "ctrl")))))

(t/deftest command-search-does-not-match-when-command-lacks-term
  (t/testing "searching 'alt' does not match a shortcut with no alt in its combo"
    (let [shortcut {:command "ctrl+z"
                    :translation "Undo"}]
      (t/is (not (matches-search (ui-shortcuts/shortcut->command-string shortcut)
                                 "alt"))))))

(t/deftest command-search-matches-key-sequence-vector
  (t/testing "searching a single key in a key-sequence vector command matches"
    (let [shortcut {:command ["g" "v"]
                    :translation "Group"}]
      (t/is (matches-search (ui-shortcuts/shortcut->command-string shortcut)
                            "g")))))

(t/deftest search-matches-by-translation-or-command
  (t/testing "a search term matches if it appears in either the translation or
              the command string — the OR that the filter predicates use"
    (let [shortcut {:command "ctrl+s"
                    :translation "Save"}]
      ;; by translation
      (t/is (or (matches-search (:translation shortcut) "save")
                (matches-search (ui-shortcuts/shortcut->command-string shortcut) "save")))
      ;; by command
      (t/is (or (matches-search (:translation shortcut) "ctrl")
                (matches-search (ui-shortcuts/shortcut->command-string shortcut) "ctrl"))))))

;; --- keyboard-event->mousetrap across keyboard layouts -------------------
;; `keyboard-event->mousetrap` (private, exercised via #') turns a recorded
;; keydown into a Mousetrap-style combo string. The runtime shortcut matcher
;; (Mousetrap) resolves keydown events positionally, based on the physical key,
;; not the character the layout+Shift combination happens to produce. So the
;; recorder must key off `event.code` (layout-independent) rather than
;; `event.key` (which varies per layout and per Shift state), or a recorded
;; combo could end up never matching what actually fires. Real key events are
;; always wrapped by goog.events in a goog.events.BrowserEvent, which forwards
;; `key` but not `code` — the fixture below simulates that wrapper via
;; `getBrowserEvent`, which is how the recorder reaches the underlying `code`.

(defn- mock-wrapped-keydown
  "Simulates a goog.events.BrowserEvent-wrapped keydown: `key`/modifiers are
   forwarded directly (as goog.events does), but `code` is only reachable via
   `getBrowserEvent`, mirroring the real wrapper's behavior. `key` is passed
   through as-is (not defaulted) so callers can simulate a genuinely missing
   `event.key` with `:key nil` - an empty string is truthy in ClojureScript,
   so silently substituting \"\" here would defeat that case."
  [{:keys [key code shift ctrl alt meta]}]
  #js {:key key
       :shiftKey (boolean shift)
       :ctrlKey (boolean ctrl)
       :altKey (boolean alt)
       :metaKey (boolean meta)
       :getBrowserEvent (fn [] #js {:code code})})

(defn- mock-native-keydown
  "Simulates a plain, unwrapped native KeyboardEvent (no getBrowserEvent),
   exercising the recorder's defensive fallback to read `code` directly."
  [{:keys [key code shift ctrl alt meta]}]
  #js {:key key
       :code code
       :shiftKey (boolean shift)
       :ctrlKey (boolean ctrl)
       :altKey (boolean alt)
       :metaKey (boolean meta)})

(t/deftest mousetrap-shift-period-on-us-layout
  (t/testing "US layout: Shift + Period key types \">\""
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+."
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key ">" :code "Period" :shift true})))))))

(t/deftest mousetrap-shift-period-on-spanish-iso-layout
  (t/testing "Spanish (ISO) layout: Shift + Period key types \":\" instead of
              \">\" - this is the layout that originally reported \"shift+.\"
              being recorded as \"shift+:\". The physical key (code \"Period\")
              is the same as on a US layout, so the recorded combo must match."
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+."
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key ":" :code "Period" :shift true})))))))

(t/deftest mousetrap-shift-period-on-arbitrary-layout
  (t/testing "an arbitrary layout where Shift + Period produces a character
              unrelated to \".\" or \":\" still normalizes to \"shift+.\",
              since matching is driven by the physical key, not the glyph"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+."
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "?" :code "Period" :shift true})))))))

(t/deftest mousetrap-letter-key-on-azerty-layout
  (t/testing "French AZERTY swaps the Q and A letters relative to a US QWERTY
              layout: the physical key printed \"A\" on the keycap sits where
              \"Q\" is on a US layout (code \"KeyQ\") and types \"a\". Unlike
              punctuation, Mousetrap's own matcher does NOT resolve letters
              positionally (they're absent from its _MAP/_KEYCODE_MAP) - it
              falls back to the typed character. So recording must stay
              glyph-based here too (\"a\", not \"q\"), or the recorded combo
              would never match what that physical key actually triggers."
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "a"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "a" :code "KeyQ"})))))))

(t/deftest mousetrap-digit-key-on-azerty-layout
  (t/testing "on French AZERTY, the unshifted top-row key at the US \"1\"
              position (code \"Digit1\") types \"&\", not \"1\" - Shift is
              needed for the digit. Digits are absent from Mousetrap's
              _MAP/_KEYCODE_MAP just like letters, so recording must stay
              glyph-based (\"&\") rather than resolving positionally to \"1\",
              which would silently record the wrong, unreachable combo."
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "&"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "&" :code "Digit1"})))))))

(t/deftest mousetrap-reads-code-from-unwrapped-native-event
  (t/testing "falls back to reading `code` straight off the event when it is
              not a goog.events.BrowserEvent wrapper (no getBrowserEvent)"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+."
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-native-keydown {:key ":" :code "Period" :shift true})))))))

(t/deftest mousetrap-tolerates-getBrowserEvent-returning-nil
  (t/testing "does not throw when getBrowserEvent() itself returns nil/undefined
              (defensive: not reachable via goog.events' real dispatch today,
              but normalize-key must not crash the recorder if it happens)"
    (with-redefs [cf/check-platform? (constantly false)]
      (let [event #js {:key ":" :shiftKey true :ctrlKey false :altKey false
                       :metaKey false :getBrowserEvent (fn [] nil)}]
        (t/is (= "shift+:"
                 (#'ui-shortcuts/keyboard-event->mousetrap event)))))))

(t/deftest mousetrap-tolerates-missing-key
  (t/testing "does not throw when `event.key` itself is nil/undefined"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (nil? (#'ui-shortcuts/keyboard-event->mousetrap
                   (mock-wrapped-keydown {:key nil :code "Period"})))))))

(t/deftest mousetrap-ignores-bare-modifier-press
  (t/testing "pressing only Shift (no other key yet) records nothing"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (nil? (#'ui-shortcuts/keyboard-event->mousetrap
                   (mock-wrapped-keydown {:key "Shift" :shift true})))))))

(t/deftest mousetrap-falls-back-to-lowercased-key-when-unmapped
  (t/testing "a key with no entry in the named-key or code maps (e.g. a
              function key) falls back to the lowercased `key` value"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "f13"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "F13" :code "F13"})))))))

(t/deftest mousetrap-full-modifier-combo-on-non-mac
  (t/testing "ctrl+alt+shift all combine with the normalized key, in order"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "ctrl+alt+shift+q"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "q" :code "KeyQ"
                                       :ctrl true :alt true :shift true})))))))
