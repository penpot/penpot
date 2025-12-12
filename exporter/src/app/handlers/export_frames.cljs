;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.handlers.export-frames
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.handlers.export-shapes :refer [prepare-exports]]
   [app.handlers.resources :as rsc]
   [app.redis :as redis]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private handle-export)
(declare ^:private create-pdf)
(declare ^:private join-pdf)
(declare ^:private move-file)

(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)

(s/def ::export
  (s/keys :req-un [::file-id ::page-id ::object-id ::name]))

(s/def ::exports
  (s/every ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports]
          :opt-un [::name]))

(defn handler
  [{:keys [:request/auth-token] :as exchange} {:keys [exports] :as params}]
  ;; NOTE: we need to have the `:type` prop because the exports
  ;; datastructure preparation uses it for creating the groups.
  (let [exports  (-> (map #(assoc % :type :pdf :scale 1 :suffix "") exports)
                     (prepare-exports auth-token))]

    (handle-export exchange (assoc params :exports exports))))

(defn handle-export
  [{:keys [:request/auth-token] :as exchange} {:keys [exports name profile-id] :as params}]
  (let [topic       (str profile-id)
        file-id     (-> exports first :file-id)

        resource
        (rsc/create :pdf (or name (-> exports first :name)))

        on-progress
        (fn [done]
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :status "running"
                      :done done}]
            (redis/pub! topic data)))

        on-complete
        (fn [resource]
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :resource-uri (:uri resource)
                      :name (:name resource)
                      :filename (:filename resource)
                      :mtype (:mtype resource)
                      :status "ended"}]
            (redis/pub! topic data)))

        on-error
        (fn [cause]
          (l/error :hint "unexpected error on frames exportation" :cause cause)
          (let [data {:type :export-update
                      :resource-id (:id resource)
                      :name (:name resource)
                      :filename (:filename resource)
                      :status "error"
                      :cause (ex-message cause)}]
            (redis/pub! topic data)))

        result-cache
        (atom [])

        on-object
        (fn [{:keys [path] :as object}]
          (let [res (swap! result-cache conj path)]
            (on-progress (count res))))

        procs
        (->> (seq exports)
             (map #(rd/render % on-object)))]

    (->> (p/all procs)
         (p/fmap (fn [] @result-cache))
         (p/mcat (partial join-pdf file-id))
         (p/mcat (partial move-file resource))
         (p/fmap (constantly resource))
         (p/mcat (partial rsc/upload-resource auth-token))
         (p/mcat (fn [resource]
                   (->> (sh/stat (:path resource))
                        (p/fmap #(merge resource %)))))
         (p/merr on-error)
         (p/fnly (fn [resource cause]
                   (when-not cause
                     (on-complete resource)))))

    (assoc exchange :response/body (dissoc resource :path))))

(defn- join-pdf
  [file-id paths]
  (p/let [prefix (str/concat "penpot.pdfunite." file-id ".")
          path   (sh/tempfile :prefix prefix :suffix ".pdf")]
    (sh/run-cmd! (str "pdfunite " (str/join " " paths) " " path))
    path))

(defn- move-file
  [{:keys [path] :as resource} output-path]
  (p/do
    (sh/move! output-path path)
    resource))
