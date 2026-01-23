(ns app.main.ui.workspace.tokens.sets.helpers
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS - Shared functions for token sets management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-update-token-set
  [tokens-lib token-set name]
  (let [name   (ctob/normalize-set-name name (ctob/get-name token-set))
        errors (sm/validation-errors name (cfo/make-token-set-name-schema
                                           tokens-lib
                                           (ctob/get-id token-set)))]
    (st/emit! (dwtl/clear-token-set-edition))
    (if (empty? errors)
      (st/emit! (dwtl/rename-token-set token-set name))
      (st/emit! (ntf/show {:content (tr "errors.token-set-already-exists")
                           :type :toast
                           :level :error
                           :timeout 9000})))))

(defn on-update-token-set-group
  [path name]
  (st/emit! (dwtl/clear-token-set-edition)
            (dwtl/rename-token-set-group path name)))

(defn on-create-token-set
  [tokens-lib parent-set name]
  (let [name   (ctob/make-child-name parent-set name)
        errors (sm/validation-errors name (cfo/make-token-set-name-schema tokens-lib nil))]
    (st/emit! (ptk/data-event ::ev/event {::ev/name "create-token-set" :name name})
              (dwtl/clear-token-set-creation))
    (if (empty? errors)
      (let [token-set (ctob/make-token-set :name name)]
        (st/emit! (dwtl/create-token-set token-set)))
      (st/emit! (ntf/show {:content (tr "errors.token-set-already-exists")
                           :type :toast
                           :level :error
                           :timeout 9000})))))
