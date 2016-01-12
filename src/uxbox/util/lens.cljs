(ns uxbox.util.lens
  (:refer-clojure :exclude [derive merge])
  (:require [cats.labs.lens :as l]))

(defn dep-in
  [where link]
  {:pre [(vector? where) (vector? link)]}
  (l/lens
   (fn [s]
     (let [value (get-in s link)
           path (conj where value)]
       (get-in s path)))
   (fn [s f]
     (throw (ex-info "Not implemented" {})))))

(defn getter
  [f]
  (l/lens f #(throw (ex-info "Not implemented" {}))))

(defn merge
  [data]
  (l/lens
   (fn [s] (cljs.core/merge s data))
   #(throw (ex-info "Not implemented" {}))))

(defn derive
  [a path]
  (l/focus-atom (l/in path) a))

(defn focus
  ([state]
   (l/focus-atom l/id state))
  ([lens state]
   (l/focus-atom lens state)))
