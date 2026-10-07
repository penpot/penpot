;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.texts-events
  (:require
   [app.common.data :as d]
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
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn editor-text-options
  "Editor data that `dwt/current-text-values` needs for `shape-id`,
  taken from the text editor that is active in `state`."
  [state shape-id]
  (let [wasm? (features/active-feature? state "text-editor-wasm/v1")
        v2?   (features/active-feature? state "text-editor/v2")
        state-map (if wasm?
                    (:workspace-wasm-editor-styles state)
                    (:workspace-editor-state state))]
    {:editor-styles   (when wasm? (get state-map shape-id))
     :editor-state    (when-not v2? (get state-map shape-id))
     :editor-instance (when v2? (:workspace-editor state))}))

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
                         (assoc (editor-text-options state (:id shape))
                                :shape shape
                                :attrs txt/text-node-attrs))

             multiple? (or (> 1 (count shapes))
                           (d/seek (partial = :multiple)
                                   (vals values)))

             too-many-texts? (> (count shapes) 1)

             font-missing? (and (not multiple?)
                                (some? (:font-id values))
                                (not (fonts/installed? (:font-id values))))

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

         (if (or font-missing? too-many-texts?)
           (rx/empty)
           (rx/concat
            (rx/of (dwl/add-typography typ)
                   (ev/event {::ev/name "add-asset-to-library"
                              :asset-type "typography"}))

            (when (not multiple?)
              (rx/of (dwt/update-attrs (:id shape)
                                       {:typography-ref-id typ-id
                                        :typography-ref-file file-id}))))))))))
