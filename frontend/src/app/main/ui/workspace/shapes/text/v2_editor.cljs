;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text.v2-editor
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.text :as gst]
   [app.common.math :as mth]
   [app.common.types.color :as color]
   [app.common.types.text :as txt]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.texts :as dwt]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.hooks :as h]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.globals :as global]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.text.content :as content]
   [app.util.text.content.styles :as styles]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn get-contrast-color [background-color]
  (when background-color
    (let [luminance (color/hex->lum background-color)]
      (if (> luminance 0.5) "#000000" "#ffffff"))))

(defn- gen-name
  [editor]
  (when (some? editor)
    (let [editor-root (.-root editor)
          result (.-textContent editor-root)]
      (when (not= result "") result))))

(defn- get-fonts
  [content]
  (let [extract-fn (juxt :font-id :font-variant-id)
        default    (extract-fn txt/default-typography)]
    (->> (tree-seq map? :children content)
         (into #{default} (keep extract-fn)))))

(defn- load-fonts!
  [fonts]
  (->> fonts
       (run! (fn [[font-id variant-id]]
               (when (some? font-id)
                 (fonts/ensure-loaded! font-id variant-id))))))

(defn- initialize-event-handlers
  "Internal editor events handler initializer/destructor"
  [shape-id content editor-ref canvas-ref container-ref text-color]
  (let [editor-node
        (mf/ref-val editor-ref)

        canvas-node
        (mf/ref-val canvas-ref)

        ;; Gets the default font from the workspace refs.
        default-font
        (deref refs/default-font)

        style-defaults
        (styles/get-style-defaults
         (merge
          (txt/get-default-text-attrs)
          {:fills [{:fill-color text-color :fill-opacity 1}]}
          txt/default-root-attrs
          default-font))

        options
        #js {:styleDefaults style-defaults
             :allowHTMLPaste (features/active-feature? @st/state "text-editor/v2-html-paste")}

        instance
        (dwt/create-editor editor-node canvas-node options)

        update-name? (nil? content)

        on-key-up
        (fn [event]
          (dom/stop-propagation event)
          (when (kbd/esc? event)
            (st/emit! :interrupt (dw/clear-edition-mode))))

        on-blur
        (fn []
          (when-let [content (content/dom->cljs (dwt/get-editor-root instance))]
            (st/emit! (dwt/v2-update-text-shape-content shape-id content
                                                        :update-name? update-name?
                                                        :name (gen-name instance)
                                                        :finalize? true)))

          (let [container-node (mf/ref-val container-ref)]
            (dom/set-style! container-node "opacity" 0)))

        on-focus
        (fn []
          (let [container-node (mf/ref-val container-ref)]
            (dom/set-style! container-node "opacity" 1)))

        on-style-change
        (fn [event]
          (let [styles (styles/get-styles-from-event event)]
            (st/emit! (dwt/v2-update-text-editor-styles shape-id styles))))

        on-needs-layout
        (fn []
          (when-let [content (content/dom->cljs (dwt/get-editor-root instance))]
            (st/emit! (dwt/v2-update-text-shape-content shape-id content :update-name? true)))
          ;; FIXME: We need to find a better way to trigger layout changes.
          #_(st/emit!
             (dwt/v2-update-text-shape-position-data shape-id [])))

        on-change
        (fn []
          (when-let [content (content/dom->cljs (dwt/get-editor-root instance))]
            (st/emit! (dwt/v2-update-text-shape-content shape-id content :update-name? true))))

        on-clipboard-change
        (fn [event]
          (let [style (.-detail event)]
            (st/emit! (dw/set-clipboard-style style))))]

    (.addEventListener ^js global/document "keyup" on-key-up)
    (.addEventListener ^js instance "blur" on-blur)
    (.addEventListener ^js instance "focus" on-focus)
    (.addEventListener ^js instance "needslayout" on-needs-layout)
    (.addEventListener ^js instance "stylechange" on-style-change)
    (.addEventListener ^js instance "change" on-change)
    (.addEventListener ^js instance "clipboardchange" on-clipboard-change)

    (st/emit! (dwt/update-editor instance))
    (when (some? content)
      (dwt/set-editor-root! instance (content/cljs->dom content)))
    (when (some? instance)
      (st/emit! (dwt/focus-editor)))

    ;; This function is called when the component is unmounted
    (fn []
      (.removeEventListener ^js global/document "keyup" on-key-up)
      (.removeEventListener ^js instance "blur" on-blur)
      (.removeEventListener ^js instance "focus" on-focus)
      (.removeEventListener ^js instance "needslayout" on-needs-layout)
      (.removeEventListener ^js instance "stylechange" on-style-change)
      (.removeEventListener ^js instance "change" on-change)
      (.removeEventListener ^js instance "clipboardchange" on-clipboard-change)
      (dwt/dispose! instance)
      (st/emit! (dwt/update-editor nil)))))

(defn get-color-from-content [content]
  (let [fills (->> (tree-seq map? :children content)
                   (mapcat :fills)
                   (filter :fill-color))]
    (some :fill-color fills)))

(defn get-default-text-color
  "Returns the appropriate text color based on fill, frame, and background."
  [{:keys [frame background-color]}]
  (if (and frame (not (cfh/root? frame)) (seq (:fills frame)))
    (let [fill-color (some #(when (:fill-color %) (:fill-color %)) (:fills frame))]
      (if fill-color
        (get-contrast-color fill-color)
        (get-contrast-color background-color)))
    (get-contrast-color background-color)))

(mf/defc text-editor-html
  "Text editor (HTML)"
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [shape canvas-ref]}]
  (let [content          (:content shape)
        shape-id         (dm/get-prop shape :id)
        fill-color       (get-color-from-content content)

        ;; This is a reference to the dom element that
        ;; should contain the TextEditor.
        editor-ref       (mf/use-ref nil)
        ;; This reference is to the container
        container-ref    (mf/use-ref nil)

        page             (mf/deref refs/workspace-page)
        objects          (get page :objects)
        frame            (cfh/get-frame objects shape-id)
        background-color (:background page)

        text-color       (or fill-color (get-default-text-color {:frame frame
                                                                 :background-color background-color}) color/black)

        fonts
        (-> (mf/use-memo (mf/deps content) #(get-fonts content))
            (h/use-equal-memo))]

    (mf/with-effect [fonts]
      (load-fonts! fonts))

    ;; WARN: we explicitly do not pass content on effect dependency
    ;; array because we only need to initialize this once with initial
    ;; content
    (mf/with-effect [shape-id]
      (initialize-event-handlers shape-id
                                 content
                                 editor-ref
                                 canvas-ref
                                 container-ref
                                 text-color))

    (mf/with-effect [text-color]
      (let [container-node (mf/ref-val container-ref)]
        (dom/set-style! container-node "--text-editor-caret-color" text-color)))

    [:div
     {:class (dm/str (cur/get-dynamic "text" (:rotation shape))
                     " "
                     (stl/css :text-editor-container))
      :ref container-ref
      :data-testid "text-editor-container"
      :style {:width "var(--editor-container-width)"
              :height "var(--editor-container-height)"}
      ;; We hide the editor when is blurred because otherwise the
      ;; selection won't let us see the underlying text. Use opacity
      ;; because display or visibility won't allow to recover focus
      ;; afterwards.

      ;; IMPORTANT! This is now done through DOM mutations (see
      ;; on-blur and on-focus) but I keep this for future references.
      ;; :opacity (when @blurred 0)}}
      }
     [:div
      {:class (dm/str
               "mousetrap "
               (stl/css-case
                :text-editor-content true
                :grow-type-fixed (= (:grow-type shape) :fixed)
                :grow-type-auto-width (= (:grow-type shape) :auto-width)
                :grow-type-auto-height (= (:grow-type shape) :auto-height)
                :align-top    (= (:vertical-align content "top") "top")
                :align-center (= (:vertical-align content) "center")
                :align-bottom (= (:vertical-align content) "bottom")))
       :ref editor-ref
       :data-testid "text-editor-content"
       :data-x (dm/get-prop shape :x)
       :data-y (dm/get-prop shape :y)
       :content-editable true
       :role "textbox"
       :aria-multiline true
       :aria-autocomplete "none"}]]))

(defn- shape->justify
  [{:keys [content]}]
  (case (d/nilv (:vertical-align content) "top")
    "center" "center"
    "top"    "flex-start"
    "bottom" "flex-end"
    nil))

(defn- font-family-from-font-id [font-id]
  (if (str/includes? font-id "gfont-noto-sans")
    (let [lang (str/replace font-id #"gfont\-noto\-sans\-" "")]
      (if (>= (count lang) 3) (str/capital lang) (str/upper lang)))
    "Noto Color Emoji"))

;; Text Editor Wrapper
;; This is an SVG element that wraps the HTML editor.
;;
(mf/defc text-editor
  "Text editor wrapper component"
  {::mf/wrap [mf/memo]
   ::mf/props :obj
   ::mf/forward-ref true}
  [{:keys [shape modifiers canvas-ref] :as props} _]
  (let [shape-id  (dm/get-prop shape :id)
        modifiers (dm/get-in modifiers [shape-id :modifiers])

        fallback-fonts (wasm.api/fonts-from-text-content (:content shape) false)
        fallback-families (map (fn [font]
                                 (font-family-from-font-id (:font-id font))) fallback-fonts)

        clip-id   (dm/str "text-edition-clip" shape-id)

        text-modifier-ref
        (mf/use-memo (mf/deps (:id shape)) #(refs/workspace-text-modifier-by-id (:id shape)))

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

        render-wasm? (mf/use-memo #(features/active-feature? @st/state "render-wasm/v1"))

        [{:keys [x y width height]} transform]
        (if render-wasm?
          (let [{:keys [height]} (wasm.api/get-text-dimensions shape-id)
                selrect-transform (mf/deref refs/workspace-selrect)
                [selrect transform] (dsh/get-selrect selrect-transform shape)
                selrect-height (:height selrect)
                max-height (max height selrect-height)
                valign (-> shape :content :vertical-align)
                y (:y selrect)
                y (if (and valign (> height selrect-height))
                    (case valign
                      "bottom" (- y (- height selrect-height))
                      "center" (- y (/ (- height selrect-height) 2))
                      "top"    y)
                    y)]
            [(assoc selrect :y y :width (:width selrect) :height max-height) transform])

          (let [bounds (gst/shape->rect shape)
                x      (mth/min (dm/get-prop bounds :x)
                                (dm/get-prop shape :x))
                y      (mth/min (dm/get-prop bounds :y)
                                (dm/get-prop shape :y))
                width  (mth/max (dm/get-prop bounds :width)
                                (dm/get-prop shape :width))
                height (mth/max (dm/get-prop bounds :height)
                                (dm/get-prop shape :height))]
            [(grc/make-rect x y width height) (gsh/transform-matrix shape)]))

        style
        (cond-> #js {:pointerEvents "all"}
          render-wasm?
          (obj/merge!
           #js {"--editor-container-width" (dm/str width "px")
                "--editor-container-height" (dm/str height "px")
                "--fallback-families" (dm/str (str/join ", " fallback-families))})

          (not render-wasm?)
          (obj/merge!
           #js {"--editor-container-width" (dm/str width "px")
                "--editor-container-height" (dm/str height "px")})

          ;; Transform is necessary when there is a text overflow and the vertical
          ;; aligment is center or bottom.
          (and (not render-wasm?)
               (not (cf/check-browser? :safari)))
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
                     :transform (dm/str transform)}
     [:defs
      [:clipPath {:id clip-id}
       [:rect {:x x :y y :width width :height height}]]]

     [:foreignObject {:x x :y y :width width :height height}
      [:div {:style style}
       [:& text-editor-html {:shape shape
                             :canvas-ref canvas-ref
                             :key (dm/str shape-id)}]]]]))
