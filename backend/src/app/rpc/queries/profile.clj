;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.profile
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::old-password ::us/string)
(s/def ::password ::us/string)
(s/def ::path ::us/string)
(s/def ::user ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::theme ::us/string)

;; --- Query: Profile (own)

(declare decode-row)
(declare get-profile)
(declare strip-private-attrs)
(declare filter-props)

(s/def ::profile
  (s/keys :opt-un [::profile-id]))

(sv/defmethod ::profile
  {::rpc/auth false}
  [{:keys [::db/pool] :as cfg} {:keys [profile-id]}]
  ;; We need to return the anonymous profile object in two cases, when
  ;; no profile-id is in session, and when db call raises not found. In all other
  ;; cases we need to reraise the exception.
  (try
    (-> (get-profile pool profile-id)
        (strip-private-attrs)
        (update :props filter-props))
    (catch Throwable _
      {:id uuid/zero :fullname "Anonymous User"})))

(defn get-profile
  "Get profile by id. Throws not-found exception if no profile found."
  [conn id & {:as attrs}]
  (-> (db/get-by-id conn :profile id attrs)
      (decode-row)))

(def ^:private sql:profile-by-email
  "select p.* from profile as p
    where p.email = ?
      and (p.deleted_at is null or
           p.deleted_at > now())")

(defn get-profile-by-email
  "Returns a profile looked up by email or `nil` if not match found."
  [conn email]
  (->> (db/exec! conn [sql:profile-by-email (str/lower email)])
       (map decode-row)
       (first)))

;; --- HELPERS

(defn strip-private-attrs
  "Only selects a publicly visible profile attrs."
  [row]
  (dissoc row :password :deleted-at))

(defn filter-props
  "Removes all namespace qualified props from `props` attr."
  [props]
  (into {} (filter (fn [[k _]] (simple-ident? k))) props))

(defn decode-row
  [{:keys [props] :as row}]
  (cond-> row
    (db/pgobject? props "jsonb")
    (assoc :props (db/decode-transit-pgobject props))))
