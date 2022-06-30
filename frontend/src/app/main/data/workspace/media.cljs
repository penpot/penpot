;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.media
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.main.data.media :as dmm]
   [app.main.data.messages :as dm]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.svg-upload :as svg]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.http :as http]
   [app.util.i18n :refer [tr]]
   [app.util.svg :as usvg]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
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

(defn extract-name [url]
  (let [query-idx (str/last-index-of url "?")
        url (if (> query-idx 0) (subs url 0 query-idx) url)
        filename (->> (str/split url "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn data-uri->blob
  [data-uri]
  (let [[mtype b64-data] (str/split data-uri ";base64,")
        mtype   (subs mtype (inc (str/index-of mtype ":")))
        decoded (.atob js/window b64-data)
        size    (.-length ^js decoded)
        content (js/Uint8Array. size)]

    (doseq [i (range 0 size)]
      (aset content i (.charCodeAt decoded i)))

    (wapi/create-blob content mtype)))


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
                   :x (- x (/ width 2))
                   :y (- y (/ height 2))
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
      (->> (rx/from (usvg/collect-images svg-data))
           (rx/map (fn [uri]
                     (merge
                      {:file-id file-id
                       :is-local true}
                      (if (str/starts-with? uri "data:")
                        {:name "image"
                         :content (data-uri->blob uri)}
                        {:name (extract-name uri)
                         :url uri}))))
           (rx/mapcat (fn [uri-data]
                        (->> (rp/mutation! (if (contains? uri-data :content)
                                             :upload-file-media-object
                                             :create-file-media-object-from-url) uri-data)
                             ;; When the image uploaded fail we skip the shape
                             ;; returning `nil` will afterward not create the shape.
                             (rx/catch #(rx/of nil))
                             (rx/map #(vector (:url uri-data) %)))))
           (rx/reduce (fn [acc [url image]] (assoc acc url image)) {})
           (rx/map #(svg/create-svg-shapes (assoc svg-data :image-data %) position))))))

(defn- process-uris
  [{:keys [file-id local? name uris mtype on-image on-svg]}]
  (letfn [(svg-url? [url]
            (or (and mtype (= mtype "image/svg+xml"))
                (str/ends-with? url ".svg")))

          (prepare [uri]
            {:file-id file-id
             :is-local local?
             :name (or name (extract-name uri))
             :url uri})

          (fetch-svg [name uri]
            (->> (http/send! {:method :get :uri uri :mode :no-cors})
                 (rx/map #(vector
                           (or name (extract-name uri))
                           (:body %)))))]

    (rx/merge
     (->> (rx/from uris)
          (rx/filter (comp not svg-url?))
          (rx/map prepare)
          (rx/mapcat #(rp/mutation! :create-file-media-object-from-url %))
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
          (rx/mapcat #(rp/mutation! :upload-file-media-object %))
          (rx/do on-image))

     (->> (rx/from blobs)
          (rx/map dmm/validate-file)
          (rx/filter svg-blob?)
          (rx/merge-map extract-content)
          (rx/merge-map svg->clj)
          (rx/do on-svg)))))

(s/def ::local? ::us/boolean)
(s/def ::blobs ::dmm/blobs)
(s/def ::name ::us/string)
(s/def ::uris (s/coll-of ::us/string))
(s/def ::mtype ::us/string)

(s/def ::process-media-objects
  (s/and
   (s/keys :req-un [::file-id ::local?]
           :opt-un [::name ::data ::uris ::mtype])
   (fn [props]
     (or (contains? props :blobs)
         (contains? props :uris)))))

(defn- process-media-objects
  [{:keys [uris on-error] :as params}]
  (us/assert ::process-media-objects params)
  (letfn [(handle-error [error]
            (if (ex/ex-info? error)
              (handle-error (ex-data error))
              (cond
                (= (:code error) :invalid-svg-file)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-type-not-allowed)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :unable-to-access-to-url)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :invalid-image)
                (rx/of (dm/error (tr "errors.media-type-not-allowed")))

                (= (:code error) :media-too-large)
                (rx/of (dm/error (tr "errors.media-too-large")))

                (= (:code error) :media-type-mismatch)
                (rx/of (dm/error (tr "errors.media-type-mismatch")))

                (= (:code error) :unable-to-optimize)
                (rx/of (dm/error (:hint error)))

                (fn? on-error)
                (on-error error)

                :else
                (rx/throw error))))]

    (ptk/reify ::process-media-objects
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/concat
         (rx/of (dm/show {:content (tr "media.loading")
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
              (rx/finalize #(st/emit! (dm/hide-tag :media-loading)))))))))

(defn upload-media-asset
  [params]
  (let [params (assoc params
                      :force-media true
                      :local? false
                      :on-image #(st/emit! (dwl/add-media %)))]
    (process-media-objects params)))


;; TODO: it is really need handle SVG here, looks like it already
;; handled separatelly
(defn upload-media-workspace
  [{:keys [position file-id] :as params}]
  (let [params (assoc params
                      :local? true
                      :on-image #(st/emit! (image-uploaded % position))
                      :on-svg   #(st/emit! (svg-uploaded % file-id position)))]
    (process-media-objects params)))


;; --- Upload File Media objects

(s/def ::object-id ::us/uuid)

(s/def ::clone-media-objects-params
  (s/keys :req-un [::file-id ::object-id]))

(defn clone-media-object
  [{:keys [file-id object-id] :as params}]
  (us/assert ::clone-media-objects-params params)
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
         (rx/of (dm/show {:content (tr "media.loading")
                          :type :info
                          :timeout nil
                          :tag :media-loading}))
         (->> (rp/mutation! :clone-file-media-object params)
              (rx/do on-success)
              (rx/catch on-error)
              (rx/finalize #(st/emit! (dm/hide-tag :media-loading)))))))))

