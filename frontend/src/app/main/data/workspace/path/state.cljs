;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.state
  (:require
   [app.common.data :as d]
   [app.common.path.shapes-to-path :as upsp]))

(defn get-path-id
  "Retrieves the currently editing path id"
  [state]
  (or (get-in state [:workspace-local :edition])
      (get-in state [:workspace-drawing :object :id])))

(defn get-path-location
  [state & ks]
  (let [edit-id  (get-in state [:workspace-local :edition])
        page-id  (:current-page-id state)]
    (d/concat
     (if edit-id
       [:workspace-data :pages-index page-id :objects edit-id]
       [:workspace-drawing :object])
     ks)))

(defn get-path
  "Retrieves the location of the path object and additionally can pass
  the arguments. This location can be used in get-in, assoc-in... functions"
  [state & ks]
  (let [path-loc (get-path-location state)
        shape (-> (get-in state path-loc)
                  ;; Empty map because we know the current shape will not have children
                  (upsp/convert-to-path {}))]

    (if (empty? ks)
      shape
      (get-in shape ks))))

(defn set-content
  [state content]
  (let [path-loc (get-path-location state :content)]
    (-> state
        (assoc-in path-loc content))))
