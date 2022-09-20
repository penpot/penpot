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
  [exchange {:keys [exports wait name profile-id] :as params}]
  (let [total       (count exports)
        topic       (str profile-id)
        resource    (rsc/create :pdf (or name (-> exports first :name)))

        on-progress (fn [{:keys [done]}]
                      (when-not wait
                        (let [data {:type :export-update
                                    :resource-id (:id resource)
                                    :name (:name resource)
                                    :filename (:filename resource)
                                    :status "running"
                                    :total total
                                    :done done}]
                          (redis/pub! topic data))))

        on-complete (fn []
                      (when-not wait
                        (let [data {:type :export-update
                                    :resource-id (:id resource)
                                    :name (:name resource)
                                    :filename (:filename resource)
                                    :status "ended"}]
                          (redis/pub! topic data))))

        on-error    (fn [cause]
                      (l/error :hint "unexpected error on frames exportation" :cause cause)
                      (if wait
                        (p/rejected cause)
                        (let [data {:type :export-update
                                    :resource-id (:id resource)
                                    :name (:name resource)
                                    :filename (:filename resource)
                                    :status "error"
                                    :cause (ex-message cause)}]
                          (redis/pub! topic data))))

        proc        (create-pdf :resource resource
                                :exports exports
                                :on-progress on-progress
                                :on-complete on-complete
                                :on-error on-error)]
    (if wait
      (p/then proc #(assoc exchange :response/body (dissoc % :path)))
      (assoc exchange :response/body (dissoc resource :path)))))

(defn create-pdf
  [& {:keys [resource exports on-progress on-complete on-error]
      :or {on-progress (constantly nil)
           on-complete (constantly nil)
           on-error    p/rejected}}]

  (let [file-id   (-> exports first :file-id)
        result    (atom [])

        on-object
        (fn [{:keys [path] :as object}]
          (let [res (swap! result conj path)]
            (on-progress {:done (count res)})))]

    (-> (p/loop [exports (seq exports)]
          (when-let [export (first exports)]
            (p/do
              (rd/render export on-object)
              (p/recur (rest exports)))))

        (p/then (fn [_] (deref result)))
        (p/then (partial join-pdf file-id))
        (p/then (partial move-file resource))
        (p/then (constantly resource))
        (p/then (fn [resource]
                  (-> (sh/stat (:path resource))
                      (p/then #(merge resource %)))))
        (p/catch on-error)
        (p/finally (fn [_ cause]
                     (when-not cause
                       (on-complete)))))))

(defn- join-pdf
  [file-id paths]
  (p/let [prefix (str/concat "penpot.tmp.pdfunite." file-id ".")
          path   (sh/tempfile :prefix prefix :suffix ".pdf")]
    (sh/run-cmd! (str "pdfunite " (str/join " " paths) " " path))
    path))

(defn- move-file
  [{:keys [path] :as resource} output-path]
  (p/do
    (sh/move! output-path path)
    resource))
