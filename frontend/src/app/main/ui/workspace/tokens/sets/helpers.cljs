(ns app.main.ui.workspace.tokens.sets.helpers
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-start-creation
  []
  (st/emit! (dwtl/start-token-set-creation [])))

(defn on-select-token-set-click [name]
  (st/emit! (dwtl/set-selected-token-set-name name)))

(defn on-toggle-token-set-click [name]
  (st/emit! (dwtl/toggle-token-set name)))

(defn on-toggle-token-set-group-click [path]
  (st/emit! (dwtl/toggle-token-set-group path)))

(defn on-update-token-set
  [token-set name]
  (st/emit! (dwtl/clear-token-set-edition)
            (dwtl/update-token-set token-set name)))

(defn on-update-token-set-group
  [path name]
  (st/emit! (dwtl/clear-token-set-edition)
            (dwtl/rename-token-set-group path name)))

(defn on-create-token-set
  [parent-set name]
  (let [;; FIXME: this code should be reusable under helper under
        ;; common types namespace
        name
        (if-let [parent-path (ctob/get-set-path parent-set)]
          (->> (concat parent-path (ctob/split-set-name name))
               (ctob/join-set-path))
          (ctob/normalize-set-name name))]

    (st/emit! (ptk/data-event ::ev/event {::ev/name "create-token-set" :name name})
              (dwtl/create-token-set name))))

(defn group-edition-id
  "Prefix editing groups `edition-id` so it can be differentiated from sets with the same id."
  [edition-id]
  (str "group-" edition-id))
