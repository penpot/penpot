(ns app.srepl.main
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.common.pages.migrations :as pmg]
   [app.config :as cfg]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.main :refer [system]]
   [app.rpc.queries.profile :as prof]
   [app.srepl.dev :as dev]
   [app.util.blob :as blob]
   [cuerdas.core :as str]
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
  [system id]
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

;; Migrate

(defn update-file-data-blob-format
  [system]
  (db/with-atomic [conn (:app.db/pool system)]
    (doseq [id (->> (db/exec! conn ["select id from file;"]) (map :id))]
      (let [{:keys [data]} (db/get-by-id conn :file id {:columns [:id :data]})]
        (prn "Updating file:" id)
        (db/update! conn :file
                    {:data (-> (blob/decode data)
                               (blob/encode {:version 2}))}
                    {:id id})))))


(defn duplicate-file
  "This is a raw version of duplication of file just only for forensic analysis"
  [system file-id email]
  (db/with-atomic [conn (:app.db/pool system)]
    (when-let [profile (some->> (prof/retrieve-profile-data-by-email conn (str/lower email))
                                (prof/populate-additional-data conn))]
      (when-let [file (db/exec-one! conn (sql/select :file {:id file-id}))]
        (let [params (assoc file
                            :id (uuid/next)
                            :project-id (:default-project-id profile))]
          (db/insert! conn :file params)
          (:id file))))))
