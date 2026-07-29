;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.permissions
  "A permission checking helper factories."
  (:require
   [app.binfile.common :as bfc]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.db :as db]
   [app.nitrate :as nitrate]))

(def schema:permissions
  [:map {:title "Permissions"}
   [:type {:gen/elements [:membership :share-link]} :keyword]
   [:is-owner ::sm/boolean]
   [:is-admin ::sm/boolean]
   [:can-edit ::sm/boolean]
   [:can-read ::sm/boolean]
   [:is-logged ::sm/boolean]])

(def valid-roles
  #{:admin :owner :editor :viewer})

(defn assign-role-flags
  [params role]
  (assert (contains? valid-roles role) "expected a valid role")
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
  (assert (fn? qfn) "expected a function")
  (fn check
    ([perms] (:is-admin perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-edition-predicate-fn
  "A simple factory for edition permission predicate functions."
  [qfn]
  (assert (fn? qfn) "expected a function")
  (fn check
    ([perms] (:can-edit perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-read-predicate-fn
  "A simple factory for read permission predicate functions."
  [qfn]
  (assert (fn? qfn) "expected a function")
  (fn check
    ([perms] (:can-read perms))
    ([conn & args] (check (apply qfn conn args)))))

(defn make-comment-predicate-fn
  "A simple factory for comment permission predicate functions."
  [qfn]
  (assert (fn? qfn) "expected a function")
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

;; --- Organization owner (Nitrate) viewer access
;;
;; Read-permission helpers that augment normal Penpot membership with
;; Nitrate organization-owner viewer access. Edit/admin permission
;; providers intentionally stay membership-only.

(def viewer-role-flags
  "Role flags granted to a non-member organization owner: read-only.
  Shared so callers that build full team/file rows shape permissions the
  same way the permission lookups do."
  {:is-owner false
   :is-admin false
   :can-edit false})

(def ^:private sql:get-team-id-for-project
  "SELECT team_id FROM project WHERE id = ?")

(def ^:private sql:get-team-id-for-file
  "SELECT p.team_id
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE f.id = ?")

(defn get-team-id-for-project
  [cfg project-id]
  (some-> (db/exec-one! cfg [sql:get-team-id-for-project project-id])
          (:team-id)))

(defn get-team-id-for-file
  [cfg file-id]
  (some-> (db/exec-one! cfg [sql:get-team-id-for-file file-id])
          (:team-id)))

(defn resolve-team-id
  [cfg {:keys [team-id project-id file-id]}]
  (cond
    (some? team-id)    team-id
    (some? project-id) (get-team-id-for-project cfg project-id)
    (some? file-id)    (get-team-id-for-file cfg file-id)))

(defn get-organization-owner-permissions
  "When `profile-id` is a non-member owner of the organization that owns
  the team/project/file referenced by `params`, returns read-only viewer
  permissions; otherwise nil."
  [cfg profile-id & {:as params}]
  (when-let [team-id (resolve-team-id cfg params)]
    (when (nitrate/organization-owner-of-team? cfg profile-id team-id)
      (assoc viewer-role-flags
             :can-read true
             :type :membership
             :is-logged (some? profile-id)))))

(defn get-file-read-permissions
  ([cfg profile-id file-id]
   (or (bfc/get-file-permissions cfg profile-id file-id)
       (get-organization-owner-permissions cfg profile-id :file-id file-id)))

  ([cfg profile-id file-id share-id]
   (or (bfc/get-file-permissions cfg profile-id file-id share-id)
       (get-organization-owner-permissions cfg profile-id :file-id file-id))))
