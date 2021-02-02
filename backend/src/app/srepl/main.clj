(ns app.srepl.main
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [app.common.pages :as cp]
   [app.common.pages.migrations :as pmg]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.profile-initial-data :as pid]
   [app.main :refer [system]]
   [app.rpc.queries.profile :as prof]
   [app.srepl.dev :as dev]
   [app.util.blob :as blob]
   [clojure.pprint :refer [pprint]]))

(defn update-file
  ([id f] (update-file id f false))
  ([id f save?]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [file (db/get-by-id conn :file id {:for-update true})
           file (-> file
                    (update :data app.util.blob/decode)
                    (update :data pmg/migrate-data)
                    (update :data f)
                    (update :data blob/encode)
                    (update :revn inc))]
       (when save?
         (db/update! conn :file
                     {:data (:data file)}
                     {:id (:id file)}))
       (update file :data blob/decode)))))

(defn update-file-raw
  [id data]
  (db/with-atomic [conn (:app.db/pool system)]
    (db/update! conn :file
                {:data data}
                {:id id})))

(defn get-file
  [id]
  (with-open [conn (db/open (:app.db/pool system))]
    (let [file (db/get-by-id conn :file id)]
      (-> file
          (update :data app.util.blob/decode)
          (update :data pmg/migrate-data)))))


;; Examples:
;; (def backup (update-file  #uuid "1586e1f0-3e02-11eb-b1d2-556a2f641513" identity))
;; (def x (update-file
;;         #uuid "1586e1f0-3e02-11eb-b1d2-556a2f641513"
;;         (fn [{:keys [data] :as file}]
;;           (update-in data [:pages-index #uuid "878278c0-3ef0-11eb-9d67-8551e7624f43" :objects] dissoc nil))))

(def default-project-id #uuid "5761a890-3b81-11eb-9e7d-556a2f641513")

(defn initial-data-dump
  ([system file] (initial-data-dump system default-project-id file))
  ([system project-id path]
   (db/with-atomic [conn (:app.db/pool system)]
     (pid/create-initial-data-dump conn project-id path))))

(defn load-data-into-user
  ([system user-email]
   (if-let [file (:initial-data-file cfg/config)]
     (load-data-into-user system file user-email)
     (prn "Data file not found in configuration")))

  ([system file user-email]
   (db/with-atomic [conn (:app.db/pool system)]
     (let [profile (prof/retrieve-profile-data-by-email conn user-email)
           profile (merge profile (prof/retrieve-additional-data conn (:id profile)))]
       (pid/create-profile-initial-data conn file profile)))))
