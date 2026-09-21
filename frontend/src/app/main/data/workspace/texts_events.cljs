;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.texts-events
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.math :as mth]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.texts :as dwt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; This function must be separated from app.main.data.workspace.texts to avoid a circular
;; dependency due main.data.workspace.libraries eventually calling app.main.data.workspace.texts.

(defn add-typography
  "A higher level version of dwl/add-typography, and has mainly two
  responsabilities: add the typography to the library and apply it to
  the currently selected text shapes (being aware of the open text
  editors.
  Optionally accepts a group-path to place the new typography inside
  a specific group."
  ([file-id] (add-typography file-id nil))
  ([file-id group-path]
   (ptk/reify ::add-typography
     ptk/WatchEvent
     (watch [_ state _]
       (let [selected   (dsh/lookup-selected state)
             objects    (dsh/lookup-page-objects state)

             xform      (comp (keep (d/getf objects))
                              (filter cfh/text-shape?))
             shapes     (into [] xform selected)
             shape      (first shapes)

             values     (dwt/current-text-values
                         {:editor-state (dm/get-in state [:workspace-editor-state (:id shape)])
                          :shape shape
                          :attrs txt/text-node-attrs})

             multiple? (or (> 1 (count shapes))
                           (d/seek (partial = :multiple)
                                   (vals values)))

             values    (-> (d/without-nils values)
                           (select-keys
                            (d/concat-vec txt/text-font-attrs
                                          txt/text-spacing-attrs
                                          txt/text-transform-attrs)))
             values    (cond-> values
                         (number? (:line-height values))
                         (update :line-height #(str (mth/precision % 2)))

                         (number? (:letter-spacing values))
                         (update :letter-spacing #(str (mth/precision % 2))))

             typ-id    (uuid/next)
             typ       (-> (if multiple?
                             txt/default-typography
                             (merge txt/default-typography values))
                           (dwt/generate-typography-name)
                           (assoc :id typ-id)
                           (cond-> (string? group-path)
                             (update :name #(str group-path " / " %))))]

         (rx/concat
          (rx/of (dwl/add-typography typ)
                 (ev/event {::ev/name "add-asset-to-library"
                            :asset-type "typography"}))

          (when (not multiple?)
            (rx/of (dwt/update-attrs (:id shape)
                                     {:typography-ref-id typ-id
                                      :typography-ref-file file-id})))))))))
