;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text.editor
  (:require
   ["draft-js" :as draft]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gst]
   [app.common.math :as mth]
   [app.common.text :as legacy.txt]
   [app.config :as cf]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.shapes.text.styles :as sts]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.text-editor :as ted]
   [goog.events :as events]
   [rumext.v2 :as mf]))

;; --- Text Editor Rendering

(mf/defc block-component
  {::mf/wrap-props false}
  [props]
  (let [bprops (obj/get props "blockProps")
        data   (obj/get bprops "data")
        style  (sts/generate-paragraph-styles (obj/get bprops "shape")
                                              (obj/get bprops "data"))
        dir    (:text-direction data "auto")]


    [:div {:style style :dir dir}
     [:> draft/EditorBlock props]]))

(mf/defc selection-component
  {::mf/wrap-props false}
  [props]
  (let [children (obj/get props "children")]
    [:span {:style {:background "#ccc" :display "inline-block"}} children]))

(defn- render-block
  [block shape]
  (let [type (ted/get-editor-block-type block)]
    (case type
      "unstyled"
      #js {:editable true
           :component block-component
           :props #js {:data (ted/get-editor-block-data block)
                       :shape shape}}
      nil)))

(defn- styles-fn [shape styles content]
  (let [data (if (= (.getText ^js content) "")
               (-> ^js (.getData content)
                   (.toJS)
                   (js->clj :keywordize-keys true))
               (legacy.txt/styles-to-attrs styles))]
    (sts/generate-text-styles shape data {:show-text? false})))

(def default-decorator
  (ted/create-decorator "PENPOT_SELECTION" selection-component))

(def empty-editor-state
  (ted/create-editor-state nil default-decorator))

(defn- get-blocks-to-setup [block-changes]
  (->> block-changes
       (filter (fn [[_ v]]
                 (nil? (:old v))))
       (mapv first)))

(defn- get-blocks-to-add-styles
  [block-changes]
  (->> block-changes
       (filter (fn [[_ v]]
                 (and (not= (:old v) (:new v)) (= (:old v) ""))))
       (mapv first)))

(defn- shape->justify
  [{:keys [content]}]
  (case (d/nilv (:vertical-align content) "top")
    "center" "center"
    "top"    "flex-start"
    "bottom" "flex-end"
    nil))

(mf/defc text-shape-edit-html
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false
   ::mf/forward-ref true}
  [props _]
  (let [{:keys [id content] :as shape} (obj/get props "shape")

        state-map     (mf/deref refs/workspace-editor-state)
        state         (get state-map id empty-editor-state)
        self-ref      (mf/use-ref)

        text-modifier-ref
        (mf/use-memo (mf/deps (:id shape)) #(refs/workspace-text-modifier-by-id (:id shape)))

        text-modifier
        (mf/deref text-modifier-ref)

        shape (cond-> shape
                (some? text-modifier)
                (dwt/apply-text-modifier text-modifier))

        blurred        (mf/use-var false)

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (kbd/esc? event)
            (st/emit! :interrupt (dw/clear-edition-mode))))

        on-mount
        (fn []
          (let [keys [(events/listen js/document "keyup" on-key-up)]]
            (st/emit! (dwt/initialize-editor-state shape default-decorator)
                      (dwt/select-all shape))
            #(do
               (st/emit! ::dwt/finalize-editor-state)
               (doseq [key keys]
                 (events/unlistenByKey key)))))

        on-blur
        (mf/use-callback
         (mf/deps shape state)
         (fn [event]
           (let [is-empty? (ted/is-current-empty state)]
             (dom/stop-propagation event)
             (dom/prevent-default event)
             (when (not is-empty?)
               (st/emit! ::dwt/finalize-editor-state)
               (st/emit! (dwt/initialize-editor-state shape default-decorator)))
             (reset! blurred true))))

        on-focus
        (mf/use-callback
         (mf/deps shape state)
         (fn [_]
           (reset! blurred false)))

        prev-value (mf/use-ref state)

        ;; Effect that keeps updated the `prev-value` reference
        _ (mf/use-effect
           (mf/deps state)
           #(mf/set-ref-val! prev-value state))

        handle-change
        (mf/use-callback
         (fn [state]
           (let [old-state (mf/ref-val prev-value)]
             (if (and (some? state) (some? old-state))
               (let [block-changes       (ted/get-content-changes old-state state)
                     prev-data           (ted/get-editor-current-inline-styles old-state)
                     block-to-setup      (get-blocks-to-setup block-changes)
                     block-to-add-styles (get-blocks-to-add-styles block-changes)]
                 (-> state
                     (ted/setup-block-styles block-to-setup prev-data)
                     (ted/apply-block-styles-to-content block-to-add-styles)))
               state))))

        on-change
        (mf/use-callback
         (fn [val]
           (let [prev-val (mf/ref-val prev-value)
                 styleOverride (ted/get-style-override prev-val)

                 ;; If the content and the selection are the same we keep the style override
                 keep-style? (and (some? styleOverride)
                                  (ted/content-equals prev-val val)
                                  (ted/selection-equals prev-val val))

                 val (cond-> (handle-change val)
                       @blurred
                       (ted/add-editor-blur-selection)

                       (not @blurred)
                       (ted/remove-editor-blur-selection)

                       keep-style?
                       (ted/set-style-override styleOverride))]
             (st/emit! (dwt/update-editor-state shape val)))))

        on-editor
        (mf/use-callback
         (fn [editor]
           (st/emit! (dwt/update-editor editor))
           (when editor
             (dom/add-class! (dom/get-element-by-class "public-DraftEditor-content") "mousetrap")
             (.focus ^js editor))))

        handle-return
        (mf/use-callback
         (fn [_ state]
           (let [style (ted/get-editor-current-block-data state)
                 state (-> (ted/insert-text state "\n" style)
                           (handle-change))]
             (st/emit! (dwt/update-editor-state shape state)))
           "handled"))

        on-pointer-down
        (mf/use-callback
         (fn [event]
           (when (dom/class? (dom/get-target event) "DraftEditor-root")
             (st/emit! (dwt/cursor-to-end shape)))
           (st/emit! (dwt/focus-editor))))

        handle-pasted-text
        (fn [text _ _]
          (let [current-block-styles (ted/get-editor-current-block-data state)
                inline-styles        (ted/get-editor-current-inline-styles state)
                style                (merge current-block-styles inline-styles)
                state                (-> (ted/insert-text state text style)
                                         (handle-change))]
            (st/emit! (dwt/update-editor-state shape state)))
          "handled")]

    (mf/use-layout-effect on-mount)

    [:div.text-editor
     {:ref self-ref
      :style {:width (:width shape)
              :height (:height shape)
              ;; We hide the editor when is blurred because otherwise the selection won't let us see
              ;; the underlying text. Use opacity because display or visibility won't allow to recover
              ;; focus afterwards.
              :opacity (when @blurred 0)}
      :on-pointer-down on-pointer-down
      :class (dom/classnames
              (cur/get-dynamic "text" (:rotation shape)) true
              :align-top    (= (:vertical-align content "top") "top")
              :align-center (= (:vertical-align content) "center")
              :align-bottom (= (:vertical-align content) "bottom"))}
     [:> draft/Editor
      {:on-change on-change
       :on-blur on-blur
       :on-focus on-focus
       :handle-return handle-return
       :strip-pasted-styles true
       :handle-pasted-text handle-pasted-text
       :custom-style-fn (partial styles-fn shape)
       :block-renderer-fn #(render-block % shape)
       :ref on-editor
       :editor-state state
       :style #js {:border "1px solid red"}}]]))

(defn translate-point-from-viewport
  "Translate a point in the viewport into client coordinates"
  [pt viewport zoom]
  (when (some? viewport)
    (let [vbox     (.. ^js viewport -viewBox -baseVal)
          box      (gpt/point (.-x vbox) (.-y vbox))
          zoom     (gpt/point zoom)]
      (-> (gpt/subtract pt box)
          (gpt/multiply zoom)))))

(mf/defc text-editor-svg
  {::mf/wrap-props false}
  [{:keys [shape modifiers]}]
  (let [shape-id  (dm/get-prop shape :id)
        modifiers (dm/get-in modifiers [shape-id :modifiers])

        clip-id   (dm/str "text-edition-clip" shape-id)

        text-modifier-ref
        (mf/with-memo [shape-id]
          (refs/workspace-text-modifier-by-id shape-id))

        text-modifier
        (mf/deref text-modifier-ref)

        ;; For Safari It's necesary to scale the editor with the zoom
        ;; level to fix a problem with foreignObjects not scaling
        ;; correctly with the viewbox
        ;;
        ;; NOTE: this teoretically breaks hooks rules, but in practice
        ;; it is imposible to really break it
        maybe-zoom
        (when (cf/check-browser? :safari-16)
          (mf/deref refs/selected-zoom))

        shape (cond-> shape
                (some? text-modifier)
                (dwt/apply-text-modifier text-modifier)

                (some? modifiers)
                (gsh/transform-shape modifiers))

        bounds (gst/shape->rect shape)

        x      (mth/min (dm/get-prop bounds :x)
                        (dm/get-prop shape :x))
        y      (mth/min (dm/get-prop bounds :y)
                        (dm/get-prop shape :y))
        width  (mth/max (dm/get-prop bounds :width)
                        (dm/get-prop shape :width))
        height (mth/max (dm/get-prop bounds :height)
                        (dm/get-prop shape :height))

        style
        (cond-> #js {:pointerEvents "all"}

          (not (cf/check-browser? :safari))
          (obj/merge!
           #js {:transform (dm/fmt "translate(%px, %px)" (- (dm/get-prop shape :x) x) (- (dm/get-prop shape :y) y))})

          (cf/check-browser? :safari-17)
          (obj/merge!
           #js {:height "100%"
                :display "flex"
                :flexDirection "column"
                :justifyContent (shape->justify shape)})

          (cf/check-browser? :safari-16)
          (obj/merge!
           #js {:position "fixed"
                :left 0
                :top  (- (dm/get-prop shape :y) y)
                :transform-origin "top left"
                :transform (when (some? maybe-zoom)
                             (dm/fmt "scale(%)" maybe-zoom))}))]

    [:g.text-editor {:clip-path (dm/fmt "url(#%)" clip-id)
                     :transform (dm/str (gsh/transform-matrix shape))}
     [:defs
      [:clipPath {:id clip-id}
       [:rect {:x x :y y :width width :height height}]]]

     [:foreignObject {:x x :y y :width width :height height}
      [:div {:style style}
       [:& text-shape-edit-html
        {:shape shape
         :key (dm/str shape-id)}]]]]))
