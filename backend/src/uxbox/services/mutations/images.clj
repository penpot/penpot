;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.services.mutations.images
  (:require
   [clojure.spec.alpha :as s]
   [datoteka.core :as fs]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.common.uuid :as uuid]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.media :as media]
   [uxbox.services.mutations :as sm]
   [uxbox.services.queries.teams :as teams]
   [uxbox.tasks :as tasks]
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

;; --- Create Library

(declare create-library)

(s/def ::create-image-library
  (s/keys :req-un [::profile-id ::team-id ::name]
          :opt-un [::id]))

(sm/defmutation ::create-image-library
  [{:keys [profile-id team-id] :as params}]
  (db/with-atomic [conn db/pool]
    (teams/check-edition-permissions! conn profile-id team-id)
    (create-library conn params)))

(defn create-library
  [conn {:keys [id team-id name]}]
  (let [id (or id (uuid/next))]
    (db/insert! conn :image-library
                {:id id
                 :team-id team-id
                 :name name})))


;; --- Rename Library

(declare select-file-for-update)

(s/def ::rename-image-library
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image-library
  [{:keys [profile-id id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-file-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))
      (db/update! conn :image-library
                  {:name name}
                  {:id id}))))

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


;; --- Delete Library

(declare delete-library)

(s/def ::delete-image-library
  (s/keys :req-un [::profile-id ::id]))

(sm/defmutation ::delete-image-library
  [{:keys [id profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [lib (select-file-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id lib))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image-library}})

      (db/update! conn :image-library
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))


;; --- Create Image (Upload and create from url)

(declare create-image)
(declare persist-image-on-fs)
(declare persist-image-thumbnail-on-fs)

(def valid-image-types?
  #{"image/jpeg", "image/png", "image/webp", "image/svg+xml"})

(s/def :uxbox$upload/filename ::us/string)
(s/def :uxbox$upload/size ::us/integer)
(s/def :uxbox$upload/content-type valid-image-types?)
(s/def :uxbox$upload/tempfile any?)

(s/def ::upload
  (s/keys :req-un [:uxbox$upload/filename
                   :uxbox$upload/size
                   :uxbox$upload/tempfile
                   :uxbox$upload/content-type]))

(s/def ::content ::upload)

(s/def ::add-image-from-url
  (s/keys :req-un [::profile-id ::file-id ::url]
          :opt-un [::id]))

(s/def ::upload-image
  (s/keys :req-un [::profile-id ::file-id ::name ::content]
          :opt-un [::id]))

(sm/defmutation ::add-image-from-url
  [{:keys [profile-id file-id url] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (let [content (images/download-image url)
            params' (merge params {:content content
                                   :name (:filename content)})]
        (create-image conn params')))))

(sm/defmutation ::upload-image
  [{:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [file (select-file-for-update conn file-id)]
      (teams/check-edition-permissions! conn profile-id (:team-id file))
      (create-image conn params))))

(defn create-image
  [conn {:keys [id content file-id name]}]
  (when-not (valid-image-types? (:content-type content))
    (ex/raise :type :validation
              :code :image-type-not-allowed
              :hint "Seems like you are uploading an invalid image."))

  (let [info  (images/run {:cmd :info :input {:path (:tempfile content)
                                              :mtype (:content-type content)}})
        path  (persist-image-on-fs content)
        opts  (assoc thumbnail-options
                    :input {:mtype (:mtype info)
                            :path path})
        thumb (if-not (= (:mtype info) "image/svg+xml")
                (persist-image-thumbnail-on-fs opts)
                (assoc info
                       :path path
                       :quality 0))]

    (-> (db/insert! conn :image
                    {:id (or id (uuid/next))
                     :file-id file-id
                     :name name
                     :path (str path)
                     :width (:width info)
                     :height (:height info)
                     :mtype  (:mtype info)
                     :thumb-path (str (:path thumb))
                     :thumb-width (:width thumb)
                     :thumb-height (:height thumb)
                     :thumb-quality (:quality thumb)
                     :thumb-mtype (:mtype thumb)})
        (images/resolve-urls :path :uri)
        (images/resolve-urls :thumb-path :thumb-uri))))

(defn persist-image-on-fs
  [{:keys [filename tempfile]}]
  (let [filename (fs/name filename)]
    (ust/save! media/media-storage filename tempfile)))

(defn persist-image-thumbnail-on-fs
  [{:keys [input] :as params}]
  (let [path  (ust/lookup media/media-storage (:path input))
        thumb (images/run
                (-> params
                    (assoc :cmd :generic-thumbnail)
                    (update :input assoc :path path)))

        name  (str "thumbnail-"
                   (first (fs/split-ext (fs/name (:path input))))
                   (images/format->extension (:format thumb)))
        path  (ust/save! media/media-storage name (:data thumb))]

    (-> thumb
        (dissoc :data :input)
        (assoc :path path))))

;; --- Mutation: Rename Image

(declare select-image-for-update)

(s/def ::rename-image
  (s/keys :req-un [::id ::profile-id ::name]))

(sm/defmutation ::rename-image
  [{:keys [id profile-id name] :as params}]
  (db/with-atomic [conn db/pool]
    (let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))
      (db/update! conn :image
                  {:name name}
                  {:id id}))))

(def ^:private sql:select-image-for-update
  "select img.*,
          lib.team_id as team_id
     from image as img
    inner join image_library as lib on (lib.id = img.library_id)
    where img.id = ?
      for update of img")

(defn- select-image-for-update
  [conn id]
  (let [row (db/exec-one! conn [sql:select-image-for-update id])]
    (when-not row
      (ex/raise :type :not-found))
    row))

;; --- Delete Image

(s/def ::delete-image
  (s/keys :req-un [::id ::profile-id]))

(sm/defmutation ::delete-image
  [{:keys [profile-id id] :as params}]
  (db/with-atomic [conn db/pool]
    (let [img (select-image-for-update conn id)]
      (teams/check-edition-permissions! conn profile-id (:team-id img))

      ;; Schedule object deletion
      (tasks/submit! conn {:name "delete-object"
                           :delay cfg/default-deletion-delay
                           :props {:id id :type :image}})

      (db/update! conn :image
                  {:deleted-at (dt/now)}
                  {:id id})
      nil)))
