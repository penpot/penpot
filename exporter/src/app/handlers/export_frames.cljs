;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.handlers.export-frames
  (:require
   ["path" :as path]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as exc :include-macros true]
   [app.common.spec :as us]
   [app.handlers.resources :as rsc]
   [app.redis :as redis]
   [app.renderer.pdf :as rp]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private handle-export)
(declare ^:private create-pdf)
(declare ^:private export-frame)
(declare ^:private join-pdf)
(declare ^:private move-file)
(declare ^:private clean-tmp)

(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::frame-id ::us/uuid)
(s/def ::uri ::us/uri)

(s/def ::export
  (s/keys :req-un [::file-id ::page-id ::frame-id ::name]))

(s/def ::exports
  (s/every ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports]
          :opt-un [::uri ::name]))

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [exports uri] :as params}]
  (let [xform    (map #(assoc % :token auth-token :uri uri))
        exports  (sequence xform exports)]
    (handle-export exchange (assoc params :exports exports))))

(defn handle-export
  [exchange {:keys [exports wait uri name] :as params}]
  (let [topic       (-> exports first :file-id str)
        resource    (rsc/create :pdf (or name (-> exports first :name)))

        on-progress (fn [progress]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :status "running"
                                  :progress progress}]
                        (redis/pub! topic data)))

        on-complete (fn [resource]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :size (:size resource)
                                  :status "ended"}]
                        (redis/pub! topic data)))

        on-error    (fn [cause]
                      (let [data {:type :export-update
                                  :resource-id (:id resource)
                                  :status "error"
                                  :cause (ex-message cause)}]
                        (redis/pub! topic data)))

        proc        (create-pdf :resource resource
                                :items exports
                                :on-progress on-progress
                                :on-complete on-complete
                                :on-error on-error)]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))

(defn create-pdf
  [& {:keys [resource items on-progress on-complete on-error]
      :or {on-progress identity
           on-complete identity
           on-error identity}}]
  (p/let [progress (atom 0)
          tmpdir   (sh/create-tmpdir! "pdfexport")
          file-id  (-> items first :file-id)
          items    (into [] (map #(partial export-frame tmpdir %)) items)
          xform    (map (fn [export-fn]
                          #(p/finally
                             (export-fn)
                             (fn [result _]
                               (on-progress {:total (count items)
                                             :done (swap! progress inc)
                                             :name (:name result)})))))]
    (-> (reduce (fn [res export-fn]
                  (p/let [res res
                          out (export-fn)]
                    (cons (:path out) res)))
                (p/resolved nil)
                (into '() xform items))
        (p/then (partial join-pdf tmpdir file-id))
        (p/then (partial move-file resource))
        (p/then (partial clean-tmp tmpdir))
        (p/then (fn [resource]
                  (-> (sh/stat (:path resource))
                      (p/then #(merge resource %)))))
        (p/finally (fn [result cause]
                     (if cause
                       (on-error cause)
                       (on-complete result)))))))

(defn- export-frame
  [tmpdir {:keys [file-id page-id frame-id token uri] :as params}]
  (let [file-name (dm/fmt "%.pdf" frame-id)
        save-path (path/join tmpdir file-name)]
    (-> (rp/render {:name (dm/str frame-id)
                    :uri  uri
                    :suffix ""
                    :token token
                    :file-id file-id
                    :page-id page-id
                    :object-id frame-id
                    :scale 1
                    :save-path save-path})
        (p/then (fn [_]
                  {:name file-name
                   :path save-path})))))

(defn- join-pdf
  [tmpdir file-id paths]
  (let [output-path (path/join tmpdir (str file-id ".pdf"))
        paths-str   (str/join " " paths)]
    (-> (sh/run-cmd! (str "pdfunite " paths-str " " output-path))
        (p/then (constantly output-path)))))

(defn- move-file
  [{:keys [path] :as resource} output-path]
  (p/do
    (sh/move! output-path path)
    resource))

(defn- clean-tmp
  [tdpath data]
  (p/do!
    (sh/rmdir! tdpath)
    data))
