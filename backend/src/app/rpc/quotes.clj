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
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [cuerdas.core :as str]))

(defmulti check-quote ::id)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:quote
  [:map {:title "Quote"}
   [::team-id {:optional true} ::sm/uuid]
   [::project-id {:optional true} ::sm/uuid]
   [::file-id {:optional true} ::sm/uuid]
   [::incr {:optional true} [::sm/int {:min 0}]]
   [::id :keyword]
   [::profile-id ::sm/uuid]])

(def valid-quote?
  (sm/lazy-validator schema:quote))

(def ^:private enabled (volatile! true))

(defn enable!
  "Enable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly true)))

(defn disable!
  "Disable quotes checking at runtime (from server REPL)."
  []
  (vswap! enabled (constantly false)))

(defn- check
  [cfg quote]
  (let [quote (merge cfg quote)
        id    (::id quote)]

    (when-not (valid-quote? quote)
      (ex/raise :type :internal
                :code :invalid-quote-definition
                :hint "found invalid data for quote schema"
                :quote (name id)))

    (-> (assoc quote ::target (name id))
        (check-quote))))

(defn check!
  ([cfg]
   (when (contains? cf/flags :quotes)
     (when @enabled
       (db/run! cfg check {}))))

  ([cfg & others]
   (when (contains? cf/flags :quotes)
     (when @enabled
       (db/run! cfg (fn [cfg]
                      (run! (partial check cfg) others)))))))

(defn- send-notification!
  [{:keys [::db/conn] :as params}]
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
      (wrk/submit! {::db/conn conn
                    ::wrk/task :sendmail
                    ::wrk/delay (dt/duration "30s")
                    ::wrk/max-retries 4
                    ::wrk/priority 200
                    ::wrk/dedupe true
                    ::wrk/label "quotes-notification"
                    ::wrk/params {:to (vec admins)
                                  :subject subject
                                  :body [{:type "text/plain"
                                          :content content}]}}))))

(defn- generic-check!
  [{:keys [::db/conn ::incr ::quote-sql ::count-sql ::default ::target] :or {incr 1} :as params}]
  (let [quote (->> (db/exec! conn quote-sql)
                   (map :quote)
                   (reduce max (- Integer/MAX_VALUE)))
        quote (if (pos? quote) quote default)
        total (:total (db/exec-one! conn count-sql))]

    (when (> (+ total incr) quote)
      (if (contains? cf/flags :soft-quotes)
        (send-notification! (assoc params ::quote quote ::total total))
        (ex/raise :type :restriction
                  :code :max-quote-reached
                  :target target
                  :quote quote
                  :count total)))))

(def ^:private sql:get-quotes-1
  "SELECT id, quote
     FROM usage_quote
    WHERE target = ?
      AND profile_id = ?
      AND team_id IS NULL
      AND project_id IS NULL
      AND file_id IS NULL;")

(def ^:private sql:get-quotes-2
  "SELECT id, quote
     FROM usage_quote
    WHERE target = ?
      AND ((team_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (profile_id = ? AND team_id IS NULL AND project_id IS NULL AND file_id IS NULL));")

(def ^:private sql:get-quotes-3
  "SELECT id, quote
     FROM usage_quote
    WHERE target = ?
      AND ((project_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (team_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (profile_id = ? AND team_id IS NULL AND project_id IS NULL AND file_id IS NULL));")

(def ^:private sql:get-quotes-4
  "SELECT id, quote
     FROM usage_quote
    WHERE target = ?
      AND ((file_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (project_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (team_id = ? AND (profile_id = ? OR profile_id IS NULL)) OR
           (profile_id = ? AND team_id IS NULL AND project_id IS NULL AND file_id IS NULL));")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAMS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:teams-per-profile
  [:map [::profile-id ::sm/uuid]])

(def ^:private valid-teams-per-profile-quote?
  (sm/lazy-validator schema:teams-per-profile))

(def ^:private sql:get-teams-per-profile
  "SELECT count(*) AS total
     FROM team_profile_rel
    WHERE profile_id = ?")

(defmethod check-quote ::teams-per-profile
  [{:keys [::profile-id ::target] :as quote}]
  (assert (valid-teams-per-profile-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-teams-per-profile Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-1 target profile-id])
      (assoc ::count-sql [sql:get-teams-per-profile profile-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: ACCESS-TOKENS-PER-PROFILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:access-tokens-per-profile
  [:map [::profile-id ::sm/uuid]])

(def ^:private valid-access-tokens-per-profile-quote?
  (sm/lazy-validator schema:access-tokens-per-profile))

(def ^:private sql:get-access-tokens-per-profile
  "SELECT count(*) AS total
     FROM access_token
    WHERE profile_id = ?")

(defmethod check-quote ::access-tokens-per-profile
  [{:keys [::profile-id ::target] :as quote}]
  (assert (valid-access-tokens-per-profile-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-access-tokens-per-profile Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-1 target profile-id])
      (assoc ::count-sql [sql:get-access-tokens-per-profile profile-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROJECTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:projects-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-projects-per-team-quote?
  (sm/lazy-validator schema:projects-per-team))

(def ^:private sql:get-projects-per-team
  "SELECT count(*) AS total
     FROM project AS p
    WHERE p.team_id = ?
      AND p.deleted_at IS NULL")

(defmethod check-quote ::projects-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-projects-per-team-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-projects-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-projects-per-team team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FONT-VARIANTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:font-variants-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-font-variant-per-team-quote?
  (sm/lazy-validator schema:font-variants-per-team))

(def ^:private sql:get-font-variants-per-team
  "SELECT count(*) AS total
     FROM team_font_variant AS v
     WHERE v.team_id = ?")

(defmethod check-quote ::font-variants-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-font-variant-per-team-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-font-variants-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-font-variants-per-team team-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: INVITATIONS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:invitations-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-invitations-per-team-quote?
  (sm/lazy-validator schema:invitations-per-team))

(def ^:private sql:get-invitations-per-team
  "SELECT count(*) AS total
     FROM team_invitation
    WHERE team_id = ?")

(defmethod check-quote ::invitations-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-invitations-per-team-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-invitations-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-invitations-per-team team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: PROFILES-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:profiles-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-profiles-per-team-quote?
  (sm/lazy-validator schema:profiles-per-team))

(def ^:private sql:get-profiles-per-team
  "SELECT (SELECT count(*)
             FROM team_profile_rel
            WHERE team_id = ?) +
          (SELECT count(*)
             FROM team_invitation
            WHERE team_id = ?
              AND valid_until > now()) AS total;")

;; NOTE: the total number of profiles is determined by the number of
;; effective members plus ongoing valid invitations.

(defmethod check-quote ::profiles-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-profiles-per-team-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-profiles-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-profiles-per-team team-id team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: FILES-PER-PROJECT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:files-per-project
  [:map
   [::profile-id ::sm/uuid]
   [::project-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-files-per-project-quote?
  (sm/lazy-validator schema:files-per-project))

(def ^:private sql:get-files-per-project
  "SELECT count(*) AS total
     FROM file AS f
    WHERE f.project_id = ?
      AND f.deleted_at IS NULL")

(defmethod check-quote ::files-per-project
  [{:keys [::profile-id ::project-id ::team-id ::target] :as quote}]
  (assert (valid-files-per-project-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-files-per-project Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-3 target project-id profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-files-per-project project-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: COMMENT-THREADS-PER-FILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:comment-threads-per-file
  [:map
   [::profile-id ::sm/uuid]
   [::project-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-comment-threads-per-file-quote?
  (sm/lazy-validator schema:comment-threads-per-file))

(def ^:private sql:get-comment-threads-per-file
  "SELECT count(*) AS total
     FROM comment_thread AS ct
    WHERE ct.file_id = ?")

(defmethod check-quote ::comment-threads-per-file
  [{:keys [::profile-id ::file-id ::team-id ::project-id ::target] :as quote}]
  (assert (valid-comment-threads-per-file-quote? quote) "invalid quote parameters")

  (-> quote
      (assoc ::default (cf/get :quotes-comment-threads-per-file Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-4 target file-id profile-id project-id
                          profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-comment-threads-per-file file-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: COMMENTS-PER-FILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:comments-per-file
  [:map
   [::profile-id ::sm/uuid]
   [::project-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-comments-per-file-quote?
  (sm/lazy-validator schema:comments-per-file))

(def ^:private sql:get-comments-per-file
  "SELECT count(*) AS total
     FROM comment AS c
     JOIN comment_thread AS ct ON (ct.id = c.thread_id)
    WHERE ct.file_id = ?")

(defmethod check-quote ::comments-per-file
  [{:keys [::profile-id ::file-id ::team-id ::project-id ::target] :as quote}]
  (assert (valid-comments-per-file-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-comments-per-file Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-4 target file-id profile-id project-id
                          profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-comments-per-file file-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: SNAPSHOTS-PER-FILE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:snapshots-per-file
  [:map
   [::profile-id ::sm/uuid]
   [::project-id ::sm/uuid]
   [::team-id ::sm/uuid]
   [::file-id ::sm/uuid]])

(def ^:private valid-snapshots-per-file-quote?
  (sm/lazy-validator schema:snapshots-per-file))

(def ^:private sql:get-snapshots-per-file
  "SELECT count(*) AS total
     FROM file_change AS fc
    WHERE fc.file_id = ?
      AND fc.created_by = 'user'
      AND fc.deleted_at IS NULL
      AND fc.data IS NOT NULL")

(defmethod check-quote ::snapshots-per-file
  [{:keys [::profile-id ::file-id ::team-id ::project-id ::target] :as quote}]
  (assert (valid-snapshots-per-file-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-snapshots-per-file Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-4 target file-id profile-id project-id
                          profile-id team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-snapshots-per-file file-id])
      (generic-check!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: SNAPSHOTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:snapshots-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-snapshots-per-team-quote?
  (sm/lazy-validator schema:snapshots-per-team))

(def ^:private sql:get-snapshots-per-team
  "SELECT count(*) AS total
     FROM file_change AS fc
     JOIN file AS f ON (f.id = fc.file_id)
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND fc.created_by = 'user'
      AND fc.deleted_at IS NULL
      AND fc.data IS NOT NULL")

(defmethod check-quote ::snapshots-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-snapshots-per-team-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-snapshots-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-snapshots-per-team team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAM-ACCESS-REQUESTS-PER-TEAM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:team-access-requests-per-team
  [:map
   [::profile-id ::sm/uuid]
   [::team-id ::sm/uuid]])

(def ^:private valid-team-access-requests-per-team-quote?
  (sm/lazy-validator schema:team-access-requests-per-team))

(def ^:private sql:get-team-access-requests-per-team
  "SELECT count(*) AS total
     FROM team_access_request AS tar
    WHERE tar.team_id = ?")

(defmethod check-quote ::team-access-requests-per-team
  [{:keys [::profile-id ::team-id ::target] :as quote}]
  (assert (valid-team-access-requests-per-team-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-team-access-requests-per-team Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-2 target team-id profile-id profile-id])
      (assoc ::count-sql [sql:get-team-access-requests-per-team team-id])
      (generic-check!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; QUOTE: TEAM-ACCESS-REQUESTS-PER-REQUESTER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private schema:team-access-requests-per-requester
  [:map
   [::profile-id ::sm/uuid]])

(def ^:private valid-team-access-requests-per-requester-quote?
  (sm/lazy-validator schema:team-access-requests-per-requester))

(def ^:private sql:get-team-access-requests-per-requester
  "SELECT count(*) AS total
     FROM team_access_request AS tar
    WHERE tar.requester_id = ?")

(defmethod check-quote ::team-access-requests-per-requester
  [{:keys [::profile-id ::target] :as quote}]
  (assert (valid-team-access-requests-per-requester-quote? quote) "invalid quote parameters")
  (-> quote
      (assoc ::default (cf/get :quotes-team-access-requests-per-requester Integer/MAX_VALUE))
      (assoc ::quote-sql [sql:get-quotes-1 target profile-id])
      (assoc ::count-sql [sql:get-team-access-requests-per-requester profile-id])
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
