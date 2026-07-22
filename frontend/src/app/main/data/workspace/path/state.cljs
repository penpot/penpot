;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.path.state
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.path.shape-to-path :as stp]))

(defn get-path-id
  "Returns the active path id.

  The drawing copy is preferred because it also exists during initial path
  creation, before workspace edition has an id. The edition id is the fallback
  while an existing path's drawing copy is being established."
  [state]
  (or (dm/get-in state [:workspace-drawing :object :id])
      (dm/get-in state [:workspace-local :edition])))

(defn get-selection
  "Returns the grouped selection for the active path or the supplied path id."
  ([state]
   (get-selection state (get-path-id state)))
  ([state id]
   (dm/get-in state [:workspace-local :edit-path id :selection])))

(defn current-edit-state
  ([state]
   (current-edit-state (dm/get-in state [:workspace-local :edit-path])
                       (dm/get-in state [:workspace-local :edition])))
  ([edit-path id]
   (get edit-path id)))

(defn editing?
  ([state]
   (some? (current-edit-state state)))
  ([edit-path id]
   (some? (current-edit-state edit-path id))))

(defn drawing?
  ([state]
   (let [edition  (dm/get-in state [:workspace-local :edition])
         edit-path (dm/get-in state [:workspace-local :edit-path])]
     (and (nil? edition)
          (some? (get edit-path (get-path-id state))))))
  ([edit-state edition drawing-tool drawing-object]
   (or (= :draw (:edit-mode edit-state))
       (and (nil? edition)
            (= :path (:type drawing-object))
            (not= :curve drawing-tool)))))

(defn get-path-location
  [_state & ks]
  (into [:workspace-drawing :object] ks))

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
