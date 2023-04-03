;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.quotes
  "Penpot resource usage quotes."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(defmulti check-quote ::id)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::conn ::db/pool-or-conn)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::incr (s/and int? pos?))
(s/def ::target ::us/string)

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
  (us/assert! ::db/pool-or-conn conn)
  (us/assert! ::quote quote)
  (when (contains? cf/flags :quotes)
    (when @enabled
      (check-quote (assoc quote ::conn conn ::target (name (::id quote)))))))

(defn- send-notification!
  [{:keys [::conn] :as params}]
  (l/warn :hint "max quote reached"
          :target (::target params)
          :profile-id (some-> params ::profile-id str)
          :team-id (some-> params ::team-id str)
          :project-id (some-> params ::project-id str)
          :file-id (some-> params ::file-id str)
          :quote (::quote params)
          :total (::total params)
          :incr  (::inc params 1))

  (when-let [admins (seq (cf/get :admins))]
    (let [subject (str/istr "[quotes:notification]: max quote reached ~(::target params)")
          content (str/istr "- Param: profile-id '~(::profile-id params)}'\n"
                            "- Param: team-id '~(::team-id params)'\n"
                            "- Param: project-id '~(::project-id params)'\n"
                            "- Param: file-id '~(::file-id params)'\n"
                            "- Quote ID: '~(::target params)'\n"
                            "- Max: ~(::quote params)\n"
                            "- Total: ~(::total params) (INCR ~(::incr params 1))\n")]
      (wrk/submit! {::wrk/task :sendmail
                    ::wrk/delay (dt/duration "30s")
                    ::wrk/max-retries 4
                    ::wrk/priority 200
                    ::wrk/conn conn
                    ::wrk/dedupe true
                    ::wrk/label "quotes-notification"
                    :to (vec admins)
                    :subject subject
                    :body [{:type "text/plain"
                            :content content}]}))))

(defn- generic-check!
  [{:keys [::conn ::incr ::quote-sql ::count-sql ::default ::target] :or {incr 1} :as params}]
  (let [quote (->> (db/exec! conn quote-sql)
                   (map :quote)
                   (reduce max (- Integer/MAX_VALUE)))
        quote (if (pos? quote) quote default)
        total (->> (db/exec! conn count-sql) first :total)]

    (when (> (+ total incr) quote)
      (if (contains? cf/flags :soft-quotes)
        (send-notification! (assoc params ::quote quote ::total total))
        (ex/raise :type :restriction
                  :code :max-quote-reached
                  :target target
                  :quote quote
                  :count total)))))

(def ^:private sql:get-quotes-1
  "select id, quote from usage_quote
    where target = ?
      and profile_id = ?
      and team_id is null
      and project_id is null
      and file_id is null;")

(def ^:private sql:get-quotes-2
  "select id, quote from usage_quote
    where target = ?
      and ((team_id = ? and (profile_id = ? or profile_id is null)) or
           (profile_id = ? and team_id is null and project_id is null and file_id is null));")

(def ^:private sql:get-quotes-3
  "select id, quote from usage_quote
    where target = ?
      and ((project_id = ? and (profile_id = ? or profile_id is null)) or
           (team_id = ? and (profile_id = ? or profile_id is null)) or
           (profile_id = ? and team_id is null and project_id is null and file_id is null));")

(def ^:private sql:get-quotes-4
  "select id, quote from usage_quote
    where target = ?
      and ((file_id = ? and (profile_id = ? or profile_id is null)) or
           (project_id = ? and (profile_id = ? or profile_id is null)) or
           (team_id = ? and (profile_id = ? or profile_id is null)) or
           (profile_id = ? and team_id is null and project_id is null and file_id is null));")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAMS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-teams-per-profile
  "select count(*) as total
     from team_profile_rel
    where profile_id = ?")

(s/def ::profile-id ::us/uuid)
(s/def ::teams-per-profile
  (s/keys :req [::profile-id ::target]))

(defmethod check-quote ::teams-per-profile
  [{:keys [::profile-id ::target] :as quote}]
  (us/assert! ::teams-per-profile quote)
  (-> quote
      (assoc ::default (cf/get :quotes-teams-per-profile Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-1 target profile-id])
      (assoc ::count-sql [sql:get-teams-per-profile profile-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: ACCESS-TOKENS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-access-tokens-per-profile
  "select count(*) as total
     from access_token
    where profile_id = ?")

(s/def ::access-tokens-per-profile
  (s/keys :req [::profile-id ::target]))

(defmethod check-quote ::access-tokens-per-profile
  [{:keys [::profile-id ::target] :as quote}]
  (us/assert! ::access-tokens-per-profile quote)
  (-> quote
      (assoc ::default (cf/get :quotes-access-tokens-per-profile Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-1 target profile-id])
      (assoc ::count-sql [sql:get-access-tokens-per-profile profile-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROJECTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-projects-per-team
  "select count(*) as total
     from project as p
    where p.team_id = ?
      and p.deleted_at is null")

(s/def ::team-id ::us/uuid)
(s/def ::projects-per-team
  (s/keys :req [::profile-id ::team-id ::target]))

(defmethod check-quote ::projects-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (-> quote
      (assoc ::default (cf/get :quotes-projects-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-projects-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FONT-VARIANTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-font-variants-per-team
  "select count(*) as total
     from team_font_variant as v
     where v.team_id = ?")

(s/def ::font-variants-per-team
  (s/keys :req [::profile-id ::team-id ::target]))

(defmethod check-quote ::font-variants-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (us/assert! ::font-variants-per-team quote)
  (-> quote
      (assoc ::default (cf/get :quotes-font-variants-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-font-variants-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: INVITATIONS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-invitations-per-team
  "select count(*) as total
     from team_invitation
    where team_id = ?")

(s/def ::invitations-per-team
  (s/keys :req [::profile-id ::team-id ::target]))

(defmethod check-quote ::invitations-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (us/assert! ::invitations-per-team quote)
  (-> quote
      (assoc ::default (cf/get :quotes-invitations-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-invitations-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROFILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(s/def ::profiles-per-team
  (s/keys :req [::profile-id ::team-id ::target]))

(defmethod check-quote ::profiles-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (us/assert! ::profiles-per-team quote)
  (-> quote
      (assoc ::default (cf/get :quotes-profiles-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-profiles-per-team team-id team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-PROJECT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-files-per-project
  "select count(*) as total
     from file as f
    where f.project_id = ?
      and f.deleted_at is null")

(s/def ::project-id ::us/uuid)
(s/def ::files-per-project
  (s/keys :req [::profile-id ::project-id ::team-id ::target]))

(defmethod check-quote ::files-per-project
  [{:keys [::profile-id ::project-id ::team-id ::target] :as quote}]
  (us/assert! ::files-per-project quote)
  (-> quote
      (assoc ::default (cf/get :quotes-files-per-project Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-3 target project-id profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-files-per-project project-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: COMMENT-THREADS-PER-FILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-comment-threads-per-file
  "select count(*) as total
     from comment_thread as ct
    where ct.file_id = ?")

(s/def ::comment-threads-per-file
  (s/keys :req [::profile-id ::project-id ::team-id ::target]))

(defmethod check-quote ::comment-threads-per-file
  [{:keys [::profile-id ::file-id ::team-id ::project-id ::target] :as quote}]
  (us/assert! ::files-per-project quote)
  (-> quote
      (assoc ::default (cf/get :quotes-comment-threads-per-file Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-4 target file-id profile-id project-id
                          profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-comment-threads-per-file file-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: COMMENTS-PER-FILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:get-comments-per-file
  "select count(*) as total
     from comment as c
     join comment_thread as ct on (ct.id = c.thread_id)
    where ct.file_id = ?")

(s/def ::comments-per-file
  (s/keys :req [::profile-id ::project-id ::team-id ::target]))

(defmethod check-quote ::comments-per-file
  [{:keys [::profile-id ::file-id ::team-id ::project-id ::target] :as quote}]
  (us/assert! ::files-per-project quote)
  (-> quote
      (assoc ::default (cf/get :quotes-comments-per-file Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-4 target file-id profile-id project-id
                          profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-comments-per-file file-id])
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
