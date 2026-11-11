;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.shapes.text.selection-menu
  "Popover over the text selected in the WASM text editor, with the CJK
  annotations of the text language."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.texts-v3 :as dwt-v3]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.workspace.sidebar.options.menus.text-cjk-controls :as cjk-controls]
   [app.render-wasm.text-editor :as wasm.text-editor]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; Languages that show the menu.
(def ^:private menu-language-families
  #{:japanese :chinese})

;; Contenteditable of the WASM text editor (see the v3 editor).
(def ^:private editor-input-id "text-editor-wasm-input")

;; Icon of each `:text-emphasis` value.
(def ^:private emphasis-icons
  {"none"          i/remove
   "filled-dot"    i/text-emphasis-filled-dot
   "open-dot"      i/text-emphasis-open-dot
   "filled-circle" i/text-emphasis-filled-circle
   "open-circle"   i/text-emphasis-open-circle
   "filled-sesame" i/text-emphasis
   "open-sesame"   i/text-emphasis-open-sesame})

(defn- keep-editor-focus
  "Keep the focus where it is when a press lands on the menu, so the editor
  keeps its selection. The reading input takes the focus to type in it."
  [event]
  (when-not (= "INPUT" (.-tagName (dom/get-target event)))
    (dom/prevent-default event)))

(defn- focus-editor
  []
  (some-> (dom/get-element editor-input-id) (dom/focus!)))

(defn- composing-key?
  "True for a key-down that an IME composition takes."
  [event]
  (let [native (dom/event->native-event event)]
    (or (.-isComposing ^js native)
        (= 229 (.-keyCode ^js native)))))

(defn- escape-key?
  "True for an Escape key-down that does not end an IME composition."
  [event]
  (and (kbd/esc? event) (not (composing-key? event))))

(defn- apply-text-attrs
  "Apply span `attrs` to the text selected in the editor of `shape-id`, as
  the design sidebar does."
  [shape-id attrs]
  (st/emit! (dwt-v3/v3-update-text-editor-styles shape-id attrs)
            (dwt/update-all-attrs #{shape-id} attrs)))

(defn- span-value
  "Value of a span attr of the selection: nil when its spans differ."
  [styles attr]
  (let [value (get styles attr)]
    (when-not (= :multiple value) value)))

(mf/defc menu-item*
  "Button with `children` as its icon, before the label, and a caret after
  it when it opens a dropdown, open when `expanded` is true. `active` shows
  that the selected text has the annotation; `pressed` also tells it to
  assistive tech on toggles. A `disabled` item explains why in `hint`."
  {::mf/private true}
  [{:keys [label children on-click hint pressed
           ^boolean dropdown ^boolean expanded ^boolean active ^boolean disabled]}]
  [:button {:type "button"
            :class (stl/css-case :menu-item true
                                 :menu-item-active active)
            :aria-haspopup (when dropdown "menu")
            :aria-expanded (when dropdown expanded)
            :aria-pressed pressed
            :aria-disabled disabled
            :title (when disabled hint)
            :on-click (when-not disabled on-click)}
   children
   [:span {:class (stl/css :label)} label]
   (when dropdown
     [:> icon* {:icon-id i/arrow-down
                :size "s"
                :class (stl/css :caret)
                :aria-hidden true}])])

(mf/defc glyph*
  "A CJK character standing for an annotation that has no icon."
  {::mf/private true}
  [{:keys [glyph]}]
  [:span {:class (stl/css :glyph) :aria-hidden true} glyph])

(mf/defc emphasis-list*
  "The emphasis marks to choose from, `selected` checked."
  {::mf/private true}
  [{:keys [selected on-select]}]
  (let [on-click
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (on-select (dom/get-data (dom/get-current-target event) "value"))))]
    [:ul {:class (stl/css :emphasis-list)
          :role "menu"
          :aria-label (tr "workspace.options.text-options.text-emphasis")}
     (for [{:keys [id label]} (cjk-controls/text-emphasis-options)]
       (let [selected? (= id selected)]
         [:li {:key id :role "none"}
          [:button {:type "button"
                    :role "menuitemradio"
                    :aria-checked selected?
                    :class (stl/css-case :emphasis-option true
                                         :emphasis-option-selected selected?)
                    :data-value id
                    :on-click on-click}
           [:> icon* {:icon-id i/tick
                      :size "s"
                      :class (stl/css :emphasis-check)
                      :aria-hidden true}]
           [:> icon* {:icon-id (get emphasis-icons id)
                      :class (stl/css-case :emphasis-mark true
                                           :emphasis-mark-none (= id "none"))
                      :aria-hidden true}]
           [:span {:class (stl/css :emphasis-label)} label]]]))]))

(mf/defc toolbar*
  "The annotations of the `family` language for the selected text of
  `shape-id`. `annotations` holds what the text has (nil when its spans
  differ). A reading takes text without annotations or a whole base
  (`readable`); a warichu note, the text of one span (`warichu-enabled`)."
  {::mf/private true}
  [{:keys [shape-id family annotations on-ruby
           ^boolean readable ^boolean ruby-enabled ^boolean warichu-enabled]}]
  (let [emphasis-open* (mf/use-state false)
        emphasis-open  (deref emphasis-open*)

        {:keys [ruby emphasis warichu combine]} annotations

        warichu? (= "warichu" warichu)
        combine? (= "all" combine)
        ruby?    (not (str/blank? ruby))

        span-hint (tr "workspace.text-selection-menu.single-span-hint")

        toggle-emphasis
        (mf/use-fn #(swap! emphasis-open* not))

        open-ruby
        (mf/use-fn
         (mf/deps on-ruby)
         (fn []
           (reset! emphasis-open* false)
           (on-ruby)))

        select-emphasis
        (mf/use-fn
         (mf/deps shape-id)
         (fn [value]
           (reset! emphasis-open* false)
           (apply-text-attrs shape-id {:text-emphasis value})))

        toggle-warichu
        (mf/use-fn
         (mf/deps shape-id warichu?)
         (fn []
           (reset! emphasis-open* false)
           (apply-text-attrs shape-id {:warichu (if warichu? "none" "warichu")})))

        toggle-combine
        (mf/use-fn
         (mf/deps shape-id combine?)
         (fn []
           (reset! emphasis-open* false)
           (apply-text-attrs shape-id {:text-combine-upright (if combine? "none" "all")})))]

    [:*
     [:> menu-item* {:label (tr "workspace.text-selection-menu.ruby")
                     :active ruby?
                     :disabled (not ruby-enabled)
                     :hint (if readable
                             (tr "workspace.options.text-options.zhuyin-selection-hint")
                             (tr "workspace.text-selection-menu.reading-hint"))
                     :on-click open-ruby}
      [:> glyph* {:glyph "あ"}]]
     [:div {:class (stl/css :emphasis-anchor)}
      [:> menu-item* {:label (tr "workspace.text-selection-menu.emphasis")
                      :dropdown true
                      :expanded emphasis-open
                      :active (and (some? emphasis) (not= "none" emphasis))
                      :on-click toggle-emphasis}
       [:> icon* {:icon-id i/text-emphasis :aria-hidden true}]]
      (when emphasis-open
        [:> emphasis-list* {:selected emphasis
                            :on-select select-emphasis}])]
     ;; Chinese layout renders neither warichu nor tate-chu-yoko.
     (when (= family :japanese)
       [:*
        [:hr {:class (stl/css :separator)}]
        [:> menu-item* {:label (tr "workspace.text-selection-menu.warichu")
                        :active warichu?
                        :pressed warichu?
                        :disabled (not warichu-enabled)
                        :hint span-hint
                        :on-click toggle-warichu}
         [:> icon* {:icon-id i/warichu :aria-hidden true}]]
        [:> menu-item* {:label (tr "workspace.text-selection-menu.combine")
                        :active combine?
                        :pressed combine?
                        :on-click toggle-combine}
         [:> icon* {:icon-id i/text-combine-upright-all :aria-hidden true}]]])]))

(mf/defc ruby-input*
  "Input for the ruby reading of the selected text, starting from its
  current `reading`. Enter applies it, Remove drops the current one, and
  Escape and the back button return to the toolbar. A `validate` fn rejects
  the readings it returns false for."
  {::mf/private true}
  [{:keys [reading validate invalid-hint on-apply on-back]}]
  (let [reading* (mf/use-state (or reading ""))
        value    (deref reading*)
        valid?   (or (str/blank? value) (nil? validate) (validate (str/trim value)))

        ;; True when the pending Escape key-up ends a real Escape press, not
        ;; an IME composition.
        escape-ref (mf/use-ref false)

        on-change
        (mf/use-fn
         (fn [event]
           (reset! reading* (dom/get-value (dom/get-target event)))))

        on-key-down
        (mf/use-fn
         (mf/deps value valid? on-apply)
         (fn [event]
           (cond
             (kbd/esc? event)
             (mf/set-ref-val! escape-ref (escape-key? event))

             (and (kbd/enter? event) (not (composing-key? event)))
             (do
               (dom/prevent-default event)
               (when valid?
                 (on-apply (str/trim value)))))))

        ;; The editor exits on an Escape key-up reaching the document, so the
        ;; input keeps every Escape key-up to itself.
        on-key-up
        (mf/use-fn
         (mf/deps on-back)
         (fn [event]
           (when (kbd/esc? event)
             (dom/stop-propagation event)
             (when (mf/ref-val escape-ref)
               (mf/set-ref-val! escape-ref false)
               (on-back)))))

        on-remove
        (mf/use-fn
         (mf/deps on-apply)
         #(on-apply ""))]

    [:*
     [:button {:type "button"
               :class (stl/css :back-button)
               :aria-label (tr "workspace.text-selection-menu.back")
               :on-click on-back}
      [:> icon* {:icon-id i/arrow-left :aria-hidden true}]
      [:span {:class (stl/css :label)} (tr "workspace.text-selection-menu.ruby")]]
     [:input {:type "text"
              :class (stl/css :reading-input)
              :aria-label (tr "workspace.text-selection-menu.reading")
              :aria-invalid (not valid?)
              :title (when-not valid? invalid-hint)
              :auto-focus true
              :value value
              :on-change on-change
              :on-key-down on-key-down
              :on-key-up on-key-up}]
     (when-not (str/blank? reading)
       [:button {:type "button"
                 :class (stl/css :remove-button)
                 :on-click on-remove}
        [:span {:class (stl/css :label)} (tr "workspace.text-selection-menu.remove")]])
     [:button {:type "button"
               :class (stl/css :more-button)
               :aria-label (tr "workspace.text-selection-menu.ruby-options")}
      [:> icon* {:icon-id i/menu
                 :class (stl/css :more-icon)
                 :aria-hidden true}]]]))

(mf/defc menu*
  {::mf/private true}
  [{:keys [shape-id rect vbox zoom family annotations zhuyin
           ^boolean readable ^boolean ruby-enabled ^boolean warichu-enabled]}]
  (let [view*   (mf/use-state :toolbar)
        view    (deref view*)

        ;; Centred over the selection box, on top of it.
        left    (* (- (+ (:x rect) (/ (:width rect) 2)) (:x vbox)) zoom)
        top     (* (- (:y rect) (:y vbox)) zoom)
        style   (mf/with-memo [left top]
                  #js {:left (dm/str left "px")
                       :top  (dm/str top "px")})

        open-ruby
        (mf/use-fn #(reset! view* :ruby))

        close-ruby
        (mf/use-fn
         (fn []
           (reset! view* :toolbar)
           (focus-editor)))

        apply-ruby
        (mf/use-fn
         (mf/deps shape-id close-ruby)
         (fn [reading]
           (apply-text-attrs shape-id {:ruby reading})
           (close-ruby)))]

    [:div {:class (stl/css-case :selection-menu true
                                :ruby-view (= view :ruby))
           :style style
           :role "toolbar"
           :aria-label (tr "workspace.text-selection-menu.title")
           ;; Focus moving into the menu keeps the editor alive (see
           ;; `keep-editing-on-blur?` in the v3 editor).
           :data-keep-editing-on-blur true
           :on-mouse-down keep-editor-focus}
     (case view
       :ruby [:> ruby-input* {:reading (:ruby annotations)
                              :validate (when zhuyin cjk/zhuyin-reading?)
                              :invalid-hint (tr "workspace.options.text-options.zhuyin-invalid")
                              :on-apply apply-ruby
                              :on-back close-ruby}]
       [:> toolbar* {:shape-id shape-id
                     :family family
                     :annotations annotations
                     :readable readable
                     :ruby-enabled ruby-enabled
                     :warichu-enabled warichu-enabled
                     :on-ruby open-ruby}])]))

(mf/defc selection-menu*
  "Shows the menu over the text selected in the editor of `shape` when the
  text renders with a Japanese or Chinese layout."
  [{:keys [shape vbox zoom]}]
  (let [shape-id      (:id shape)
        rects         (mf/deref refs/workspace-wasm-editor-selection-rects)
        styles-map    (mf/deref refs/workspace-wasm-editor-styles)
        file-language (mf/deref refs/file-text-language)
        rect          (get rects shape-id)
        family        (cjk/language-family
                       (or (cjk/content-language (:content shape)) file-language))]
    (when (and (some? rect) (contains? menu-language-families family))
      (let [styles      (get styles-map shape-id)
            emphasis    (get styles :text-emphasis)
            annotations {:ruby     (span-value styles :ruby)
                         ;; Spans without a mark store none.
                         :emphasis (cond
                                     (nil? emphasis)        "none"
                                     (= :multiple emphasis) nil
                                     :else                  emphasis)
                         :warichu  (span-value styles :warichu)
                         :combine  (span-value styles :text-combine-upright)}
            content     (wasm.text-editor/get-cached-content shape-id)
            selection   (wasm.text-editor/text-editor-get-selection)
            ;; A reading joins the spans it covers into one base; a warichu
            ;; note stays in one span.
            readable    (wasm.text-editor/reading-selection? content selection)
            ;; Zhuyin annotates one Han character at a time.
            zhuyin      (and (= family :chinese) (true? (:ruby-zhuyin styles)))]
        [:> menu* {:shape-id shape-id
                   :rect rect
                   :vbox vbox
                   :zoom zoom
                   :family family
                   :annotations annotations
                   :zhuyin zhuyin
                   :readable readable
                   :ruby-enabled (and readable
                                      (or (not zhuyin)
                                          (wasm.text-editor/zhuyin-selection? content selection)))
                   :warichu-enabled (wasm.text-editor/single-span-selection? content selection)}]))))
