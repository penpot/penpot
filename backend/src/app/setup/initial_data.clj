;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.setup.initial-data
  (:refer-clojure :exclude [load])
  (:require
   [app.common.data :as d]
   [app.common.pages.migrations :as pmg]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.rpc.mutations.projects :as projects]
   [app.rpc.queries.profile :as profile]
   [app.util.blob :as blob]
   [clojure.walk :as walk]))

;; --- DUMP GENERATION

(def sql:file
  "select * from file where project_id = ?")

(def sql:file-library-rel
  "with file_ids as (select id from file where project_id = ?)
   select *
     from file_library_rel
     where file_id in (select id from file_ids)")

(def sql:file-media-object
  "with file_ids as (select id from file where project_id = ?)
   select *
     from file_media_object
     where file_id in (select id from file_ids)")

(defn dump
  ([system project-id] (dump system project-id nil))
  ([system project-id {:keys [skey project-name]
                       :or {project-name "Penpot Onboarding"}}]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [skey  (or skey (cfg/get :initial-project-skey))
           files (db/exec! conn [sql:file project-id])
           flibs (db/exec! conn [sql:file-library-rel project-id])
           fmeds (db/exec! conn [sql:file-media-object project-id])
           data  {:project-name project-name
                  :files files
                  :flibs flibs
                  :fmeds fmeds}]

       (db/delete! conn :server-prop {:id skey})
       (db/insert! conn :server-prop
                   {:id skey
                    :preload false
                    :content (db/tjson data)})
       skey))))


;; --- DUMP LOADING

(defn- process-file
  [file index]
  (letfn [(process-form [form]
            (cond-> form
              ;; Relink Components
              (and (map? form)
                   (uuid? (:component-file form)))
              (update :component-file #(get index % %))

              ;; Relink Image Shapes
              (and (map? form)
                   (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] #(get index % %))))

          ;; A function responsible to analize all file data and
          ;; replace the old :component-file reference with the new
          ;; ones, using the provided file-index
          (relink-shapes [data]
            (walk/postwalk process-form data))

          ;; A function responsible of process the :media attr of file
          ;; data and remap the old ids with the new ones.
          (relink-media [media]
            (reduce-kv (fn [res k v]
                         (let [id (get index k)]
                           (if (uuid? id)
                             (-> res
                                 (assoc id (assoc v :id id))
                                 (dissoc k))
                             res)))
                       media
                       media))]

    (update file :data
            (fn [data]
              (-> data
                  (blob/decode)
                  (assoc :id (:id file))
                  (pmg/migrate-data)
                  (update :pages-index relink-shapes)
                  (update :components relink-shapes)
                  (update :media relink-media)
                  (d/without-nils)
                  (blob/encode))))))

(defn- remap-id
  [item index key]
  (cond-> item
    (contains? item key)
    (assoc key (get index (get item key) (get item key)))))

(defn- retrieve-data
  [conn skey]
  (when-let [row (db/exec-one! conn ["select content from server_prop where id = ?" skey])]
    (when-let [content (:content row)]
      (when (db/pgobject? content)
        (db/decode-transit-pgobject content)))))

(defn load-initial-project!
  ([conn profile] (load-initial-project! conn profile nil))
  ([conn profile opts]
   (let [skey (or (:skey opts) (cfg/get :initial-project-skey))
         data (retrieve-data conn skey)]
     (when data
       (let [project (projects/create-project conn {:profile-id (:id profile)
                                                    :team-id (:default-team-id profile)
                                                    :name (:project-name data)})

             index   (as-> {} index
                       (reduce #(assoc %1 (:id %2) (uuid/next)) index (:files data))
                       (reduce #(assoc %1 (:id %2) (uuid/next)) index (:fmeds data)))

             flibs   (map #(remap-id % index :file-id) (:flibs data))

             files   (->> (:files data)
                          (map #(assoc % :id (get index (:id %))))
                          (map #(assoc % :project-id (:id project)))
                          (map #(process-file % index)))

             fmeds   (->> (:fmeds data)
                          (map #(assoc % :id (get index (:id %))))
                          (map #(remap-id % index :file-id)))

             fprofs  (map #(array-map :file-id (:id %)
                                      :profile-id (:id profile)
                                      :is-owner true
                                      :is-admin true
                                      :can-edit true) files)]

       (projects/create-project-profile conn {:project-id (:id project)
                                              :profile-id (:id profile)})

       (projects/create-team-project-profile conn {:team-id (:default-team-id profile)
                                                   :project-id (:id project)
                                                   :profile-id (:id profile)})

       ;; Re-insert into the database
       (doseq [params files]
         (db/insert! conn :file params))

       (doseq [params fprofs]
         (db/insert! conn :file-profile-rel params))

       (doseq [params flibs]
         (db/insert! conn :file-library-rel params))

       (doseq [params fmeds]
         (db/insert! conn :file-media-object params)))))))

(defn load
  [system {:keys [email] :as opts}]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (some->> email
                                (profile/retrieve-profile-data-by-email conn)
                                (profile/populate-additional-data conn))]
      (load-initial-project! conn profile opts)
      true)))

