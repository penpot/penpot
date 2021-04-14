;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.state
  (:require
   [app.common.data :as d]))

(defn get-path-id
  "Retrieves the currently editing path id"
  [state]
  (or (get-in state [:workspace-local :edition])
      (get-in state [:workspace-drawing :object :id])))

(defn get-path
  "Retrieves the location of the path object and additionaly can pass
  the arguments. This location can be used in get-in, assoc-in... functions"
  [state & path]
  (let [edit-id (get-in state [:workspace-local :edition])
        page-id (:current-page-id state)]
    (d/concat
     (if edit-id
       [:workspace-data :pages-index page-id :objects edit-id]
       [:workspace-drawing :object])
     path)))


