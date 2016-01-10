(ns uxbox.util.syntax
  (:refer-clojure :exclude [defonce]))

(defmacro define-once
  [name' & body]
  (let [sym (symbol (str (namespace name') "-" (name name')))]
    `(cljs.core/defonce ~sym
       (do ~@body nil))))
