;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.libraries-common-helpers
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.uuid :as uuid]))

(defn generate-add-component-changes
  [changes root objects file-id page-id components-v2]
  (let [name (:name root)
        [path name] (cfh/parse-path-name name)

        [root-shape new-shapes updated-shapes]
        (if-not components-v2
          (ctn/make-component-shape root objects file-id components-v2)
          (ctn/convert-shape-in-component root objects file-id))

        changes (-> changes
                    (pcb/add-component (:id root-shape)
                                       path
                                       name
                                       new-shapes
                                       updated-shapes
                                       (:id root)
                                       page-id))]
    [root-shape changes]))

(defn generate-add-component
  "If there is exactly one id, and it's a frame (or a group in v1), and not already a component,
  use it as root. Otherwise, create a frame (v2) or group (v1) that contains all ids. Then, make a
  component with it, and link all shapes to their corresponding one in the component."
  [it shapes objects page-id file-id components-v2 prepare-create-group prepare-create-board]

  (let [changes      (pcb/empty-changes it page-id)
        shapes-count (count shapes)
        first-shape  (first shapes)

        from-singe-frame?
        (and (= 1 shapes-count)
             (cfh/frame-shape? first-shape))

        [root changes old-root-ids]
        (if (and (= shapes-count 1)
                 (or (and (cfh/group-shape? first-shape)
                          (not components-v2))
                     (cfh/frame-shape? first-shape))
                 (not (ctk/instance-head? first-shape)))
          [first-shape
           (-> (pcb/empty-changes it page-id)
               (pcb/with-objects objects))
           (:shapes first-shape)]

          (let [root-name (if (= 1 shapes-count)
                            (:name first-shape)
                            "Component 1")

                shape-ids (into (d/ordered-set) (map :id) shapes)

                [root changes]
                (if-not components-v2
                  (prepare-create-group it            ; These functions needs to be passed as argument
                                        objects       ; to avoid a circular dependence
                                        page-id
                                        shapes
                                        root-name
                                        (not (ctk/instance-head? first-shape)))
                  (prepare-create-board changes
                                        (uuid/next)
                                        (:parent-id first-shape)
                                        objects
                                        shape-ids
                                        nil
                                        root-name
                                        true))]

            [root changes shape-ids]))

        changes
        (cond-> changes
          (not from-singe-frame?)
          (pcb/update-shapes
           (:shapes root)
           (fn [shape]
             (assoc shape :constraints-h :scale :constraints-v :scale))))

        objects' (assoc objects (:id root) root)

        [root-shape changes] (generate-add-component-changes changes root objects' file-id page-id components-v2)

        changes  (pcb/update-shapes changes
                                    old-root-ids
                                    #(dissoc % :component-root)
                                    [:component-root])]

    [root (:id root-shape) changes]))
