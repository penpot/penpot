(ns hooks.i18n
  (:require [clj-kondo.hooks-api :as api]))

(defn tr-dynamic
  [{:keys [:node]}]
  (let [[_ code & _] (:children node)]
    (when (and (some? code)
               (not (api/string-node? code)))
      (let [{:keys [:row :col :end-row :end-col]} (meta code)]
        (api/reg-finding! {:message "dynamic key in (tr ...) is invisible to rehash; use a string literal or declare it with ;; (tr \"key\")"
                           :type :penpot/tr-dynamic
                           :row row
                           :col col
                           ;; end positions are required for #_:clj-kondo/ignore to match
                           :end-row end-row
                           :end-col end-col}))))
  {:node node})
