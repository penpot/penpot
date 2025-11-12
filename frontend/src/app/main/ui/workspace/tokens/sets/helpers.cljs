(ns app.main.ui.workspace.tokens.sets.helpers
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS - Shared functions for token sets management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
