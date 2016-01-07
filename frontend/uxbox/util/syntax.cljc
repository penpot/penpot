(ns uxbox.util.syntax
  (:refer-clojure :exclude [defonce]))

(defmacro define-once
  [& body]
  (let [sym (gensym "uxbox-")]
    `(cljs.core/defonce ~sym
       (do ~@body
           nil))))
