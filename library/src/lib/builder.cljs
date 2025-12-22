;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns lib.builder
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.builder :as fb]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.types.tokens-lib :refer [read-multi-set-dtcg]]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]))

(def BuilderError
  (obj/class
   :name "BuilderError"
   :extends js/Error
   :constructor
   (fn [this type code hint cause]
     (.call js/Error this hint)
     (set! (.-name this) (str "Exception: " hint))
     (set! (.-type this) type)
     (set! (.-code this) code)
     (set! (.-hint this) hint)

     (when (exists? js/Error.captureStackTrace)
       (.captureStackTrace js/Error this))

     (obj/add-properties!
      this
      {:name "cause"
       :enumerable true
       :this false
       :get (fn [] cause)}
      {:name "explain"
       :enumerable true
       :this false
       :get (fn []
              (let [data (ex-data cause)]
                (when-let [explain (::sm/explain data)]
                  (json/->js (sm/simplify explain)))))}))))

(defn- handle-exception
  [cause]
  (let [data (ex-data cause)]
    (throw (new BuilderError
                (d/name (get data :type :unknown))
                (d/name (get data :code :unknown))
                (or (get data :hint) (ex-message cause))
                cause))))

(defn- decode-params
  [params]
  (if (obj/plain-object? params)
    (json/->clj params)
    params))

(defn- get-current-page-id
  [state]
  (dm/str (get state ::fb/current-page-id)))

(defn- get-last-id
  [state]
  (dm/str (get state ::fb/last-id)))

(defn- create-builder-api
  [state]
  (obj/reify {:name "BuildContext"}
    :currentFileId
    {:get #(dm/str (get @state ::fb/current-file-id))}

    :currentFrameId
    {:get #(dm/str (get @state ::fb/current-frame-id))}

    :currentPageId
    {:get #(get-current-page-id @state)}

    :lastId
    {:get #(get-last-id @state)}

    :addFile
    (fn [params]
      (try
        (let [params (-> params decode-params fb/decode-file)]
          (-> (swap! state fb/add-file params)
              (get ::fb/current-file-id)
              (dm/str)))
        (catch :default cause
          (handle-exception cause))))

    :closeFile
    (fn []
      (swap! state fb/close-file)
      nil)

    :addPage
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (fb/decode-page))]

          (-> (swap! state fb/add-page params)
              (get-current-page-id)))

        (catch :default cause
          (handle-exception cause))))

    :closePage
    (fn []
      (swap! state fb/close-page)
      nil)

    :addBoard
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :frame)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-board params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :closeBoard
    (fn []
      (swap! state fb/close-board)
      nil)

    :addGroup
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :group)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-group params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :closeGroup
    (fn []
      (swap! state fb/close-group)
      nil)

    :addBool
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (fb/decode-add-bool))]
          (-> (swap! state fb/add-bool params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addRect
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :rect)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-shape params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addCircle
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :circle)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-shape params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addPath
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :path)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-shape params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addText
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (assoc :type :text)
                         (fb/decode-shape))]
          (-> (swap! state fb/add-shape params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addLibraryColor
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (fb/decode-library-color)
                         (d/without-nils))]
          (-> (swap! state fb/add-library-color params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addLibraryTypography
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (fb/decode-library-typography)
                         (d/without-nils))]
          (-> (swap! state fb/add-library-typography params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addComponent
    (fn [params]
      (try
        (let [params (-> (decode-params params)
                         (fb/decode-add-component))]
          (-> (swap! state fb/add-component params)
              (get-last-id)))
        (catch :default cause
          (handle-exception cause))))

    :addFileMedia
    (fn [params blob]

      (when-not (instance? js/Blob blob)
        (throw (BuilderError. "validation"
                              "invalid-media"
                              "only Blob instance are soported")))
      (try
        (let [blob (fb/map->BlobWrapper
                    {:size (.-size ^js blob)
                     :mtype (.-type ^js blob)
                     :blob blob})
              params
              (-> (decode-params params)
                  (fb/decode-add-file-media))]

          (-> (swap! state fb/add-file-media params blob)
              (get-last-id)))

        (catch :default cause
          (handle-exception cause))))

    :getMediaAsImage
    (fn [id]
      (let [id (uuid/parse id)]
        (when-let [fmedia (get-in @state [::fb/file-media id])]
          (let [image {:id (get fmedia :id)
                       :width (get fmedia :width)
                       :height (get fmedia :height)
                       :name (get fmedia :name)
                       :mtype (get fmedia :mtype)}]
            (json/->js (d/without-nils image))))))

    :addTokensLib
    (fn [data]
      (try
        (let [tlib (read-multi-set-dtcg data)]
          (swap! state fb/add-tokens-lib tlib)
          nil)
        (catch :default cause
          (handle-exception cause))))

    :addRelation
    (fn [file-id library-id]
      (let [file-id    (uuid/parse file-id)
            library-id (uuid/parse library-id)]
        (if (and file-id library-id)
          (do
            (swap! state update :relations assoc file-id library-id)
            true)
          false)))

    :genId
    (fn []
      (dm/str (uuid/next)))

    :getInternalState
    (fn []
      (json/->js @state))))

(def ^:private schema:context-options
  [:map {:title "ContextOptions"}
   [:referer {:optional true} ::sm/text]])

(def ^:private decode-context-options
  (sm/decoder schema:context-options sm/json-transformer))

(defn create-build-context
  "Create an empty builder state context."
  [options]
  (let [options (some-> options decode-params decode-context-options)
        state   (atom {:options options})
        api     (create-builder-api state)]

    (specify! api
      cljs.core/IDeref
      (-deref [_] @state))))
