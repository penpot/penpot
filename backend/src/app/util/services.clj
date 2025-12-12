;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.services
  "A helpers and macros for define rpc like registry based services."
  (:refer-clojure :exclude [defmethod])
  (:require
   [app.common.data :as d]
   [cuerdas.core :as str]))

(defmacro defmethod
  [sname & body]
  (let [[docs body]  (if (string? (first body))
                       [(first body) (rest body)]
                       [nil body])
        [mdata body] (if (map? (first body))
                       [(first body) (rest body)]
                       [nil body])

        [args body]  (if (vector? (first body))
                       [(first body) (rest body)]
                       [nil body])]
    (when-not args
      (throw (IllegalArgumentException. "Missing arguments on `defmethod` macro.")))

    (let [mdata (assoc mdata
                       ::docstring (some-> docs str/unindent)
                       ::spec sname
                       ::name (name sname))

          sym   (symbol (str "sm$" (name sname)))]
      `(do
         (def ~sym (fn ~args ~@body))
         (reset-meta! (var ~sym) ~mdata)))))

(def nsym-xf
  (comp
   (d/domap require)
   (map find-ns)
   (mapcat (fn [ns]
             (->> (ns-publics ns)
                  (map second)
                  (filter #(::spec (meta %)))
                  (map (fn [fvar]
                         [(deref fvar)
                          (-> (meta fvar)
                              (assoc :ns (-> ns ns-name str)))])))))))

(defn scan-ns
  [& nsyms]
  (sequence nsym-xf nsyms))
