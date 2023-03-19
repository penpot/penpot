;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.objects-gc
  "A maintenance task that performs a general purpose garbage collection
  of deleted or unreachable objects."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.db :as db]
   [app.media :as media]
   [app.storage :as sto]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare ^:private delete-profiles!)
(declare ^:private delete-teams!)
(declare ^:private delete-fonts!)
(declare ^:private delete-projects!)
(declare ^:private delete-files!)
(declare ^:private delete-orphan-teams!)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool ::sto/storage]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age cf/deletion-delay))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool ::sto/storage] :as cfg}]
  (fn [params]
    (db/with-atomic [conn pool]
      (let [min-age (or (:min-age params) (::min-age cfg))
            _       (l/info :hint "gc started"
                            :min-age (dt/format-duration min-age)
                            :rollback? (boolean (:rollback? params)))

            storage (media/configure-assets-storage storage conn)
            cfg     (-> cfg
                        (assoc ::min-age (db/interval min-age))
                        (assoc ::conn conn)
                        (assoc ::storage storage))

            htotal  (+ (delete-profiles! cfg)
                       (delete-teams! cfg)
                       (delete-projects! cfg)
                       (delete-files! cfg)
                       (delete-fonts! cfg))
            stotal  (delete-orphan-teams! cfg)]

        (l/info :hint "gc finished"
                :deleted htotal
                :orphans stotal
                :rollback? (boolean (:rollback? params)))

        (when (:rollback? params)
          (db/rollback! conn))

        {:processed (+ stotal htotal)
         :orphans stotal}))))



(def ^:private sql:get-profiles-chunk
  "select id, photo_id, created_at from profile
    where deleted_at is not null
      and deleted_at < now() - ?::interval
      and created_at < ?
    order by created_at desc
    limit 10
    for update skip locked")

(defn- delete-profiles!
  [{:keys [::conn ::min-age ::storage] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-profiles-chunk min-age cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-profile [total {:keys [id photo-id]}]
            (l/debug :hint "permanently delete profile" :id (str id))

            ;; Mark as deleted the storage object related with the
            ;; photo-id field.
            (some->> photo-id (sto/touch-object! storage))

            ;; And finally, permanently delete the profile.
            (db/delete! conn :profile {:id id})

            (inc total))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-profile 0))))

(def ^:private sql:get-teams-chunk
  "select id, photo_id, created_at from team
    where deleted_at is not null
      and deleted_at < now() - ?::interval
      and created_at < ?
    order by created_at desc
    limit 10
    for update skip locked")

(defn- delete-teams!
  [{:keys [::conn ::min-age ::storage] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-teams-chunk min-age cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-team [total {:keys [id photo-id]}]
            (l/debug :hint "permanently delete team" :id (str id))

            ;; Mark as deleted the storage object related with the
            ;; photo-id field.
            (some->> photo-id (sto/touch-object! storage))

            ;; And finally, permanently delete the team.
            (db/delete! conn :team {:id id})

            (inc total))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-team 0))))

(def ^:private sql:get-orphan-teams-chunk
  "select t.id, t.created_at
     from team as t
     left join team_profile_rel as tpr
            on (t.id = tpr.team_id)
    where tpr.profile_id is null
      and t.created_at < ?
    order by t.created_at desc
    limit 10
      for update of t skip locked;")

(defn- delete-orphan-teams!
  "Find all orphan teams (with no members and mark them for
  deletion (soft delete)."
  [{:keys [::conn] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-orphan-teams-chunk cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-team [total {:keys [id]}]
            (let [result (db/update! conn :team
                                     {:deleted-at (dt/now)}
                                     {:id id :deleted-at nil}
                                     {::db/return-keys? false})
                  count  (db/get-update-count result)]
              (when (pos? count)
                (l/debug :hint "mark team for deletion" :id (str id) ))

              (+ total count)))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-team 0))))

(def ^:private sql:get-fonts-chunk
  "select id, created_at, woff1_file_id, woff2_file_id, otf_file_id, ttf_file_id
     from team_font_variant
    where deleted_at is not null
      and deleted_at < now() - ?::interval
      and created_at < ?
    order by created_at desc
    limit 10
    for update skip locked")

(defn- delete-fonts!
  [{:keys [::conn ::min-age ::storage] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-fonts-chunk min-age cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-font [total {:keys [id] :as font}]
            (l/debug :hint "permanently delete font variant" :id (str id))

            ;; Mark as deleted the all related storage objects
            (some->> (:woff1-file-id font) (sto/touch-object! storage))
            (some->> (:woff2-file-id font) (sto/touch-object! storage))
            (some->> (:otf-file-id font)   (sto/touch-object! storage))
            (some->> (:ttf-file-id font)   (sto/touch-object! storage))

            ;; And finally, permanently delete the team font variant
            (db/delete! conn :team-font-variant {:id id})

            (inc total))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-font 0))))

(def ^:private sql:get-projects-chunk
  "select id, created_at
     from project
    where deleted_at is not null
      and deleted_at < now() - ?::interval
      and created_at < ?
    order by created_at desc
    limit 10
    for update skip locked")

(defn- delete-projects!
  [{:keys [::conn ::min-age] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-projects-chunk min-age cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-project [total {:keys [id]}]
            (l/debug :hint "permanently delete project" :id (str id))
            ;; And finally, permanently delete the project.
            (db/delete! conn :project {:id id})

            (inc total))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-project 0))))

(def ^:private sql:get-files-chunk
  "select id, created_at
     from file
    where deleted_at is not null
      and deleted_at < now() - ?::interval
      and created_at < ?
    order by created_at desc
    limit 10
    for update skip locked")

(defn- delete-files!
  [{:keys [::conn ::min-age] :as cfg}]
  (letfn [(get-chunk [cursor]
            (let [rows (db/exec! conn [sql:get-files-chunk min-age cursor])]
              [(some->> rows peek :created-at) rows]))

          (process-file [total {:keys [id]}]
            (l/debug :hint "permanently delete file" :id (str id))
            ;; And finally, permanently delete the file.
            (db/delete! conn :file {:id id})
            (inc total))]

    (->> (d/iteration get-chunk :vf second :kf first :initk (dt/now))
         (reduce process-file 0))))
