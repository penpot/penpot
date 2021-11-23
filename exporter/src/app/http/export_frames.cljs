;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.export-frames
  (:require
   ["path" :as path]
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]
   [app.renderer.pdf :as rp]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::frame-id ::us/uuid)
(s/def ::frame-ids (s/coll-of ::frame-id :kind vector?))

(s/def ::handler-params
  (s/keys :req-un [::file-id ::page-id ::frame-ids]))

(defn- export-frame
  [tdpath file-id page-id token frame-id spaths]
  (p/let [spath  (path/join tdpath (str frame-id ".pdf"))
          result (rp/render {:name (str frame-id)
                             :suffix ""
                             :token token
                             :file-id file-id
                             :page-id page-id
                             :object-id frame-id
                             :scale 1
                             :save-path spath})]
    (conj spaths spath)))

(defn- join-files
  [tdpath file-id paths]
  (let [output-path (path/join tdpath (str file-id ".pdf"))
        paths-str   (str/join " " paths)]
    (-> (sh/run-cmd! (str "pdfunite " paths-str " " output-path))
        (p/then (constantly output-path)))))

(defn- clean-tmp-data
  [tdpath data]
  (p/do!
    (sh/rmdir! tdpath)
    data))

(defn export-frames-handler
  [{:keys [params cookies] :as request}]
  (let [{:keys [name file-id page-id frame-ids]} (us/conform ::handler-params params)
        token  (.get ^js cookies "auth-token")]
    (if (seq frame-ids)
      (p/let [tdpath (sh/create-tmpdir! "pdfexport-")
              data (-> (reduce (fn [promise frame-id]
                                 (p/then promise (partial export-frame tdpath file-id page-id token frame-id)))
                               (p/future [])
                               (reverse frame-ids))
                       (p/then  (partial join-files tdpath file-id))
                       (p/then  sh/read-file)
                       (p/then  (partial clean-tmp-data tdpath)))]
        {:status 200
         :body data
         :headers {"content-type" "application/pdf"
                   "content-length" (.-length data)}})
      {:status 204
       :body ""
       :headers {"content-type" "text/plain"}})))

