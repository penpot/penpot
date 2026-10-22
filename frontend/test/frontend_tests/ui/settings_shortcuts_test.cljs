(ns frontend-tests.ui.settings-shortcuts-test
  (:require
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts :as dsc]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts :as vsc]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
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
            {:workspace {:draw-rect "shift+r"
                         :increase-zoom "+"
                         :zoom-lense-decrease "alt+z"}}))))

(t/deftest validate-imported-shortcuts-accepts-optional-contexts
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:workspace {:draw-rect "shift+r"}
             :dashboard {:toggle-theme "alt+m"}
             :viewer {:next-frame "right"}}))))

(t/deftest validate-imported-shortcuts-accepts-empty-string-command
  (t/is (= {:valid? true}
           (sut/validate-imported-shortcuts
            {:workspace {:draw-rect ""}}))))

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
  (let [result (sut/validate-imported-shortcuts {:workspace {:draw-rect 123}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace/draw-rect" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-vector-command
  (let [result (sut/validate-imported-shortcuts {:workspace {:draw-rect ["ctrl+e"]}})]
    (t/is (= false (:valid? result)))
    (t/is (seq (:errors result)))
    (t/is (some #(= "workspace/draw-rect" (:path %)) (:errors result)))))

(t/deftest validate-imported-shortcuts-rejects-unknown-top-level-key
  (let [result (sut/validate-imported-shortcuts {:workspace {:draw-rect "shift+r"}
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

(t/deftest sanitize-imported-shortcuts-skips-unknown-action-keys
  (t/is (= {:shortcuts {:workspace {:draw-rect "shift+r"}}
            :skipped ["workspace/not-a-real-shortcut"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-rect "shift+r"
                         :not-a-real-shortcut "x"}}))))

(t/deftest sanitize-imported-shortcuts-skips-unknown-contexts
  (t/is (= {:shortcuts {:workspace {:draw-rect "shift+r"}}
            :skipped ["unknown-context"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-rect "shift+r"}
             :unknown-context {:draw-rect "shift+r"}}))))

(t/deftest sanitize-imported-shortcuts-skips-non-string-commands
  (t/is (= {:shortcuts {:workspace {:zoom-lense-decrease "alt+z"}}
            :skipped ["workspace/draw-rect" "workspace/increase-zoom"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-rect 123
                         :increase-zoom ["+"]
                         :zoom-lense-decrease "alt+z"}}))))

(t/deftest sanitize-imported-shortcuts-skips-invalid-commands
  (t/is (= {:shortcuts {:workspace {:zoom-lense-decrease "alt+z"}}
            :skipped ["workspace/draw-rect" "workspace/increase-zoom"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-rect "foo+bar"
                         :increase-zoom "ctrl+"
                         :zoom-lense-decrease "alt+z"}}))))

(t/deftest cancel-copy-and-paste-are-not-customizable
  (t/testing "in the workspace and in the path editor, which share the
              shortcut keys of the workspace context"
    (doseq [shortcuts [wsc/shortcuts psc/shortcuts]
            k         [:escape :copy :paste]]
      (t/is (false? (get-in shortcuts [k :customizable]))
            (str k)))))

(t/deftest sanitize-imported-shortcuts-skips-non-customizable-keys
  (t/testing "cancel, copy and paste cannot be imported, even with a free
              key combination"
    (t/is (= {:shortcuts {}
              :skipped ["workspace/copy" "workspace/escape" "workspace/paste"]}
             (sut/sanitize-imported-shortcuts
              {:workspace {:escape "shift+escape"
                           :copy   "ctrl+shift+1"
                           :paste  "ctrl+shift+2"}})))))

(t/deftest apply-custom-overrides-skips-non-customizable-shortcuts
  (t/testing "an override stored before a shortcut became non-customizable
              is ignored, since the settings page can no longer reset it"
    (let [shortcuts {:escape {:command "escape" :customizable false}
                     :undo   {:command "ctrl+z"}}
          result    (ds/apply-custom-overrides shortcuts
                                               {:workspace {:escape "shift+escape"
                                                            :undo   "ctrl+u"}}
                                               :workspace)]
      (t/is (= "escape" (get-in result [:escape :command])))
      (t/is (= "ctrl+u" (get-in result [:undo :command]))))))

(t/deftest sanitize-imported-shortcuts-skips-reserved-commands
  (t/is (= {:shortcuts {:workspace {:zoom-lense-decrease "alt+z"}}
            :skipped ["workspace/draw-rect" "workspace/increase-zoom"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-rect "escape"
                         :increase-zoom "ctrl+v"
                         :zoom-lense-decrease "alt+z"}}))))

(t/deftest sanitize-imported-shortcuts-skips-copy-combination
  (t/is (= {:shortcuts {:workspace {:draw-rect "ctrl+alt+c"}}
            :skipped ["workspace/draw-ellipse"]}
           (sut/sanitize-imported-shortcuts
            {:workspace {:draw-ellipse "ctrl+c"
                         :draw-rect "ctrl+alt+c"}}))))

(t/deftest sanitize-imported-shortcuts-skips-non-map-context
  (t/is (= {:shortcuts {:viewer {:next-frame "right"}}
            :skipped ["workspace"]}
           (sut/sanitize-imported-shortcuts
            {:workspace "not-a-map"
             :viewer {:next-frame "right"}}))))

(t/deftest sanitize-imported-shortcuts-drops-context-without-viable-entries
  (t/testing "a context whose entries are all skipped is left out, so the
              import does not clear the user's shortcuts in that context"
    (t/is (= {:shortcuts {}
              :skipped ["viewer/not-a-real-shortcut"]}
             (sut/sanitize-imported-shortcuts
              {:viewer {:not-a-real-shortcut "x"}})))))

(t/deftest sanitize-imported-shortcuts-keeps-empty-context
  (t/is (= {:shortcuts {:workspace {}}
            :skipped []}
           (sut/sanitize-imported-shortcuts {:workspace {}}))))

(t/deftest sanitize-imported-shortcuts-returns-nil-for-non-maps
  (t/is (nil? (sut/sanitize-imported-shortcuts nil)))
  (t/is (nil? (sut/sanitize-imported-shortcuts "shortcuts")))
  (t/is (nil? (sut/sanitize-imported-shortcuts 42)))
  (t/is (nil? (sut/sanitize-imported-shortcuts []))))

(t/deftest sanitize-imported-shortcuts-output-is-valid
  (let [{:keys [shortcuts]} (sut/sanitize-imported-shortcuts
                             {:workspace {:draw-rect "shift+r"
                                          :not-a-real-shortcut "x"
                                          :increase-zoom 1}
                              :dashboard "not-a-map"
                              :viewer {:next-frame "right"}
                              :unknown-context {}})]
    (t/is (= {:valid? true}
             (sut/validate-imported-shortcuts shortcuts)))))

(t/deftest valid-command-accepts-key-combinations
  (t/are [command] (true? (ui-shortcuts/valid-command? command))
    ""
    "a"
    "ctrl+shift+k"
    "Ctrl+s"
    "command+alt+ñ"
    "+"
    "shift++"
    "ctrl+plus"
    "shift+\""
    "g v"
    "escape"
    "Escape"
    "f1"
    "f12"
    "f24"
    "num5"
    "alt"))

(t/deftest valid-command-rejects-other-strings
  (t/are [command] (false? (ui-shortcuts/valid-command? command))
    "foo"
    "foo+bar"
    "a+b"
    "ctrl+foo"
    "ctrl+"
    "ctrl+shift"
    "++"
    "f25"
    "   "
    " a"
    "a "
    "a  b"
    "🙂"
    nil
    123))

(t/deftest reserved-command-matches-non-customizable-combinations
  (t/testing "cancel, copy and paste keep their keys; spelling and modifier
              order do not matter, and a reserved step taints a sequence"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/are [command] (true? (ui-shortcuts/reserved-command? command))
        "escape"
        "Escape"
        "esc"
        "ctrl+c"
        "Control+C"
        "mod+c"
        "ctrl+v"
        "Control+V"
        "mod+v"
        "g escape"
        "ctrl+c g"))))

(t/deftest reserved-command-ignores-other-combinations
  (with-redefs [cf/check-platform? (constantly false)]
    (t/are [command] (false? (ui-shortcuts/reserved-command? command))
      ""
      "v"
      "c"
      "shift+escape"
      "ctrl+shift+v"
      "ctrl+shift+c"
      "ctrl+alt+c"
      "shift+alt+c"
      nil)))

(t/deftest valid-command-accepts-every-default-command
  (t/testing "a profile exported by Penpot always holds valid commands"
    (doseq [shortcuts [psc/shortcuts wsc/shortcuts dsc/shortcuts vsc/shortcuts]
            [shortcut-key {:keys [command]}] shortcuts
            command (if (vector? command) command [command])
            :when (some? command)]
      (t/is (ui-shortcuts/valid-command? command)
            (str shortcut-key " -> " (pr-str command))))))

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
  (let [imported {:workspace {:draw-rect ""}}
        current  {}
        diff     (diff-modal/compute-diff imported current)]
    (t/is (pos? (count diff)))
    (let [entry (first (filter #(= :draw-rect (:key %)) diff))]
      (t/is (= :workspace (:context entry)))
      (t/is (= "" (:imported entry))))))

(t/deftest compute-diff-excludes-unchanged-shortcuts
  (let [imported {:workspace {:draw-rect ""}}
        current  {:workspace {:draw-rect ""}}
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
;; Shortcut search matches by translated name or key-combo string.
;; `shortcut->command-string` (in `app.main.ui.shortcuts`) extracts the
;; searchable combo from `:command`/`:show-command`; `matches-search` does
;; the case-insensitive substring match.

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
;; keydown into a Mousetrap-style combo string from `event.key` (the typed
;; glyph), so recording and dispatch always use the same token. Real
;; keydown events are wrapped by goog.events in a goog.events.BrowserEvent,
;; which forwards `key` but not `code`; the fixtures below simulate that
;; wrapper via `getBrowserEvent`.

(defn- mock-wrapped-keydown
  "Simulates a goog.events.BrowserEvent-wrapped keydown: `key` and modifiers
   are forwarded directly, and `code` is only reachable via `getBrowserEvent`.
   `key` is passed through as-is (not defaulted) so callers can simulate a
   missing `event.key` with `:key nil` - substituting \"\" would defeat that,
   since an empty string is truthy in ClojureScript."
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
  (t/testing "US layout without a layout map: Shift + Period types \">\" and
              records the glyph, the same token Mousetrap dispatches"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+>"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key ">" :code "Period" :shift true})))))))

(t/deftest mousetrap-shift-period-with-layout-map-records-primary
  (t/testing "with a layout map, Shift + Period records the primary \".\"
              even when the typed glyph differs"
    (with-redefs [cf/check-platform? (constantly false)]
      (try
        (reset! ds/layout-map #js {:get (fn [code] (when (= code "Period") "."))})
        (t/is (= "shift+."
                 (#'ui-shortcuts/keyboard-event->mousetrap
                  (mock-wrapped-keydown {:key ">" :code "Period" :shift true}))))
        (t/is (= "shift+."
                 (#'ui-shortcuts/keyboard-event->mousetrap
                  (mock-wrapped-keydown {:key ":" :code "Period" :shift true}))))
        (finally
          (reset! ds/layout-map nil))))))

(t/deftest mousetrap-shift-period-on-spanish-iso-layout
  (t/testing "Spanish (ISO) layout without a layout map: Shift + Period
              types \":\" and records the glyph"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+:"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key ":" :code "Period" :shift true})))))))

(t/deftest mousetrap-shift-period-on-arbitrary-layout
  (t/testing "without a layout map the typed glyph wins over the physical
              position: \"?\" on the Period position records \"shift+?\""
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+?"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "?" :code "Period" :shift true})))))))

(t/deftest mousetrap-letter-key-on-azerty-layout
  (t/testing "French AZERTY: the key printed \"A\" sits where \"Q\" is on a US
              layout (code \"KeyQ\") and types \"a\". Letters resolve by glyph
              (\"a\", not \"q\") to match what that physical key triggers"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "a"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "a" :code "KeyQ"})))))))

(t/deftest mousetrap-digit-key-on-azerty-layout
  (t/testing "French AZERTY: the unshifted top-row key at the US \"1\"
              position (code \"Digit1\") types \"&\". Digits, like letters,
              resolve by glyph (\"&\") rather than positionally to \"1\""
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "&"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "&" :code "Digit1"})))))))

(t/deftest mousetrap-reads-code-from-unwrapped-native-event
  (t/testing "recording works on a plain native event too (glyph wins
              without a layout map)"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+:"
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

(t/deftest mousetrap-shifted-letter-lowercases
  (t/testing "Ctrl+Shift+Z types \"Z\" but records \"ctrl+shift+z\" so the
              bind and the event use the same token"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "ctrl+shift+z"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "Z" :code "KeyZ"
                                       :ctrl true :shift true})))))))

(t/deftest mousetrap-shifted-digit-resolves-to-digit
  (t/testing "a digit row key held with Shift records the digit itself: US
              Shift+0 types \")\" but records \"shift+0\", so the physical
              digit key means the same on every layout (production keyCode
              behavior)"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+0"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key ")" :code "Digit0" :shift true}))))
      (t/is (= "shift+1"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "!" :code "Digit1" :shift true}))))
      (t/is (= "shift+2"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "@" :code "Digit2" :shift true}))))
      (t/is (= "shift+8"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "*" :code "Digit8" :shift true})))))))

(t/deftest mousetrap-shifted-digit-already-digit-unchanged
  (t/testing "AZERTY Shift+Digit0 already types \"0\": unchanged"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+0"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "0" :code "Digit0" :shift true})))))))

(t/deftest mousetrap-space-and-named-keys
  (t/testing "\" \" records as space and named keys stay lowercase"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "space"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key " " :code "Space"}))))
      (t/is (= "escape"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "Escape" :code "Escape"}))))
      (t/is (= "shift+tab"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "Tab" :code "Tab" :shift true}))))
      (t/is (= "del"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "Delete" :code "Delete"})))))))

(t/deftest mousetrap-altgr-drops-phantom-ctrl
  (t/testing "Spanish AltGr++] types \"]\" with ctrl+alt flags, but the
              phantom ctrl is dropped so no ctrl+alt shortcut fires"
    (with-redefs [cf/check-platform? (constantly false)]
      (let [event #js {:key "]"
                       :shiftKey false :ctrlKey true :altKey true :metaKey false
                       :getModifierState (fn [m] (= m "AltGraph"))
                       :getBrowserEvent (fn [] nil)}]
        (t/is (= "alt+]"
                 (#'ui-shortcuts/keyboard-event->mousetrap event)))))))

(t/deftest mousetrap-real-ctrl-alt-keeps-both
  (t/testing "a real Ctrl+Alt without AltGraph still records both modifiers"
    (with-redefs [cf/check-platform? (constantly false)]
      (let [event #js {:key "]"
                       :shiftKey false :ctrlKey true :altKey true :metaKey false
                       :getModifierState (fn [_] false)
                       :getBrowserEvent (fn [] nil)}]
        (t/is (= "ctrl+alt+]"
                 (#'ui-shortcuts/keyboard-event->mousetrap event)))))))

(t/deftest mousetrap-spanish-shift-plus-with-layout-map-records-primary
  (t/testing "Spanish layout with a layout map: Shift + the physical \"+\"
              key types \"*\" but records the primary \"shift++\", which is
              what the user sees and what Mousetrap matches"
    (with-redefs [cf/check-platform? (constantly false)]
      (try
        (reset! ds/layout-map #js {:get (fn [code] (when (= code "BracketRight") "+"))})
        (t/is (= "shift++"
                 (#'ui-shortcuts/keyboard-event->mousetrap
                  (mock-wrapped-keydown {:key "*" :code "BracketRight" :shift true}))))
        (t/is (= "+"
                 (#'ui-shortcuts/keyboard-event->mousetrap
                  (mock-wrapped-keydown {:key "+" :code "BracketRight"}))))
        (finally
          (reset! ds/layout-map nil))))))

(t/deftest mousetrap-spanish-shift-plus-without-layout-map-records-glyph
  (t/testing "Spanish layout without a layout map: Shift + the physical
              \"+\" key records the typed glyph \"shift+*\", the same token
              Mousetrap dispatches"
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "shift+*"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "*" :code "BracketRight" :shift true})))))))

(t/deftest mousetrap-spanish-unshifted-plus-records-plus
  (t/testing "Spanish layout: the unshifted physical \"+\" key records \"+\""
    (with-redefs [cf/check-platform? (constantly false)]
      (t/is (= "+"
               (#'ui-shortcuts/keyboard-event->mousetrap
                (mock-wrapped-keydown {:key "+" :code "BracketRight"})))))))

(t/deftest layout-map-supported-matches-keyboard-api
  (t/testing "false without a navigator or without a keyboard API"
    (t/is (false? (ds/layout-map-supported? nil)))
    (t/is (false? (ds/layout-map-supported? #js {})))
    (t/is (false? (ds/layout-map-supported? #js {:keyboard #js {}}))))
  (t/testing "true when getLayoutMap exists (Chromium)"
    (t/is (true? (ds/layout-map-supported?
                  #js {:keyboard #js {:getLayoutMap (fn [] nil)}})))))
