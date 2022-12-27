;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.quotes
  "Penpot resource usage quotes."
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [clojure.spec.alpha :as s]))

(defmulti check-quote ::id)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::conn ::db/conn-or-pool)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::incr (s/and int? pos?))

(s/def ::quote
  (s/keys :req [::id ::profile-id]
          :opt [::conn
                ::team-id
                ::project-id
                ::file-id
                ::incr]))

(def ^:private enabled (volatile! true))

(defn enable!
  "Enable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly true)))

(defn disable!
  "Disable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly false)))

(defn check-quote!
  [conn quote]
  (us/assert! ::db/conn-or-pool conn)
  (us/assert! ::quote quote)
  (when (contains? cf/flags :quotes)
    (when @enabled
      (check-quote (assoc quote ::conn conn)))))

(defn- generic-check!
  [{:keys [::conn ::id ::incr ::quote-sql ::count-sql ::default] :or {incr 1}}]
  (let [quote (->> (db/exec! conn quote-sql)
                   (map :quote)
                   (reduce max (- Integer/MAX_VALUE)))
        quote (if (pos? quote) quote default)
        total (->> (db/exec! conn count-sql) first :total)]

    (when (> (+ total incr) quote)
      (ex/raise :type :restriction
                :code :max-quote-reached
                :target id
                :quote quote
                :count total))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAMS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-teams-per-profile-quote
  "select id, quote from usage_quote
    where target = 'team'
      and (profile_id is null or profile_id = ?);")

(def ^:private sql:get-teams-per-profile
  "select count(*) as total
     from team_profile_rel
    where profile_id = ?")

(defmethod check-quote ::teams-per-profile
  [{:keys [::profile-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-teams-per-profile Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-teams-per-profile-quote profile-id])
      (assoc ::count-sql [sql:get-teams-per-profile profile-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROJECTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-projects-per-team-quote
  "select id, quote from usage_quote
    where target = 'project'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-projects-per-team
  "select count(*) as total
     from project as p
     where p.team_id = ?")


(defmethod check-quote ::projects-per-team
  [{:keys [::profile-id ::team-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-projects-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-projects-per-team-quote team-id profile-id team-id])
      (assoc ::count-sql [sql:get-projects-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FONT-VARIANTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-font-variants-per-team-quote
  "select id, quote from usage_quote
    where target = 'font-variant'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-font-variants-per-team
  "select count(*) as total
     from team_font_variant as v
     where v.team_id = ?")

(defmethod check-quote ::font-variants-per-team
  [{:keys [::profile-id ::team-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-font-variants-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-font-variants-per-team-quote team-id profile-id team-id])
      (assoc ::count-sql [sql:get-font-variants-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: INVITATIONS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-invitations-per-team-quote
  "select id, quote from usage_quote
    where target = 'team-invitation'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-invitations-per-team
  "select count(*) as total
     from team_invitation
    where team_id = ?")

(defmethod check-quote ::invitations-per-team
  [{:keys [::profile-id ::team-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-invitations-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-invitations-per-team-quote team-id profile-id team-id])
      (assoc ::count-sql [sql:get-invitations-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROFILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-profiles-per-team-quote
  "select id, quote from usage_quote
    where target = 'team-member'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-profiles-per-team
  "select (select count(*)
             from team_profile_rel
            where team_id = ?) +
          (select count(*)
             from team_invitation
            where team_id = ?
              and valid_until > now()) as total;")

;; NOTE: the total number of profiles is determined by the number of
;; effective members plus ongoing valid invitations.

(defmethod check-quote ::profiles-per-team
  [{:keys [::profile-id ::team-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-profiles-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-profiles-per-team-quote team-id profile-id team-id])
      (assoc ::count-sql [sql:get-profiles-per-team team-id team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-PROJECT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-per-project-quote
  "select id, quote from usage_quote
    where target = 'file'
      and ((profile_id is null and project_id = ?) or
           (profile_id = ? and project_id = ?));")

(def ^:private sql:get-files-per-project
  "select count(*) as total
     from file as f
    where f.project_id = ?")

(defmethod check-quote ::files-per-project
  [{:keys [::profile-id ::project-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-files-per-project Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-files-per-project-quote project-id profile-id project-id])
      (assoc ::count-sql [sql:get-files-per-project project-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-per-team-quote
  "select id, quote from usage_quote
    where target = 'file'
      and ((profile_id is null and team_id = ?) or
           (profile_id = ? and team_id = ?));")

(def ^:private sql:get-files-per-team
  "select count(*) as total
     from file as f
     join project as p on (f.project_id=p.id)
    where p.team_id = ?")

(defmethod check-quote ::files-per-team
  [{:keys [::profile-id ::team-id] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-files-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-files-per-team-quote team-id profile-id team-id])
      (assoc ::count-sql [sql:get-files-per-team team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: DEFAULT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod check-quote :default
  [{:keys [::id]}]
  (ex/raise :type :internal
            :code :quote-not-defined
            :quote id
            :hint "backend using a quote identifier not defined"))
