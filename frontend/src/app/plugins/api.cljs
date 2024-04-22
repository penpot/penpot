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
   [app.common.record :as cr]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as ch]
   [app.main.store :as st]
   [app.plugins.events :as events]
   [app.plugins.file :as file]
   [app.plugins.page :as page]
   [app.plugins.shape :as shape]))

;;
;; PLUGINS PUBLIC API - The plugins will able to access this functions
;;
(def ^:private
  xf-map-shape-proxy
  (comp
   (map val)
   (map shape/data->shape-proxy)))

(deftype PenpotContext []
  Object
  (addListener
    [_ type callback]
    (events/add-listener type callback))

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

  (createRectangle
    [_]
    (let [page-id (:current-page-id @st/state)
          page (dm/get-in @st/state [:workspace-data :pages-index page-id])
          shape (cts/setup-shape {:type :rect
                                  :x 0 :y 0 :width 100 :height 100})
          changes
          (-> (cb/empty-changes)
              (cb/with-page page)
              (cb/with-objects (:objects page))
              (cb/add-object shape))]
      (st/emit! (ch/commit-changes changes))
      (shape/data->shape-proxy shape))))

(defn create-context
  []
  (cr/add-properties!
   (PenpotContext.)
   {:name "root" :get #(.getRoot ^js %)}
   {:name "currentPage" :get #(.getPage ^js %)}
   {:name "selection" :get #(.getSelectedShapes ^js %)}))
