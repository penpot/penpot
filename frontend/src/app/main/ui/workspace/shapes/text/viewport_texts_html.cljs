;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text.viewport-texts-html
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gsht]
   [app.common.math :as mth]
   [app.common.text :as txt]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as mdwm]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.text.html-text :as html]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [app.util.text-svg-position :as tsp]
   [app.util.text.content :as content]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defn fix-position
  [shape]
  (if-let [modifiers (:modifiers shape)]
    (let [shape' (gsh/transform-shape shape modifiers)

          old-sr (dm/get-prop shape :selrect)
          new-sr (dm/get-prop shape' :selrect)

          ;; We need to remove the movement because the dynamic modifiers will have move it
          deltav (gpt/to-vec (gpt/point new-sr)
                             (gpt/point old-sr))]
      (-> shape
          (gsh/transform-shape (ctm/move modifiers deltav))
          (mdwm/update-grow-type shape)
          (dissoc :modifiers)))
    shape))

(defn- update-shape-with-content
  [shape content editor-content]
  (cond-> shape
    (and (some? shape) (some? editor-content))
    (assoc :content (d/txt-merge content editor-content))))

(defn- update-with-editor-state
  "Updates the shape with the current state in the editor"
  [shape editor-state]
  (let [content (:content shape)
        editor-content
        (when editor-state
          (-> editor-state
              (ted/get-editor-current-content)
              (ted/export-content)))]

    (update-shape-with-content shape content editor-content)))

(defn- update-with-editor-v2
  "Updates the shape with the current editor"
  [shape editor]
  (let [content (:content shape)
        editor-content (content/dom->cljs (.-root editor))]

    (update-shape-with-content shape content editor-content)))

(defn- update-text-shape
  [{:keys [grow-type id migrate] :as shape} node]
  ;; Check if we need to update the size because it's auto-width or auto-height
  ;; Update the position-data of every text fragment
  (->> (tsp/calc-position-data id)
       (p/fmap (fn [position-data]
                 ;; At least one paragraph needs to be inside the bounding box
                 (when (gsht/overlaps-position-data? shape position-data)
                   (st/emit! (dwt/update-position-data id position-data)))

                 (when (contains? #{:auto-height :auto-width} grow-type)
                   (let [{:keys [width height]}
                         (-> (dom/query node ".paragraph-set")
                             (dom/get-bounding-rect))

                         width (mth/ceil width)
                         height (mth/ceil height)]
                     (when (and (not (mth/almost-zero? width))
                                (not (mth/almost-zero? height))
                                (not migrate))
                       (st/emit! (dwt/resize-text id width height)))))

                 (st/emit! (dwt/clean-text-modifier id))))))

(defn- update-text-modifier
  [{:keys [grow-type id] :as shape} node]
  (->> (tsp/calc-position-data id)
       (p/fmap (fn [position-data]
                 (let [props {:position-data position-data}]
                   (if (contains? #{:auto-height :auto-width} grow-type)
                     (let [{:keys [width height]} (-> (dom/query node ".paragraph-set") (dom/get-client-size))
                           width (mth/ceil width)
                           height (mth/ceil height)]
                       (if (and (not (mth/almost-zero? width)) (not (mth/almost-zero? height)))
                         (cond-> props
                           (= grow-type :auto-width)
                           (assoc :width width)

                           (or (= grow-type :auto-height) (= grow-type :auto-width))
                           (assoc :height height))
                         props))
                     props))))
       (p/fmap #(st/emit! (dwt/update-text-modifier id %)))))

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

    [:& html/text-shape {:key (str "shape-" (:id shape))
                         :ref handle-update
                         :shape shape
                         :grow-type (:grow-type shape)}]))

(defn text-properties-equal?
  [shape other]
  (or (identical? shape other)
      (and (= (:grow-type shape) (:grow-type other))
           (= (:content shape) (:content other))
           ;; Check if the position and size is close. If any of these changes the shape has changed
           ;; and if not there is no geometry relevant change
           (mth/close? (dm/get-prop shape :x) (dm/get-prop other :x))
           (mth/close? (dm/get-prop shape :y) (dm/get-prop other :y))
           (mth/close? (dm/get-prop shape :width) (dm/get-prop other :width))
           (mth/close? (dm/get-prop shape :height) (dm/get-prop other :height)))))

(mf/defc text-changes-renderer
  {::mf/wrap-props false}
  [props]
  (let [text-shapes      (unchecked-get props "text-shapes")

        prev-text-shapes (hooks/use-previous text-shapes)

        ;; We store in the state the texts still pending to be calculated so we can
        ;; get its position
        pending-update* (mf/use-state {})
        pending-update  (deref pending-update*)

        text-change?
        (fn [id]
          (let [new-shape (get text-shapes id)
                old-shape (get prev-text-shapes id)
                remote?   (some? (-> new-shape meta :session-id))]

            (or (and (not remote?) ;; changes caused by a remote peer are not re-calculated
                     (not (text-properties-equal? old-shape new-shape)))
                ;; When the position data is nil we force to recalculate
                (nil? (:position-data new-shape)))))

        changed-texts
        (mf/with-memo [text-shapes pending-update]
          (let [pending-shapes (into #{} (vals pending-update))]
            (->> (keys text-shapes)
                 (filter (fn [id]
                           (or (contains? pending-shapes id)
                               (text-change? id))))
                 (map (d/getf text-shapes)))))

        handle-update-shape
        (mf/use-fn
         (fn [shape node]
           ;; Unique to indentify the pending state
           (let [uid (uuid/next)]
             (swap! pending-update* assoc uid (:id shape))
             (p/then
              (update-text-shape shape node)
              #(swap! pending-update* dissoc uid)))))]

    [:.text-changes-renderer
     (for [{:keys [id] :as shape} changed-texts]
       [:& text-container {:key (dm/str "text-container-" id)
                           :shape shape
                           :on-update handle-update-shape}])]))

(mf/defc text-modifiers-renderer
  {::mf/wrap-props false}
  [props]
  (let [text-shapes (-> (obj/get props "text-shapes")
                        (update-vals fix-position))

        prev-text-shapes (hooks/use-previous text-shapes)

        text-change?
        (fn [id]
          (let [new-shape (get text-shapes id)
                old-shape (get prev-text-shapes id)]
            (and
             (some? new-shape)
             (some? old-shape)
             (not (text-properties-equal? old-shape new-shape)))))

        changed-texts
        (mf/use-memo
         (mf/deps text-shapes)
         #(->> (keys text-shapes)
               (filter text-change?)
               (map (d/getf text-shapes))))

        handle-update-shape (mf/use-callback update-text-modifier)]

    [:.text-changes-renderer
     (for [{:keys [id] :as shape} changed-texts]
       [:& text-container {:key (dm/str "text-container-" id)
                           :shape shape
                           :on-update handle-update-shape}])]))

(mf/defc viewport-text-editing
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [shape   (obj/get props "shape")
        shape-id (:id shape)

        workspace-editor-state (mf/deref refs/workspace-editor-state)
        workspace-v2-editor-state (mf/deref refs/workspace-v2-editor-state)
        workspace-editor (mf/deref refs/workspace-editor)

        editor-state (get workspace-editor-state shape-id)
        v2-editor-state (get workspace-v2-editor-state shape-id)

        text-modifier-ref
        (mf/use-memo (mf/deps shape-id) #(refs/workspace-text-modifier-by-id shape-id))

        text-modifier
        (mf/deref text-modifier-ref)

        shape (cond-> shape
                (some? editor-state)
                (update-with-editor-state editor-state)

                (and (some? v2-editor-state) (some? workspace-editor))
                (update-with-editor-v2 workspace-editor))

        ;; When we have a text with grow-type :auto-height or :auto-height we need to check the correct height
        ;; otherwise the center alignment will break
        shape
        (if (some? text-modifier)
          (let [{:keys [width height]} (dwt/apply-text-modifier shape text-modifier)]
            (assoc shape :width width :height height))
          shape)

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
         (fn []
           (into {} (filter (comp cfh/text-shape? second)) objects)))

        text-shapes
        (hooks/use-equal-memo text-shapes)

        editing-shape (mf/use-memo
                       (mf/deps text-shapes edition)
                       #(get text-shapes edition))

        editing-shape
        (hooks/use-equal-memo editing-shape)

        text-shapes-changes
        (mf/use-memo
         (mf/deps text-shapes edition)
         (fn []
           (-> text-shapes
               (dissoc edition))))

        text-shapes-modifiers
        (mf/use-memo
         (mf/deps modifiers text-shapes)
         (fn []
           (into {}
                 (keep (fn [[id modifiers]]
                         (when-let [shape (get text-shapes id)]
                           (vector id (d/patch-object shape modifiers)))))
                 modifiers)))]

    ;; We only need the effect to run on "mount" because the next fonts will be changed when the texts are
    ;; edited
    (mf/use-effect
     (fn []
       (let [text-nodes (->> text-shapes (vals) (mapcat #(txt/node-seq txt/is-text-node? (:content %))))
             fonts (into #{} (keep :font-id) text-nodes)]
         (run! fonts/ensure-loaded! fonts))))

    [:*
     (when editing-shape
       [:& viewport-text-editing {:shape editing-shape}])

     [:& text-modifiers-renderer {:text-shapes text-shapes-modifiers}]
     [:& text-changes-renderer {:text-shapes text-shapes-changes}]]))
