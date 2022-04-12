;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text.viewport-texts
  (:require
   [app.common.attrs :as attrs]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [app.util.text-svg-position :as utp]
   [rumext.alpha :as mf]))

(defn- update-with-editor-state
  "Updates the shape with the current state in the editor"
  [shape editor-state]
  (let [content (:content shape)
        editor-content
        (when editor-state
          (-> editor-state
              (ted/get-editor-current-content)
              (ted/export-content)))]

    (cond-> shape
      (some? editor-content)
      (assoc :content (attrs/merge content editor-content)))))

(mf/defc text-container
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [shape (obj/get props "shape")

        handle-node-rendered
        (fn [node]
          (when node
            ;; Check if we need to update the size because it's auto-width or auto-height
            (when (contains? #{:auto-height :auto-width} (:grow-type shape))
              (let [{:keys [width height]}
                    (-> (dom/query node ".paragraph-set")
                        (dom/get-client-size))]
                (when (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
                  (st/emit! (dwt/resize-text (:id shape) (mth/ceil width) (mth/ceil height))))))

            ;; Update the position-data of every text fragment
            (let [position-data (utp/calc-position-data node)]
              (st/emit! (dch/update-shapes
                         [(:id shape)]
                         (fn [shape]
                           (-> shape
                               (assoc :position-data position-data)))
                         {:save-undo? false})))))]

    [:& fo/text-shape {:key (str "shape-" (:id shape))
                       :ref handle-node-rendered
                       :shape shape
                       :grow-type (:grow-type shape)}]))

(mf/defc viewport-texts
  [{:keys [objects edition]}]

  (let [editor-state (-> (mf/deref refs/workspace-editor-state)
                         (get edition))

        text-shapes-ids
        (mf/use-memo
         (mf/deps objects)
         #(->> objects (vals) (filter cph/text-shape?) (map :id)))

        text-shapes
        (mf/use-memo
         (mf/deps text-shapes-ids editor-state edition)
         #(cond-> (select-keys objects text-shapes-ids)
            (some? editor-state)
            (d/update-when edition update-with-editor-state editor-state)))

        prev-text-shapes (hooks/use-previous text-shapes)

        ;; A change in position-data won't be a "real" change
        text-change?
        (fn [id]
          (not= (-> (get text-shapes id)
                    (dissoc :position-data))
                (-> (get prev-text-shapes id)
                    (dissoc :position-data))))

        changed-texts
        (->> (keys text-shapes)
             (filter text-change?)
             (map (d/getf text-shapes)))]

    (for [{:keys [id] :as shape} changed-texts]
      [:& text-container {:shape (dissoc shape :transform :transform-inverse)
                          :key (str (dm/str "text-container-" id))}])))
