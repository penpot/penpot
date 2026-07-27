(ns frontend-tests.ui.settings-shortcuts-test
  (:require
   [app.main.data.profile :as du]
   [app.main.ui.settings.restore-shortcuts-modal :as restore-modal]
   [app.main.ui.settings.shortcuts :as sut]
   [app.main.ui.shortcuts :as ui-shortcuts]
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
