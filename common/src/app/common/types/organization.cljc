;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.organization
  (:require
   [app.common.schema :as sm]))

(def schema:organization
  [:map
   [:id ::sm/uuid]
   [:name ::sm/text]
   [:slug ::sm/text]
   [:owner-id ::sm/uuid]
   [:avatar-bg-url ::sm/uri]
   [:logo-id {:optional true} [:maybe ::sm/uuid]]])


(def schema:team-with-organization
  [:map
   [:id ::sm/uuid]
   [:is-your-penpot :boolean]
   [:organization schema:organization]])

(def organization->team-keys
  "Mapping from organization field keys to their corresponding :organization-* team keys."
  [[:id            :organization-id]
   [:name          :organization-name]
   [:custom-photo  :organization-custom-photo]
   [:slug          :organization-slug]
   [:avatar-bg-url :organization-avatar-bg-url]
   [:owner-id      :organization-owner-id]])

(defn apply-organization
  "Updates a team map with organization fields sourced from org.
	Associates each org field to the corresponding :organization-* team key when
	the value is non-nil; dissociates the key otherwise. This correctly handles
	both attaching an org (all values present) and detaching one (org is nil or
	all fields absent)."
  [team organization]
  (let [id (:id organization)]
    (reduce (fn [acc [org-k team-k]]
              (let [v (get organization org-k)]
                (if (and id (some? v))
                  (assoc acc team-k v)
                  (dissoc acc team-k))))
            team
            organization->team-keys)))