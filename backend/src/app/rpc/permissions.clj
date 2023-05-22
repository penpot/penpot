;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.permissions
  "A permission checking helper factories."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

(sm/def! ::permissions
  [:map {:title "Permissions"}
   [:type {:gen/elements [:membership :share-link]} :keyword]
   [:is-owner :boolean]
   [:is-admin :boolean]
   [:can-edit :boolean]
   [:can-read :boolean]
   [:is-logged :boolean]])


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

(defn make-admin-predicate-fn
  "A simple factory for admin permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn check
    ([perms] (:is-admin perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-edition-predicate-fn
  "A simple factory for edition permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn check
    ([perms] (:can-edit perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-read-predicate-fn
  "A simple factory for read permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn check
    ([perms] (:can-read perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-comment-predicate-fn
  "A simple factory for comment permission predicate functions."
  [qfn]
  (us/assert fn? qfn)
  (fn check
    ([perms]
     (and (:is-logged perms) (= (:who-comment perms) "all")))
    ([conn & args]
     (check (apply qfn conn args)))))

(defn make-check-fn
  "Helper that converts a predicate permission function to a check
  function (function that raises an exception)."
  [pred]
  (fn [& args]
    (when-not (apply pred args)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "not found"))))
