;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.state
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.path.shape-to-path :as stp]))

(defn get-path-id
  "Retrieves the currently editing path id"
  [state]
  (or (dm/get-in state [:workspace-local :edition])
      (dm/get-in state [:workspace-drawing :object :id])))

(defn get-path-location
  [state & ks]
  (if-let [edit-id (dm/get-in state [:workspace-local :edition])]
    (let [page-id  (:current-page-id state)
          file-id  (:current-file-id state)]
      (into [:files file-id :data :pages-index page-id :objects edit-id] ks))
    (into [:workspace-drawing :object] ks)))

(defn get-path
  "Retrieves the location of the path object and additionally can pass
  the arguments. This location can be used in get-in, assoc-in... functions"
  [state & ks]
  (let [path-loc (get-path-location state)
        shape    (-> (get-in state path-loc)
                     (stp/convert-to-path {}))]
    (if (empty? ks)
      shape
      (get-in shape ks))))

(defn set-content
  [state content]
  (let [path-loc (get-path-location state :content)]
    (assoc-in state path-loc content)))
