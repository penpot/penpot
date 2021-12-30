;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.debug
  (:require
   [app.common.transit :as t]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.util.json :as json]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(defn retrieve-file-data
  [{:keys [pool]} {:keys [params path-params] :as request}]
  (let [id    (uuid/uuid (get path-params :id))
        file  (db/get-by-id pool :file id)
        wrap? (contains? params :wrap)]
    {:status 200
     :headers {"content-type" "application/transit+json"}
     :body (-> (:data file)
               (blob/decode)
               (t/encode-str {:type :json-verbose})
               (cond-> wrap? (json/write)))}))

(def sql:retrieve-range-of-changes
  "select revn, changes from file_change where file_id=? and revn >= ? and revn < ? order by revn")

(def sql:retrieve-single-change
  "select revn, changes from file_change where file_id=? and revn = ?")

(defn retrieve-file-changes
  [{:keys [pool]} {:keys [params path-params] :as request}]
  (let [id    (uuid/uuid (get path-params :id))
        revn  (:rev params "latest")
        file  (db/get-by-id pool :file id)
        wrap? (contains? params :wrap)]

    (cond
      (or (not file)
          (not revn))
      {:status 404 :body "not found"}

      (str/includes? revn ":")
      (let [[start end] (->> (str/split revn #":")
                             (map d/read-string))
            _ (prn "fofof" start end)
            items (db/exec! pool [sql:retrieve-range-of-changes
                                    id start end])
            items (->> items
                       (map :changes)
                       (map blob/decode)
                       (mapcat identity))]
        {:status 200
         :headers {"content-type" "application/transit+json"}
         :body (-> items
                   (t/encode-str {:type :json-verbose})
                   (cond-> wrap? (json/write)))})

      (d/num-string? revn)
      (let [item (db/exec-one! pool [sql:retrieve-single-change id (d/read-string revn)])
            _     (prn "KAKAKAKA")
            _    (clojure.pprint/pprint item)

            item (-> item
                     :changes
                     blob/decode)]
        {:status 200
         :headers {"content-type" "application/transit+json"}
         :body (-> item
                   (t/encode-str {:type :json-verbose})
                   (cond-> wrap? (json/write)))})

      :else
      {:status 400
       :body "bad arguments"})))



(defmethod ig/init-key ::handlers
  [_ {:keys [pool] :as cfg}]
  {:retrieve-file-data (partial retrieve-file-data cfg)
   :retrieve-file-changes (partial retrieve-file-changes cfg)})
