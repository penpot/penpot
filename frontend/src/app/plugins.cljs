;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.record :as crc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [goog.functions :as gf]
   [app.util.array :as array]
   [app.util.rxops :as rxops]
   [app.util.timers :as tm]))

;; ---- TYPES

(deftype ShapeProxy [id name type _data])

(defn data->shape-proxy
  [data]
  (->ShapeProxy (str (:id data))
                (:name data)
                (name (:type data))
                data))

(def ^:private
  xf-map-shape-proxy
  (comp
   (map val)
   (map data->shape-proxy)))

(deftype PageProxy [id name _data]
  Object
  (getShapes [_]
    ;; Returns a lazy (iterable) of all available shapes
    (sequence xf-map-shape-proxy (:objects _data))))

(defn- data->page-proxy
  [data]
  (->PageProxy (str (:id data))
               (:name data)
               data))

(def ^:private
  xf-map-page-proxy
  (comp
   (map val)
   (map data->page-proxy)))

(deftype FileProxy [id name revn _data]
  Object
  (getPages [_]
    ;; Returns a lazy (iterable) of all available pages
    (sequence xf-map-page-proxy (:pages-index _data))))

;; ---- PROPERTIES

(crc/define-properties!
  FileProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "FileProxy"))})

(crc/define-properties!
  PageProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "PageProxy"))})

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

;; ---- PUBLIC API

(defn ^:export getCurrentFile
  []
  (let [data (:workspace-data @st/state)]
    (when (some? data)
      (let [file (:workspace-file @st/state)]
        (->FileProxy (str (:id file))
                     (:name file)
                     (:revn file)
                     data)))))

(defn ^:export getCurrentPage
  []
  (when-let [page-id (:current-page-id @st/state)]
    (when-let [data (get-in @st/state [:workspace-data :pages-index page-id])]
      (data->page-proxy data))))

(defn ^:export getCurrentSelection
  []
  (let [selection (get-in @st/state [:workspace-local :selected])]
    (when (some? selection)
      selection)))

(defn ^:export getCurrentTheme
  []
  (get-in @st/state [:profile :theme]))

(defn ^:export getState
  []
  @st/state)

;; (defonce listeners
;;   (atom {}))

(defn ^:export addListener
  [key type f]
  (let [f (gf/debounce f 500)]
    (case type
      "file"
      (add-watch st/state key
                 (fn [_ _ old-val new-val]
                   (let [old-file (:workspace-file old-val)
                         new-file (:workspace-file new-val)
                         old-data (:workspace-data old-val)
                         new-data (:workspace-data new-val)]
                     (when-not (and (identical? old-file new-file)
                                    (identical? old-data new-data))
                       (f (->FileProxy (str (:id new-file))
                                       (:name new-file)
                                       (:revn new-file)
                                       new-data))))))
      "page"
      (add-watch st/state key
                 (fn [_ _ old-val new-val]
                   (let [old-page-id (:current-page-id old-val)
                         new-page-id (:current-page-id new-val)
                         old-page    (dm/get-in old-val [:workspace-data :pages-index old-page-id])
                         new-page    (dm/get-in new-val [:workspace-data :pages-index new-page-id])]
                     (when-not (identical? old-page new-page)
                       (f (data->page-proxy new-page))))))
      "selection"
      (add-watch st/state key
                 (fn [_ _ old-val new-val]
                   (let [old-selection (get-in old-val [:workspace-local :selected])
                         new-selection (get-in new-val [:workspace-local :selected])]
                     (when-not (identical? old-selection new-selection)
                        (f (clj->js new-selection))))))

      "theme"
      (add-watch st/state key
                 (fn [_ _ old-val new-val]
                   (let [old-theme (get-in old-val [:profile :theme])
                         new-theme (get-in new-val [:profile :theme])]
                     (when-not (identical? old-theme new-theme)
                       (f new-theme)))))
      )))

