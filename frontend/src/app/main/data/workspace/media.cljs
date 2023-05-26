;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.media
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.pages.changes-builder :as pcb]
   [app.common.schema :as sm]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.media :as dmm]
   [app.main.data.messages :as msg]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.svg-upload :as svg]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [promesa.core :as p]
   [tubax.core :as tubax]))

(defn svg->clj
  [[name text]]
  (try
    (->> (rx/of (-> (tubax/xml->clj text)
                    (assoc :name name))))

    (catch :default _err
      (rx/throw {:type :svg-parser}))))

;; TODO: rename to bitmap-image-uploaded
(defn image-uploaded
  [image {:keys [x y]}]
  (ptk/reify ::image-uploaded
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [name width height id mtype]} image
            shape {:name name
                   :width width
                   :height height
                   :x (mth/round (- x (/ width 2)))
                   :y (mth/round (- y (/ height 2)))
                   :metadata {:width width
                              :height height
                              :mtype mtype
                              :id id}}]
        (rx/of (dwsh/create-and-add-shape :image x y shape))))))

(defn svg-uploaded
  [svg-data file-id position]
  (ptk/reify ::svg-uploaded
    ptk/WatchEvent
    (watch [_ _ _]
      ;; Once the SVG is uploaded, we need to extract all the bitmap
      ;; images and upload them separately, then proceed to create
      ;; all shapes.
      (->> (svg/upload-images svg-data file-id)
           (rx/map #(svg/add-svg-shapes (assoc svg-data :image-data %) position))))))

(defn- process-uris
  [{:keys [file-id local? name uris mtype on-image on-svg]}]
  (letfn [(svg-url? [url]
            (or (and mtype (= mtype "image/svg+xml"))
                (str/ends-with? url ".svg")))

          (prepare [uri]
            {:file-id file-id
             :is-local local?
             :name (or name (svg/extract-name uri))
             :url uri})

          (fetch-svg [name uri]
            (->> (http/send! {:method :get :uri uri :mode :no-cors})
                 (rx/map #(vector
                           (or name (svg/extract-name uri))
                           (:body %)))))]

    (rx/merge
     (->> (rx/from uris)
          (rx/filter (comp not svg-url?))
          (rx/map prepare)
          (rx/mapcat #(rp/cmd! :create-file-media-object-from-url %))
          (rx/do on-image))

     (->> (rx/from uris)
          (rx/filter svg-url?)
          (rx/merge-map (partial fetch-svg name))
          (rx/merge-map svg->clj)
          (rx/do on-svg)))))

(defn- process-blobs
  [{:keys [file-id local? name blobs force-media on-image on-svg]}]
  (letfn [(svg-blob? [blob]
            (and (not force-media)
                 (= (.-type blob) "image/svg+xml")))

          (prepare-blob [blob]
            (let [name (or name (if (dmm/file? blob) (.-name blob) "blob"))]
              {:file-id file-id
               :name name
               :is-local local?
               :content blob}))

          (extract-content [blob]
            (let [name (or name (.-name blob))]
              (-> (.text ^js blob)
                  (p/then #(vector name %)))))]

    (rx/merge
     (->> (rx/from blobs)
          (rx/map dmm/validate-file)
          (rx/filter (comp not svg-blob?))
          (rx/map prepare-blob)
          (rx/mapcat #(rp/cmd! :upload-file-media-object %))
          (rx/do on-image))

     (->> (rx/from blobs)
          (rx/map dmm/validate-file)
          (rx/filter svg-blob?)
          (rx/merge-map extract-content)
          (rx/merge-map svg->clj)
          (rx/do on-svg)))))

(def schema:process-media-objects
  [:map
   [:file-id ::sm/uuid]
   [:local? :boolean]
   [:name {:optional true} :string]
   [:data {:optional true} :any] ; FIXME
   [:uris {:optional true} [:vector :string]]
   [:mtype {:optional true} :string]])

(defn- process-media-objects
  [{:keys [uris on-error] :as params}]
  (dm/assert!
   (and (sm/valid? schema:process-media-objects params)
        (or (contains? params :blobs)
            (contains? params :uris))))

  (letfn [(handle-error [error]
            (if (ex/ex-info? error)
              (handle-error (ex-data error))
              (cond
                (= (:code error) :invalid-svg-file)
                (rx/of (msg/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-type-not-allowed)
                (rx/of (msg/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :unable-to-access-to-url)
                (rx/of (msg/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :invalid-image)
                (rx/of (msg/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-max-file-size-reached)
                (rx/of (msg/error (tr "errors.media-too-large")))

                (= (:code error) :media-type-mismatch)
                (rx/of (msg/error (tr "errors.media-type-mismatch")))

                (= (:code error) :unable-to-optimize)
                (rx/of (msg/error (:hint error)))

                (fn? on-error)
                (on-error error)

                :else
                (rx/throw error))))]

    (ptk/reify ::process-media-objects
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/concat
         (rx/of (msg/show {:content (tr "media.loading")
                           :type :info
                           :timeout nil
                           :tag :media-loading}))
         (->> (if (seq uris)
                ;; Media objects is a list of URL's pointing to the path
                (process-uris params)
                ;; Media objects are blob of data to be upload
                (process-blobs params))

              ;; Every stream has its own sideeffect. We need to ignore the result
              (rx/ignore)
              (rx/catch handle-error)
              (rx/finalize #(st/emit! (msg/hide-tag :media-loading)))))))))

;; Deprecated in components-v2
(defn upload-media-asset
  [params]
  (let [params (assoc params
                      :force-media true
                      :local? false
                      :on-image #(st/emit! (dwl/add-media %)))]
    (process-media-objects params)))

;; TODO: it is really need handle SVG here, looks like it already
;; handled separately
(defn upload-media-workspace
  [{:keys [position file-id] :as params}]
  (let [params (assoc params
                      :local? true
                      :on-image #(st/emit! (image-uploaded % position))
                      :on-svg   #(st/emit! (svg-uploaded % file-id position)))]
    (process-media-objects params)))


;; --- Upload File Media objects

(defn load-and-parse-svg
  "Load the contents of a media-obj of type svg, and parse it
  into a clojure structure."
  [media-obj]
  (let [path (cfg/resolve-file-media media-obj)]
    (->> (http/send! {:method :get :uri path :mode :no-cors})
         (rx/map :body)
         (rx/map #(vector (:name media-obj) %))
         (rx/merge-map svg->clj)
         (rx/catch  ; When error downloading media-obj, skip it and continue with next one
             #(log/error :msg (str "Error downloading " (:name media-obj) " from " path)
                         :hint (ex-message %)
                         :error %)))))

(defn create-shapes-svg
  "Convert svg elements into penpot shapes."
  [file-id objects pos svg-data]
  (let [upload-images
        (fn [svg-data]
          (->> (svg/upload-images svg-data file-id)
               (rx/map #(assoc svg-data :image-data %))))

        process-svg
        (fn [svg-data]
          (let [[shape children]
                (svg/create-svg-shapes svg-data pos objects uuid/zero nil #{} false)]
            [shape children]))]

    (->> (upload-images svg-data)
         (rx/map process-svg))))

(defn create-shapes-img
  "Convert a media object that contains a bitmap image into shapes,
  one shape of type :image and one group that contains it."
  [pos {:keys [name width height id mtype] :as media-obj}]
  (let [group-shape (cts/setup-shape
                     {:type :group
                      :x (:x pos)
                      :y (:y pos)
                      :width width
                      :height height
                      :name name
                      :frame-id uuid/zero
                      :parent-id uuid/zero})

        img-shape   (cts/setup-shape
                     {:type :image
                      :x (:x pos)
                      :y (:y pos)
                      :width width
                      :height height
                      :metadata {:id id
                                 :width width
                                 :height height
                                 :mtype mtype}
                      :name name
                      :frame-id uuid/zero
                      :parent-id (:id group-shape)})]
    (rx/of [group-shape [img-shape]])))

(defn- add-shapes-and-component
  [it file-data page name [shape children]]
  (let [page'  (reduce #(ctst/add-shape (:id %2) %2 %1 uuid/zero (:parent-id %2) nil false)
                       page
                       (cons shape children))

        shape' (ctn/get-shape page' (:id shape))

        [component-shape component-shapes updated-shapes]
        (ctn/make-component-shape shape' (:objects page') (:id file-data) true)

        changes (-> (pcb/empty-changes it)
                    (pcb/with-page page')
                    (pcb/with-objects (:objects page'))
                    (pcb/with-library-data file-data)
                    (pcb/add-objects (cons shape children))
                    (pcb/add-component (:id component-shape)
                                       ""
                                       name
                                       component-shapes
                                       updated-shapes
                                       (:id shape)
                                       (:id page)))]

    (dch/commit-changes changes)))

(defn- process-img-component
  [media-obj]
  (ptk/reify ::process-img-component
    ptk/WatchEvent
    (watch [it state _]
      (let [file-data (wsh/get-local-file state)
            page      (wsh/lookup-page state)
            pos       (wsh/viewport-center state)]
        (->> (create-shapes-img pos media-obj)
             (rx/map (partial add-shapes-and-component it file-data page (:name media-obj))))))))

(defn- process-svg-component
  [svg-data]
  (ptk/reify ::process-svg-component
    ptk/WatchEvent
    (watch [it state _]
      (let [file-data (wsh/get-local-file state)
            page      (wsh/lookup-page state)
            pos       (wsh/viewport-center state)]
        (->> (create-shapes-svg (:id file-data) (:objects page) pos svg-data)
             (rx/map (partial add-shapes-and-component it file-data page (:name svg-data))))))))

(defn upload-media-components
  [params]
  (let [params (assoc params
                      :local? false
                      :on-image #(st/emit! (process-img-component %))
                      :on-svg #(st/emit! (process-svg-component %)))]
    (process-media-objects params)))

(def schema:clone-media-object
  [:map
   [:file-id ::sm/uuid]
   [:object-id ::sm/uuid]])

(defn clone-media-object
  [{:keys [file-id object-id] :as params}]
  (dm/assert! (sm/valid? schema:clone-media-object params))
  (ptk/reify ::clone-media-objects
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [on-success on-error]
             :or {on-success identity
                  on-error identity}} (meta params)
            params {:is-local true
                    :file-id file-id
                    :id object-id}]

        (rx/concat
         (rx/of (msg/show {:content (tr "media.loading")
                           :type :info
                           :timeout nil
                           :tag :media-loading}))
         (->> (rp/cmd! :clone-file-media-object params)
              (rx/do on-success)
              (rx/catch on-error)
              (rx/finalize #(st/emit! (msg/hide-tag :media-loading)))))))))

