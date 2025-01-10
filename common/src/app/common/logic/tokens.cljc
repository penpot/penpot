(ns app.common.logic.tokens
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.tokens-lib :as ctob]))

(defn generate-update-active-sets
  "Copy the active sets from the currently active themes and move them to the hidden token theme and update the theme with `update-hidden-theme-fn`.

  Use this for managing sets active state without having to modify a user created theme (\"no themes selected\" state in the ui)."
  [changes tokens-lib update-hidden-theme-fn]
  (let [prev-active-token-themes (ctob/get-active-theme-paths tokens-lib)
        active-token-set-names   (ctob/get-active-themes-set-names tokens-lib)

        prev-hidden-token-theme (ctob/get-hidden-theme tokens-lib)
        hidden-token-theme      (-> (or (some-> prev-hidden-token-theme (ctob/set-sets active-token-set-names))
                                        (ctob/make-hidden-token-theme :sets active-token-set-names))
                                    (update-hidden-theme-fn))

        changes (-> changes
                    (pcb/update-active-token-themes #{ctob/hidden-token-theme-path} prev-active-token-themes))

        changes (if prev-hidden-token-theme
                  (pcb/update-token-theme changes hidden-token-theme prev-hidden-token-theme)
                  (pcb/add-token-theme changes hidden-token-theme))]
    changes))

(defn generate-toggle-token-set
  "Toggle a token set at `set-name` in `tokens-lib` without modifying a user theme."
  [changes tokens-lib set-name]
  (generate-update-active-sets changes tokens-lib #(ctob/toggle-set % set-name)))

(defn generate-toggle-token-set-group
  "Toggle a token set group at `prefixed-set-path-str` in `tokens-lib` without modifying a user theme."
  [changes tokens-lib prefixed-set-path-str]
  (let [deactivate? (contains? #{:all :partial} (ctob/sets-at-path-all-active? tokens-lib prefixed-set-path-str))
        sets-names  (->> (ctob/get-sets-at-prefix-path tokens-lib prefixed-set-path-str)
                         (map :name)
                         (into #{}))
        update-fn   (if deactivate?
                      #(ctob/disable-sets % sets-names)
                      #(ctob/enable-sets % sets-names))]
    (generate-update-active-sets changes tokens-lib update-fn)))
