;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.state
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cph]
   [app.common.svg.path.shapes-to-path :as upsp]))

(defn path-editing?
  "Returns true if we're editing a path or creating a new one."
  [{local :workspace-local
    drawing :workspace-drawing}]
  (let [selected     (:selected local)
        edition      (:edition local)

        drawing-obj  (:object drawing)
        drawing-tool (:tool drawing)

        edit-path?   (dm/get-in local [:edit-path edition])

        shape        (or drawing-obj (first selected))
        shape-id     (:id shape)

        single?      (= (count selected) 1)
        editing?     (and (some? shape-id)
                          (some? edition)
                          (= shape-id edition))

        ;; we need to check if we're drawing a new object but we're
        ;; not using the pencil tool.
        draw-path?   (and (some? drawing-obj)
                          (cph/path-shape? drawing-obj)
                          (not= :curve drawing-tool))]

    (or (and ^boolean single?
             ^boolean editing?
             (and (not (cph/text-shape? shape))
                  (not (cph/frame-shape? shape))))
        draw-path?
        edit-path?)))

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
                     ;; Empty map because we know the current shape will not have children
                     (upsp/convert-to-path {}))]
    (if (empty? ks)
      shape
      (get-in shape ks))))

(defn set-content
  [state content]
  (let [path-loc (get-path-location state :content)]
    (assoc-in state path-loc content)))
