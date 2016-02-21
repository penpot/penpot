(ns uxbox.util.lens
  (:refer-clojure :exclude [derive merge])
  (:require [cats.labs.lens :as l]))

(defn getter
  [f]
  (l/lens f #(throw (ex-info "Not implemented" {}))))

(defn merge
  [data]
  (l/lens
   (fn [s] (cljs.core/merge s data))
   #(throw (ex-info "Not implemented" {}))))
