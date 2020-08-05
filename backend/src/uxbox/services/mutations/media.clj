;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.mutations.media
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.teams :as teams]
   [uxbox.tasks :as tasks]
   [uxbox.media-storage :as mst]
   [uxbox.util.storage :as ust]
   [uxbox.util.time :as dt]))

(def thumbnail-options
  {:width 800
   :height 800
   :quality 85
   :format :jpeg})

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::team-id ::us/uuid)
(s/def ::url ::us/url)

;; ;; --- Create Library
;;
;; (declare create-library)
;;
;; (s/def ::create-media-object-library
;;   (s/keys :req-un [::profile-id ::team-id ::name]
;;           :opt-un [::id]))
;;
;; (sm/defmutation ::create-media-object-library
;;   [{:keys [profile-id team-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (teams/check-edition-permissions! conn profile-id team-id)
;;     (create-library conn params)))
;;
;; (defn create-library
;;   [conn {:keys [id team-id name]}]
;;   (let [id (or id (uuid/next))]
;;     (db/insert! conn :media-object-library
;;                 {:id id
;;                  :team-id team-id
;;                  :name name})))
;;
;;
;; ;; --- Rename Library
;;
;; (s/def ::rename-media-object-library
;;   (s/keys :req-un [::id ::profile-id ::name]))
;;
;; (sm/defmutation ::rename-media-object-library
;;   [{:keys [profile-id id name] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-file-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;       (db/update! conn :media-object-library
;;                   {:name name}
;;                   {:id id}))))
;;
;;
;; ;; --- Delete Library
;;
;; (declare delete-library)
;;
;; (s/def ::delete-media-object-library
;;   (s/keys :req-un [::profile-id ::id]))
;;
;; (sm/defmutation ::delete-media-object-library
;;   [{:keys [id profile-id] :as params}]
;;   (db/with-atomic [conn db/pool]
;;     (let [lib (select-file-for-update conn id)]
;;       (teams/check-edition-permissions! conn profile-id (:team-id lib))
;;
;;       ;; Schedule object deletion
;;       (tasks/submit! conn {:name "delete-object"
;;                            :delay cfg/default-deletion-delay
;;                            :props {:id id :type :media-object-library}})
;;
;;       (db/update! conn :media-object-library
;;                   {:deleted-at (dt/now)}
;;                   {:id id})
;;       nil)))


;; --- Create Media object (Upload and create from url)

(declare create-media-object)
(declare select-file-for-update)
(declare persist-media-object-on-fs)
(declare persist-media-thumbnail-on-fs)

(def valid-media-object-types?
  #{"image/jpeg", "image/png", "image/webp", "image/svg+xml"})

(s/def :uxbox$upload/filename ::us/string)
(s/def :uxbox$upload/size ::us/integer)
(s/def :uxbox$upload/content-type valid-media-object-types?)
(s/def :uxbox$upload/tempfile any?)

(s/def ::upload
  (s/keys :req-un [:uxbox$upload/filename
                   :uxbox$upload/size
                   :uxbox$upload/tempfile
                   :uxbox$upload/content-type]))

(s/def ::content ::upload)

(s/def ::is-local boolean?)

(s/def ::add-media-object-from-url
  (s/keys :req-un [::profile-id ::file-id ::url ::is-local]
          :opt-un [::id]))

(s/def ::upload-media-object
  (s/keys :req-un [::profile-id ::file-id ::name ::content ::is-local]
          :opt-un [::id]))

(sm/defmutation ::add-media-object-from-url
  [{:keys [profile-id file-id url] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (let [content (media/download-media-object url)
            params' (merge params {:content content
                                   :name (:filename content)})]
        (create-media-object conn params')))))

(sm/defmutation ::upload-media-object
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (create-media-object conn params))))

(defn create-media-object
  [conn {:keys [id content file-id name is-local]}]
  (when-not (valid-media-object-types? (:content-type content))
    (ex/raise :type :validation
              :code :media-type-not-allowed
              :hint "Seems like you are uploading an invalid media object."))

  (let [info  (media/run {:cmd :info :input {:path (:tempfile content)
                                             :mtype (:content-type content)}})
        path  (persist-media-object-on-fs content)
        opts  (assoc thumbnail-options
                    :input {:mtype (:mtype info)
                            :path path})
        thumb (if-not (= (:mtype info) "image/svg+xml")
                (persist-media-thumbnail-on-fs opts)
                (assoc info
                       :path path
                       :quality 0))

        media-object-id (or id (uuid/next))

        media-object (-> (db/insert! conn :media-object
                                     {:id media-object-id
                                      :file-id file-id
                                      :is-local is-local
                                      :name name
                                      :path (str path)
                                      :width (:width info)
                                      :height (:height info)
                                      :mtype  (:mtype info)})
                         (media/resolve-urls :path :uri)
                         (media/resolve-urls :thumb-path :thumb-uri))

        media-thumbnail (db/insert! conn :media-thumbnail
                                    {:id (uuid/next)
                                     :media-object-id media-object-id
                                     :path (str (:path thumb))
                                     :width (:width thumb)
                                     :height (:height thumb)
                                     :quality (:quality thumb)
                                     :mtype (:mtype thumb)})]
    media-object))

(def ^:private sql:select-file-for-update
  "select file.*,
          project.team_id as team_id
     from file
    inner join project on (project.id = file.project_id)
    where file.id = ?
      for update of file")

(defn- select-file-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-file-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

(defn persist-media-object-on-fs
  [{:keys [filename tempfile]}]
  (let [filename (fs/name filename)]
    (ust/save! mst/media-storage filename tempfile)))

(defn persist-media-thumbnail-on-fs
  [{:keys [input] :as params}]
  (let [path  (ust/lookup mst/media-storage (:path input))
        thumb (media/run
                (-> params
                    (assoc :cmd :generic-thumbnail)
                    (update :input assoc :path path)))

        name  (str "thumbnail-"
                   (first (fs/split-ext (fs/name (:path input))))
                   (media/format->extension (:format thumb)))
        path  (ust/save! mst/media-storage name (:data thumb))]

    (-> thumb
        (dissoc :data :input)
        (assoc :path path))))

;; --- Mutation: Rename Media object

(declare select-media-object-for-update)

(s/def ::rename-media-object
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-media-object
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [obj (select-media-object-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id obj))
      (db/update! conn :media-object
                  {:name name}
                  {:id id}))))

(def ^:private sql:select-media-object-for-update
  "select obj.*,
          p.team_id as team_id
     from media_object as obj
    inner join file as f on (f.id = obj.file_id)
    inner join project as p on (p.id = f.project_id)
    where obj.id = ?
      for update of obj")

(defn- select-media-object-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-media-object-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

;; --- Delete Media object

(s/def ::delete-media-object
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-media-object
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [obj (select-media-object-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id obj))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :media-object}})

      (db/update! conn :media-object
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
