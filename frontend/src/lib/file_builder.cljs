;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns lib.file-builder
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.builder :as fb]
   [app.common.json :as json]
   [app.common.schema :as sm]
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
      {:name "data"
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
    (json/->js params)
    params))

(defn- create-file-api
  [file]
  (let [state* (volatile! file)
        api    (obj/reify {:name "File"}
                 :id
                 {:get #(dm/str (:id @state*))}

                 :currentFrameId
                 {:get #(dm/str (::fb/current-frame-id @state*))}

                 :currentPageId
                 {:get #(dm/str (::fb/current-page-id @state*))}

                 :lastId
                 {:get #(dm/str (::fb/last-id @state*))}

                 :addPage
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (decode-params)
                                      (fb/decode-page))]
                       (vswap! state* fb/add-page params)
                       (dm/str (::fb/current-page-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :closePage
                 (fn []
                   (vswap! state* fb/close-page))

                 :addArtboard
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :frame)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-artboard params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :closeArtboard
                 (fn []
                   (vswap! state* fb/close-artboard))

                 :addGroup
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :group)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-group params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :closeGroup
                 (fn []
                   (vswap! state* fb/close-group))

                 :addBool
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (fb/decode-add-bool))]
                       (vswap! state* fb/add-bool params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addRect
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :rect)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-shape params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addCircle
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :circle)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-shape params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addPath
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :path)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-shape params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addText
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (assoc :type :text)
                                      (fb/decode-shape))]
                       (vswap! state* fb/add-shape params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addLibraryColor
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (fb/decode-library-color)
                                      (d/without-nils))]
                       (vswap! state* fb/add-library-color params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addLibraryTypography
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (fb/decode-library-typography)
                                      (d/without-nils))]
                       (vswap! state* fb/add-library-typography params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addComponent
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (fb/decode-component)
                                      (d/without-nils))]
                       (vswap! state* fb/add-component params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :addComponentInstance
                 (fn [params]
                   (try
                     (let [params (-> params
                                      (json/->clj)
                                      (fb/decode-add-component-instance)
                                      (d/without-nils))]
                       (vswap! state* fb/add-component-instance params)
                       (dm/str (::fb/last-id @state*)))
                     (catch :default cause
                       (handle-exception cause))))

                 :getShape
                 (fn [shape-id]
                   (let [shape-id (uuid/parse shape-id)]
                     (some-> (fb/lookup-shape @state* shape-id)
                             (json/->js))))

                 :toMap
                 (fn []
                   (-> @state*
                       (d/without-qualified)
                       (json/->js))))]

    (specify! api
      cljs.core/IDeref
      (-deref [_]
        (d/without-qualified @state*)))))

(defn create-file
  [params]
  (try
    (let [params (-> params json/->clj fb/decode-file)
          file   (fb/create-file params)]
      (create-file-api file))
    (catch :default cause
      (handle-exception cause))))
