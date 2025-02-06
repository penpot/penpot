;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.media
  (:require
   ["@penpot/svgo$default" :as svgo]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.builder :as fb]
   [app.common.files.changes-builder :as pcb]
   [app.common.logging :as log]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.svg.shapes-builder :as csvg.shapes-builder]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.media :as dmm]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.svg-upload :as svg]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [promesa.core :as p]
   [tubax.core :as tubax]))

(defn- optimize
  [input]
  (svgo/optimize input svgo/defaultOptions))

(defn svg->clj
  [[name text]]
  (try
    (let [text (if (contains? cf/flags :frontend-svgo)
                 (optimize text)
                 text)
          data (-> (tubax/xml->clj text)
                   (assoc :name name))]
      (rx/of data))
    (catch :default cause
      (js/console.error cause)
      (rx/throw (ex/error :type :svg-parser
                          :hint (ex-message cause))))))

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
                   :fills [{:fill-opacity 1
                            :fill-image {:name name
                                         :width width
                                         :height height
                                         :mtype mtype
                                         :id id
                                         :keep-aspect-ratio true}}]}]
        (rx/of (dwsh/create-and-add-shape :rect x y shape))))))

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


(defn upload-media-url
  [name file-id url]
  (rp/cmd!
   :create-file-media-object-from-url
   {:name name
    :file-id file-id
    :url url
    :is-local true}))

(defn process-uris
  [{:keys [file-id local? name uris mtype on-image on-svg]}]
  (letfn [(svg-url? [url]
            (or (and mtype (= mtype "image/svg+xml"))
                (str/ends-with? url ".svg")))

          (upload [uri]
            (->> (http/send! {:method :get :uri uri :mode :no-cors :response-type :blob})
                 (rx/map :body)
                 (rx/map (fn [content]
                           {:file-id file-id
                            :name (or name (svg/extract-name uri))
                            :is-local local?
                            :content content}))
                 (rx/mapcat #(rp/cmd! :upload-file-media-object %))))

          (fetch-svg [name uri]
            (->> (http/send! {:method :get :uri uri :mode :no-cors})
                 (rx/map #(vector
                           (or name (svg/extract-name uri))
                           (:body %)))))]

    (rx/merge
     (->> (rx/from uris)
          (rx/filter (comp not svg-url?))
          (rx/mapcat upload)
          (rx/tap on-image))

     (->> (rx/from uris)
          (rx/filter svg-url?)
          (rx/merge-map (partial fetch-svg name))
          (rx/merge-map svg->clj)
          (rx/tap on-svg)))))

(defn process-blobs
  [{:keys [file-id local? name blobs force-media on-image on-svg]}]
  (letfn [(svg-blob? [blob]
            (and (not force-media)
                 (= (.-type blob) "image/svg+xml")))

          (prepare-blob [blob]
            (let [name (or name (if (dmm/file? blob) (fb/strip-image-extension (.-name blob)) "blob"))]
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
          (rx/tap on-image))

     (->> (rx/from blobs)
          (rx/map dmm/validate-file)
          (rx/filter svg-blob?)
          (rx/merge-map extract-content)
          (rx/merge-map svg->clj)
          (rx/tap on-svg)))))

(defn handle-media-error [error on-error]
  (if (ex/ex-info? error)
    (handle-media-error (ex-data error) on-error)
    (cond
      (= (:code error) :invalid-svg-file)
      (rx/of (ntf/error (tr "errors.media-type-not-allowed")))

      (= (:code error) :media-type-not-allowed)
      (rx/of (ntf/error (tr "errors.media-type-not-allowed")))

      (= (:code error) :unable-to-access-to-url)
      (rx/of (ntf/error (tr "errors.media-type-not-allowed")))

      (= (:code error) :invalid-image)
      (rx/of (ntf/error (tr "errors.media-type-not-allowed")))

      (= (:code error) :media-max-file-size-reached)
      (rx/of (ntf/error (tr "errors.media-too-large")))

      (= (:code error) :media-type-mismatch)
      (rx/of (ntf/error (tr "errors.media-type-mismatch")))

      (= (:code error) :unable-to-optimize)
      (rx/of (ntf/error (:hint error)))

      (fn? on-error)
      (on-error error)

      :else
      (do
        (.error js/console "ERROR" error)
        (rx/of (ntf/error (tr "errors.cannot-upload")))))))


(def ^:private
  schema:process-media-objects
  [:map {:title "process-media-objects"}
   [:file-id ::sm/uuid]
   [:local? :boolean]
   [:name {:optional true} :string]
   [:data {:optional true} :any] ; FIXME
   [:uris {:optional true} [:sequential :string]]
   [:mtype {:optional true} :string]])

(defn- process-media-objects
  [{:keys [uris on-error] :as params}]
  (dm/assert!
   (and (sm/check schema:process-media-objects params)
        (or (contains? params :blobs)
            (contains? params :uris))))

  (ptk/reify ::process-media-objects
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (ntf/show {:content (tr "media.loading")
                         :type :toast
                         :level :info
                         :timeout nil
                         :tag :media-loading}))
       (->> (if (seq uris)
                ;; Media objects is a list of URL's pointing to the path
              (process-uris params)
                ;; Media objects are blob of data to be upload
              (process-blobs params))

              ;; Every stream has its own sideeffect. We need to ignore the result
            (rx/ignore)
            (rx/catch #(handle-media-error % on-error))
            (rx/finalize #(st/emit! (ntf/hide :tag :media-loading))))))))

;; Deprecated in components-v2
(defn upload-media-asset
  [params]
  (let [params (assoc params
                      :force-media true
                      :local? false
                      :on-image #(st/emit! (dwl/add-media %))
                      :on-svg #(st/emit! (dwl/add-media %)))]
    (process-media-objects params)))

(defn upload-media-workspace
  [{:keys [position file-id] :as params}]
  (let [params (assoc params
                      :local? true
                      :on-image #(st/emit! (image-uploaded % position))
                      :on-svg   #(st/emit! (svg-uploaded % file-id position)))]
    (process-media-objects params)))

(defn upload-fill-image
  [file on-success]
  (dm/assert!
   "expected a valid blob for `file` param"
   (dmm/blob? file))
  (ptk/reify ::upload-fill-image
    ptk/WatchEvent
    (watch [_ state _]
      (let [on-upload-success
            (fn [image]
              (on-success image)
              (dmm/notify-finished-loading))

            file-id (:current-file-id state)

            prepare
            (fn [content]
              {:file-id file-id
               :name (if (dmm/file? content) (.-name content) (tr "media.image"))
               :is-local false
               :content content})]

        (dmm/notify-start-loading)
        (->> (rx/of file)
             (rx/map dmm/validate-file)
             (rx/map prepare)
             (rx/mapcat #(rp/cmd! :upload-file-media-object %))
             (rx/tap on-upload-success)
             (rx/catch handle-media-error))))))

;; --- Upload File Media objects

(defn load-and-parse-svg
  "Load the contents of a media-obj of type svg, and parse it
  into a clojure structure."
  [media-obj]
  (let [path (cf/resolve-file-media media-obj)]
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
          (let [[root-svg-shape children]
                (csvg.shapes-builder/create-svg-shapes svg-data pos objects uuid/zero nil #{} false)

                frame-shape
                (cts/setup-shape
                 {:type :frame
                  :x (:x pos)
                  :y (:y pos)
                  :width (-> root-svg-shape :selrect :width)
                  :height (-> root-svg-shape :selrect :height)
                  :name (:name root-svg-shape)
                  :frame-id uuid/zero
                  :parent-id uuid/zero
                  :fills []})

                root-svg-shape
                (-> root-svg-shape
                    (assoc :frame-id (:id frame-shape) :parent-id (:id frame-shape)))

                shapes
                (->> children
                     (filter #(= (:parent-id %) (:id root-svg-shape)))
                     (mapv :id))

                root-svg-shape
                (assoc root-svg-shape :shapes shapes)

                children (->> children (mapv #(assoc % :frame-id (:id frame-shape))))
                children (d/concat-vec [root-svg-shape] children)]

            [frame-shape children]))]

    (->> (upload-images svg-data)
         (rx/map process-svg))))

(defn create-shapes-img
  "Convert a media object that contains a bitmap image into shapes,
  one shape of type :rect containing an image fill and one group that contains it."
  [pos {:keys [name width height id mtype] :as media-obj}]
  (let [frame-shape (cts/setup-shape
                     {:type :frame
                      :x (:x pos)
                      :y (:y pos)
                      :width width
                      :height height
                      :name name
                      :frame-id uuid/zero
                      :parent-id uuid/zero})

        img-shape   (cts/setup-shape
                     {:type :rect
                      :x (:x pos)
                      :y (:y pos)
                      :width width
                      :height height
                      :fills [{:fill-opacity 1
                               :fill-image {:name name
                                            :id id
                                            :width width
                                            :height height
                                            :mtype mtype
                                            :keep-aspect-ratio true}}]
                      :name name
                      :frame-id (:id frame-shape)
                      :parent-id (:id frame-shape)})]
    (rx/of [frame-shape [img-shape]])))

(defn- add-shapes-and-component
  [it file-data page name [shape children]]
  (let [[component-shape component-shapes updated-shapes]
        (ctn/convert-shape-in-component shape children (:id file-data))

        changes (-> (pcb/empty-changes it)
                    (pcb/with-page page)
                    (pcb/with-objects (:objects page))
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
      (let [file-id (:current-file-id state)
            page-id (:current-page-id state)

            fdata   (dsh/lookup-file-data state file-id)
            page    (dsh/get-page fdata page-id)
            pos     (dsh/get-viewport-center state)]

        (->> (create-shapes-img pos media-obj)
             (rx/map (partial add-shapes-and-component it fdata page (:name media-obj))))))))

(defn- process-svg-component
  [svg-data]
  (ptk/reify ::process-svg-component
    ptk/WatchEvent
    (watch [it state _]

      (let [file-id (:current-file-id state)
            page-id (:current-page-id state)

            fdata   (dsh/lookup-file-data state file-id)
            page    (dsh/get-page fdata page-id)
            pos     (dsh/get-viewport-center state)]

        (->> (create-shapes-svg file-id (:objects page) pos svg-data)
             (rx/map (partial add-shapes-and-component it fdata page (:name svg-data))))))))

(defn upload-media-components
  [params]
  (let [params (assoc params
                      :local? false
                      :on-image #(st/emit! (process-img-component %))
                      :on-svg #(st/emit! (process-svg-component %)))]
    (process-media-objects params)))

(def ^:private
  schema:clone-media-object
  [:map {:title "clone-media-object"}
   [:file-id ::sm/uuid]
   [:object-id ::sm/uuid]])

(defn clone-media-object
  [{:keys [file-id object-id] :as params}]
  (dm/assert!
   (sm/check schema:clone-media-object params))

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
         (rx/of (ntf/show {:content (tr "media.loading")
                           :type :toast
                           :level :info
                           :timeout nil
                           :tag :media-loading}))
         (->> (rp/cmd! :clone-file-media-object params)
              (rx/tap on-success)
              (rx/catch on-error)
              (rx/finalize #(st/emit! (ntf/hide :tag :media-loading)))))))))

(defn create-svg-shape
  [id name svg-string position]
  (ptk/reify ::create-svg-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (svg->clj [name svg-string])
           (rx/take 1)
           (rx/map #(svg/add-svg-shapes id % position {:ignore-selection? true
                                                       :change-selection? false}))))))
(defn create-svg-shape-with-images
  [file-id id name svg-string position on-success on-error]
  (ptk/reify ::create-svg-shape-with-images
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (svg->clj [name svg-string])
           (rx/take 1)
           (rx/mapcat
            (fn [svg-data]
              (->> (svg/upload-images svg-data file-id)
                   (rx/map #(assoc svg-data :image-data %)))))
           (rx/map
            (fn [svg-data]
              (svg/add-svg-shapes
               id
               svg-data
               position
               {:ignore-selection? true
                :change-selection? false})))
           (rx/tap on-success)
           (rx/catch on-error)))))
