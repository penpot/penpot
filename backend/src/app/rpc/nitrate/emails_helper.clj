;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.nitrate.emails-helper
  "Helpers for organization SSO notification emails triggered by Nitrate integration."
  (:require
   [app.common.data :as d]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.nitrate :as nitrate]
   [app.rpc.commands.teams :as teams]
   [app.rpc.nitrate.organization-helper :as neh]
   [cuerdas.core :as str]))

(def ^:private sql:get-profile-emails-by-ids
  "SELECT email
     FROM profile
    WHERE id = ANY(?)
      AND deleted_at IS NULL")

(def ^:private sql:get-profiles-by-emails
  "SELECT id, email, fullname, is_muted
     FROM profile
    WHERE email = ANY(?)
      AND deleted_at IS NULL")

(defn- org-sso-active?
  "Return whether SSO is enabled for the organization."
  [cfg organization-id]
  (when (contains? cf/flags :nitrate)
    (true? (:active (nitrate/call cfg :get-org-sso {:organization-id organization-id})))))

(def ^:private xf:map-email (map :email))

(defn- recipients-by-emails
  "Build `{:email :user-name :profile}` maps for a deduplicated email list."
  [conn emails]
  (let [profiles (if (seq emails)
                   (let [emails-array (db/create-array conn "text" emails)]
                     (db/exec! conn [sql:get-profiles-by-emails emails-array]))
                   [])
        profile-by-email (d/index-by (comp str/lower :email) profiles)]
    (map (fn [email]
           (let [profile (get profile-by-email (str/lower email))]
             {:email email
              :user-name (:fullname profile)
              :profile profile}))
         emails)))

(defn- send-organization-setup-sso-email!
  "Send the organization SSO setup email to a single recipient, when allowed."
  [conn organization-name {:keys [email user-name profile]}]
  (when (or (nil? profile)
            (eml/allow-send-emails? conn profile))
    (eml/send! {::eml/conn conn
                ::eml/factory eml/organization-setup-sso
                :public-uri (cf/get :public-uri)
                :to email
                :user-name user-name
                :organization-name organization-name})))

(defn- get-org-sso-notify-recipients
  "Unique org members and pending org/team invitees for SSO activation emails."
  [conn cfg organization-id org-summary]
  (let [member-ids    (nitrate/call cfg :get-org-members {:organization-id organization-id})
        team-ids      (neh/get-org-team-ids org-summary)
        member-emails (if (seq member-ids)
                        (let [ids-array (db/create-array conn "uuid" member-ids)]
                          (into #{} (map :email (db/exec! conn [sql:get-profile-emails-by-ids ids-array]))))
                        #{})
        invite-emails (into #{} (map :email
                                     (neh/get-org-invitations conn organization-id team-ids)))
        emails        (into #{} (concat member-emails invite-emails))]
    (recipients-by-emails conn emails)))

(defn- get-team-sso-notify-recipients
  "Team members who are not in `org-member-ids`, plus pending team invitations."
  [conn team-id org-member-ids]
  (let [team-members (->> (teams/get-team-members conn team-id)
                          (remove #(contains? org-member-ids (:id %))))
        invitations  (neh/get-team-invitation-emails conn team-id)]
    (->> (sequence xf:map-email (concat team-members invitations))
         (recipients-by-emails conn))))

(defn send-organization-setup-sso-emails!
  "Notify all org members and pending org/team invitees that SSO is active."
  [cfg organization-id]
  (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})]
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (doseq [recipient (get-org-sso-notify-recipients conn cfg organization-id org-summary)]
                    (send-organization-setup-sso-email! conn (:name org-summary) recipient))))))

(defn send-organization-setup-sso-emails-for-team!
  "Notify team members who are not in `org-member-ids-before` and pending team invitees."
  [cfg organization-id team-id org-member-ids-before]
  (when (org-sso-active? cfg organization-id)
    (let [org-summary (nitrate/call cfg :get-org-summary {:organization-id organization-id})]
      (db/tx-run! cfg
                  (fn [{:keys [::db/conn]}]
                    (doseq [recipient (get-team-sso-notify-recipients conn team-id org-member-ids-before)]
                      (send-organization-setup-sso-email! conn (:name org-summary) recipient)))))))
