;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.permissions
  "A permission checking helper factories."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

(s/def ::role #{:admin :owner :editor :viewer})

(defn assign-role-flags
  [params role]
  (us/verify ::role role)
  (cond-> params
    (= role :owner)
    (assoc :is-owner true
           :is-admin true
           :can-edit true)

    (= role :admin)
    (assoc :is-owner false
           :is-admin true
           :can-edit true)

    (= role :editor)
    (assoc :is-owner false
           :is-admin false
           :can-edit true)

    (= role :viewer)
    (assoc :is-owner false
           :is-admin false
           :can-edit false)))

(defn make-edition-predicate-fn
  "A simple factory for edition permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn [& args]
    (let [rows (apply qfn args)]
      (when-not (or (empty? rows)
                    (not (or (some :can-edit rows)
                             (some :is-admin rows)
                             (some :is-owner rows))))
        rows))))

(defn make-read-predicate-fn
  "A simple factory for read permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn [& args]
    (let [rows (apply qfn args)]
      (when (seq rows)
        rows))))

(defn make-check-fn
  "Helper that converts a predicate permission function to a check
  function (function that raises an exception)."
  [pred]
  (fn [& args]
    (when-not (seq (apply pred args))
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))))


;; TODO: the following functions are deprecated and replaced with the
;; new ones. Should not be used.

(defn make-edition-check-fn
  "A simple factory for edition permission check functions."
  [qfn]
  (us/assert fn? qfn)
  (fn [& args]
    (let [rows (apply qfn args)]
      (if (or (empty? rows)
              (not (or (some :can-edit rows)
                       (some :is-admin rows)
                       (some :is-owner rows))))
        (ex/raise :type :not-found
                  :code :object-not-found
                  :hint "not found")
        rows))))

(defn make-read-check-fn
  "A simple factory for read permission check functions."
  [qfn]
  (us/assert fn? qfn)
  (fn [& args]
    (let [rows (apply qfn args)]
      (if-not (seq rows)
        (ex/raise :type :not-found
                  :code :object-not-found)
        rows))))
