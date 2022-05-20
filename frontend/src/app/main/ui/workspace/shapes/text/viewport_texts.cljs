;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text.viewport-texts
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.helpers :as cph]
   [app.common.text :as txt]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.text.fo-text :as fo]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [app.util.text-svg-position :as utp]
   [app.util.timers :as ts]
   [rumext.alpha :as mf]))

(defn strip-position-data [shape]
  (dissoc shape :position-data :transform :transform-inverse))

(defn strip-modifier
  [modifier]
  (if (or (some? (get-in modifier [:modifiers :resize-vector]))
          (some? (get-in modifier [:modifiers :resize-vector-2])))
    modifier
    (d/update-when modifier :modifiers dissoc :displacement :rotation)))

(defn process-shape [modifiers {:keys [id] :as shape}]
  (let [modifier (-> (get modifiers id) strip-modifier)
        shape (cond-> shape
                (not (gsh/empty-modifiers? (:modifiers modifier)))
                (-> (assoc :grow-type :fixed)
                    (merge modifier) gsh/transform-shape))]
    (strip-position-data shape)))

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
      (and (some? shape) (some? editor-content))
      (assoc :content (d/txt-merge content editor-content)))))

(defn- update-text-shape
  [{:keys [grow-type id]} node]
  ;; Check if we need to update the size because it's auto-width or auto-height
  (when (contains? #{:auto-height :auto-width} grow-type)
    (let [{:keys [width height]}
          (-> (dom/query node ".paragraph-set")
              (dom/get-client-size))
          width (mth/ceil width)
          height (mth/ceil height)]
      (when (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
        (st/emit! (dwt/resize-text id width height)))))

  ;; Update the position-data of every text fragment
  (let [position-data (utp/calc-position-data node)]
    (st/emit! (dwt/update-position-data id position-data)))

  (st/emit! (dwt/clean-text-modifier id)))

(defn- update-text-modifier
  [{:keys [grow-type id]} node]
  (let [position-data (utp/calc-position-data node)
        props {:position-data position-data}

        props
        (if (contains? #{:auto-height :auto-width} grow-type)
          (let [{:keys [width height]} (-> (dom/query node ".paragraph-set") (dom/get-client-size))
                width (mth/ceil width)
                height (mth/ceil height)]
            (if (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
              (assoc props :width width :height height)
              props))
          props)]

    (st/emit! (dwt/update-text-modifier id props))))

(mf/defc text-container
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [shape       (obj/get props "shape")
        on-update   (obj/get props "on-update")

        handle-update
        (mf/use-callback
         (mf/deps shape on-update)
         (fn [node]
           (when (some? node)
             (on-update shape node))))]

    [:& fo/text-shape {:key (str "shape-" (:id shape))
                       :ref handle-update
                       :shape shape
                       :grow-type (:grow-type shape)}]))

(mf/defc viewport-texts-wrapper
  {::mf/wrap-props false
   ::mf/wrap [mf/memo #(mf/deferred % ts/idle-then-raf)]}
  [props]
  (let [text-shapes (obj/get props "text-shapes")
        modifiers (obj/get props "modifiers")
        prev-modifiers (hooks/use-previous modifiers)
        prev-text-shapes (hooks/use-previous text-shapes)

        ;; A change in position-data won't be a "real" change
        text-change?
        (fn [id]
          (let [old-shape (get prev-text-shapes id)
                new-shape (get text-shapes id)
                old-modifiers (-> (get prev-modifiers id) strip-modifier)
                new-modifiers (-> (get modifiers id) strip-modifier)]
            (or (and (not (identical? old-shape new-shape))
                     (not= old-shape new-shape))
                (not= new-modifiers old-modifiers))))

        changed-texts
        (mf/use-memo
         (mf/deps text-shapes modifiers)
         #(->> (keys text-shapes)
               (filter text-change?)
               (map (d/getf text-shapes))))

        handle-update-modifier (mf/use-callback update-text-modifier)
        handle-update-shape (mf/use-callback update-text-shape)]

    [:*
     (for [{:keys [id] :as shape} changed-texts]
       [:& text-container {:shape (gsh/transform-shape shape)
                           :on-update (if (some? (get modifiers (:id shape)))
                                        handle-update-modifier
                                        handle-update-shape)
                           :key (str (dm/str "text-container-" id))}])]))

(mf/defc viewport-text-editing
  {::mf/wrap-props false}
  [props]

  (let [shape   (obj/get props "shape")

        ;; Join current objects with the state of the editor
        editor-state
        (-> (mf/deref refs/workspace-editor-state)
            (get (:id shape)))

        text-modifier-ref
        (mf/use-memo (mf/deps (:id shape)) #(refs/workspace-text-modifier-by-id (:id shape)))

        text-modifier
        (mf/deref text-modifier-ref)

        shape (cond-> shape
                (some? editor-state)
                (update-with-editor-state editor-state))

        ;; When we have a text with grow-type :auto-height we need to check the correct height
        ;; otherwise the center alignment will break
        shape
        (if (or (not= :auto-height (:grow-type shape)) (empty? text-modifier))
          shape
          (let [tr-shape (dwt/apply-text-modifier shape text-modifier)]
            (cond-> shape
              ;; we only change the height otherwise could cause problems with the other fields
              (some? text-modifier)
              (assoc  :height (:height tr-shape)))))

        shape (hooks/use-equal-memo shape)

        handle-update-shape (mf/use-callback update-text-modifier)]

    (mf/use-effect
     (mf/deps (:id shape))
     (fn []
       #(st/emit! (dwt/remove-text-modifier (:id shape)))))

    [:& text-container {:shape shape
                        :on-update handle-update-shape}]))

(defn check-props
  [new-props old-props]
  (and (identical? (unchecked-get new-props "objects")
                   (unchecked-get old-props "objects"))
       (identical? (unchecked-get new-props "modifiers")
                   (unchecked-get old-props "modifiers"))
       (= (unchecked-get new-props "edition")
          (unchecked-get old-props "edition"))))

(mf/defc viewport-texts
  {::mf/wrap-props false
   ::mf/wrap [#(mf/memo' % check-props)]}
  [props]
  (let [objects   (obj/get props "objects")
        edition   (obj/get props "edition")
        modifiers (obj/get props "modifiers")

        text-shapes
        (mf/use-memo
         (mf/deps objects)
         #(into {} (filter (comp cph/text-shape? second)) objects))

        text-shapes
        (mf/use-memo
         (mf/deps text-shapes modifiers)
         #(d/update-vals text-shapes (partial process-shape modifiers)))

        editing-shape (get text-shapes edition)]

    ;; We only need the effect to run on "mount" because the next fonts will be changed when the texts are
    ;; edited
    (mf/use-effect
     (fn []
       (let [text-nodes (->> text-shapes (vals)(mapcat #(txt/node-seq txt/is-text-node? (:content %))))
             fonts (into #{} (keep :font-id) text-nodes)]
         (run! fonts/ensure-loaded! fonts))))

    [:*
     (when editing-shape
       [:& viewport-text-editing {:shape editing-shape}])

     [:& viewport-texts-wrapper {:text-shapes (dissoc text-shapes edition)
                                 :modifiers modifiers}]]))
