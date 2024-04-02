;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.api
  "RPC for plugins runtime."
  (:require
   [app.common.data.macros :as dm]
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

(defn ^:export addListener
  [type callback]
  (events/add-listener type callback))

(defn ^:export getFile
  []
  (file/data->file-proxy (:workspace-file @st/state) (:workspace-data @st/state)))

(defn ^:export getPage
  []
  (let [page-id (:current-page-id @st/state)]
    (page/data->page-proxy (dm/get-in @st/state [:workspace-data :pages-index page-id]))))

(defn ^:export getSelected
  []
  (let [selection (get-in @st/state [:workspace-local :selected])]
    (apply array (map str selection))))

(defn ^:export getSelectedShapes
  []
  (let [page-id (:current-page-id @st/state)
        selection (get-in @st/state [:workspace-local :selected])
        objects (dm/get-in @st/state [:workspace-data :pages-index page-id :objects])
        shapes (select-keys objects selection)]
    (apply array (sequence xf-map-shape-proxy shapes))))

(defn ^:export getTheme
  []
  (let [theme (get-in @st/state [:profile :theme])]
    (if (or (not theme) (= theme "default"))
      "dark"
      (get-in @st/state [:profile :theme]))))
