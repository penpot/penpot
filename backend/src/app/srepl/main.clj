(ns app.srepl.main
  "A  main namespace for server repl."
  #_:clj-kondo/ignore
  (:require
   [clojure.pprint :refer [pprint]]
   [app.db :as db]
   [app.main :refer [system]]
   [app.common.pages.migrations :as pmg]
   [app.util.blob :as blob]
   [app.common.pages :as cp]))

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

;; Examples
;; (def backup (update-file  #uuid "1586e1f0-3e02-11eb-b1d2-556a2f641513" identity))
;; (def x (update-file #uuid "1586e1f0-3e02-11eb-b1d2-556a2f641513" (fn [{:keys [data] :as file}] (update-in data [:pages-index #uuid "878278c0-3ef0-11eb-9d67-8551e7624f43" :objects] dissoc nil))))
