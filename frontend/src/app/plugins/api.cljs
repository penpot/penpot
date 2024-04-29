;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.api
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.geom.point :as gpt]
   [app.common.record :as cr]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as ch]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.media :as dwm]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.file :as file]
   [app.plugins.page :as page]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as utils]
   [app.plugins.viewport :as viewport]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

;;
;; PLUGINS PUBLIC API - The plugins will able to access this functions
;;
(def ^:private
  xf-map-shape-proxy
  (comp
   (map val)
   (map shape/data->shape-proxy)))

(defn create-shape
  [type]
  (let [page-id (:current-page-id @st/state)
        page (dm/get-in @st/state [:workspace-data :pages-index page-id])
        shape (cts/setup-shape {:type type
                                :x 0 :y 0 :width 100 :height 100})
        changes
        (-> (cb/empty-changes)
            (cb/with-page page)
            (cb/with-objects (:objects page))
            (cb/add-object shape))]
    (st/emit! (ch/commit-changes changes))
    (shape/data->shape-proxy shape)))

(deftype PenpotContext []
  Object
  (addListener
    [_ type callback]
    (events/add-listener type callback))

  (getViewport
    [_]
    (viewport/create-proxy))

  (getFile
    [_]
    (file/data->file-proxy (:workspace-file @st/state) (:workspace-data @st/state)))

  (getPage
    [_]
    (let [page-id (:current-page-id @st/state)
          page (dm/get-in @st/state [:workspace-data :pages-index page-id])]
      (page/data->page-proxy page)))

  (getSelected
    [_]
    (let [selection (get-in @st/state [:workspace-local :selected])]
      (apply array (map str selection))))

  (getSelectedShapes
    [_]
    (let [page-id (:current-page-id @st/state)
          selection (get-in @st/state [:workspace-local :selected])
          objects (dm/get-in @st/state [:workspace-data :pages-index page-id :objects])
          shapes (select-keys objects selection)]
      (apply array (sequence xf-map-shape-proxy shapes))))

  (getRoot
    [_]
    (let [page-id (:current-page-id @st/state)
          root (dm/get-in @st/state [:workspace-data :pages-index page-id :objects uuid/zero])]
      (shape/data->shape-proxy root)))

  (getTheme
    [_]
    (let [theme (get-in @st/state [:profile :theme])]
      (if (or (not theme) (= theme "default"))
        "dark"
        (get-in @st/state [:profile :theme]))))

  (uploadMediaUrl
    [_ name url]
    (let [file-id (get-in @st/state [:workspace-file :id])]
      (p/create
       (fn [resolve reject]
         (->> (dwm/upload-media-url name file-id url)
              (rx/map utils/to-js)
              (rx/take 1)
              (rx/subs! resolve reject))))))

  (group
    [_ shapes]
    (let [page-id (:current-page-id @st/state)
          id (uuid/next)
          ids (into #{} (map #(get (obj/get % "_data") :id)) shapes)]
      (st/emit! (dwg/group-shapes id ids))
      (shape/data->shape-proxy
       (dm/get-in @st/state [:workspace-data :pages-index page-id :objects id]))))

  (ungroup
    [_ group & rest]
    (let [shapes (concat [group] rest)
          ids (into #{} (map #(get (obj/get % "_data") :id)) shapes)]
      (st/emit! (dwg/ungroup-shapes ids))))

  (createFrame
    [_]
    (create-shape :frame))

  (createRectangle
    [_]
    (create-shape :rect))

  (createShapeFromSvg
    [_ svg-string]
    (let [id (uuid/next)
          page-id (:current-page-id @st/state)]
      (st/emit! (dwm/create-svg-shape id "svg" svg-string (gpt/point 0 0)))
      (shape/data->shape-proxy
       (dm/get-in @st/state [:workspace-data :pages-index page-id :objects id])))))

(defn create-context
  []
  (cr/add-properties!
   (PenpotContext.)
   {:name "root" :get #(.getRoot ^js %)}
   {:name "currentPage" :get #(.getPage ^js %)}
   {:name "selection" :get #(.getSelectedShapes ^js %)}
   {:name "viewport" :get #(.getViewport ^js %)}))
