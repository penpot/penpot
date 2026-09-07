;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require-macros [app.main.style :as stl])
  (:require
   ["react-virtualized" :as rvt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.types.text :as txt]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.common :as dcm]
   [app.main.data.fonts :as fts]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.strings :as ust]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [goog.events :as events]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(defn- attr->string [value]
  (if (= value :multiple)
    ""
    (ust/format-precision value 2)))

(defn- get-next-font
  [{:keys [id] :as current} fonts]
  (if (seq fonts)
    (let [index (d/index-of-pred fonts #(= (:id %) id))
          index (or index -1)
          next  (ex/ignoring (nth fonts (inc index)))]
      (or next (first fonts)))
    current))

(defn- get-prev-font
  [{:keys [id] :as current} fonts]
  (if (seq fonts)
    (let [index (d/index-of-pred fonts #(= (:id %) id))
          next  (ex/ignoring (nth fonts (dec index)))]
      (or next (peek fonts)))
    current))

(defn- use-font-lazy-load
  "Lazily loads `font-id` for the fallback preview when `fallback?`, on idle so
  fast scrolling over recycled virtualized rows doesn't storm requests (cancelled
  if the row is reused first). Returns whether the font face is loaded yet."
  [font-id fallback?]
  (let [loaded? (mf/use-state #(and fallback? (contains? @fonts/loaded font-id)))]
    (mf/use-effect
     (mf/deps font-id fallback?)
     (fn []
       (let [already? (and fallback? (contains? @fonts/loaded font-id))]
         (reset! loaded? already?)
         (if (and fallback? (not already?))
           (let [cancelled? (volatile! false)
                 task (tm/schedule-on-idle
                       (fn []
                         (-> (fonts/ensure-loaded! font-id)
                             (p/then (fn [_]
                                       (when-not @cancelled?
                                         (reset! loaded? true))))
                             (p/catch (fn [_] nil)))))]
             (fn []
               (vreset! cancelled? true)
               (tm/dispose! task)))
           (constantly nil)))))
    @loaded?))

;; --- OPTICAL CENTERING OF SAMPLE TEXT --------------------------------------

;; Fonts with exaggerated vertical metrics (huge ascender/descender, small
;; caps) render their line box lower within a fixed-height row, so a plain
;; `align-items: center` leaves the visible glyphs sitting low. We measure the
;; font-wide vs glyph-ink bounding boxes once per font/sample and shift the
;; text by the computed offset so the visible glyphs are optically centered.
;; The offset is expressed in `em`, which makes it size-independent: the same
;; measurement corrects both the 16px `Ag` sample and the smaller font-name
;; labels in the font selector.

(defonce ^:private optical-offset-cache (atom {}))

(defn- optical-offset-key [family weight style text]
  (dm/str family "|" weight "|" style "|" text))

(defn- optical-offset-em
  "Vertical shift (in `em` units, i.e. relative to the font size) that centers
  the ink of `text` within a single line box.

  For a centered line the shift reduces to the difference between the font-wide
  and ink bounding boxes:
  dy = ((ink-ascent - font-ascent) + (font-descent - ink-descent)) / 2.
  Measuring at 16px and dividing the pixel shift by it yields the `em` value."
  [family weight style text]
  (when-some [{:keys [font-ascent font-descent ink-ascent ink-descent]}
              (dom/measure-text-metrics family weight style text 16)]
    (let [dy (/ (+ (- ink-ascent font-ascent)
                   (- font-descent ink-descent))
                2)
          em (/ dy 16)]
      ;; Round to avoid float noise leaking into the transform string.
      (/ (js/Math.round (* em 10000)) 10000))))

(defn- load-optical-offset
  [font-id family weight style text]
  (let [key (optical-offset-key family weight style text)]
    (if-let [cached (get @optical-offset-cache key)]
      (p/resolved cached)
      (-> (fonts/ensure-loaded! font-id)
          (p/then
           (fn [_]
             (let [em (or (optical-offset-em family weight style text) 0)]
               (swap! optical-offset-cache assoc key em)
               em)))))))

(defn- use-optical-offset
  "Lazily resolve the optical-centering offset (in `em`) for sample text in a
  given font, measuring once per font/sample and caching it. Falls back to 0
  when the font isn't available or the metrics can't be measured."
  [font-id family weight style text]
  (let [offset* (mf/use-state 0)]
    (mf/use-effect
     (mf/deps font-id family weight style text)
     (fn []
       (let [cancelled? (volatile! false)
             key        (optical-offset-key family weight style text)]
         (if (contains? @optical-offset-cache key)
           (reset! offset* (get @optical-offset-cache key))
           (let [task (tm/schedule-on-idle
                       (fn []
                         (-> (load-optical-offset font-id family weight style text)
                             (p/then
                              (fn [em]
                                (when-not @cancelled?
                                  (reset! offset* em)))))))]
             (fn []
               (vreset! cancelled? true)
               (tm/dispose! task)))))
       nil))
    (deref offset*)))

(defn- sample-container-style
  "Inline style that applies the typography font to the (clipped, fixed-height)
  sample container. Must be a real JS object (`#js`), not a ClojureScript map:
  the `:style` value here is a runtime expression, not a literal recognized by
  the hiccup macro, so it reaches React unconverted."
  [typography]
  #js {:fontFamily (:font-family typography)
       :fontWeight (:font-weight typography)
       :fontStyle  (:font-style typography)})

(defn- sample-text-style
  "Inline style that optically centers the sample glyphs. Must be applied to
  the text node itself, not to the clipped container: a transform on an
  `overflow: hidden` element moves its own clip region along with it, so it
  would shift the whole box relative to the row instead of the glyphs inside it."
  [em]
  (when-not (zero? em)
    #js {:transform (dm/str "translateY(" em "em)")}))

;; --- FONT SELECTOR --------------------------------------------------------

(mf/defc font-item-preview*
  "Row content with previews: a vector preview from the shared sprite for catalog
  fonts, or the font's own name lazily loaded for custom fonts the sprite doesn't
  cover."
  {::mf/wrap [mf/memo]}
  [{:keys [font]}]
  (let [font-id    (get font :id)
        sprite     (mf/deref fonts/preview-sprite)

        ;; The sprite is only referenceable once it's been attached to the DOM,
        ;; so the `<use>` glyph is gated on `attached?`. Until then we show the
        ;; plain name: no blank rows, and no per-font load storm either (see
        ;; `fallback?` below).
        attached?  (pos? (:refs sprite))

        ;; Fallback is ONLY for custom fonts: ones the (attached) sprite doesn't
        ;; cover. If the sprite isn't ready (loading/error) or not yet attached,
        ;; we show the plain name rather than runtime-loading the whole catalog.
        in-sprite? (and attached? (contains? (:ids sprite) font-id))
        fallback?  (and (= :ready (:status sprite)) attached? (not in-sprite?))
        loaded?    (use-font-lazy-load font-id fallback?)

        ;; Optical centering for the fallback name (custom fonts the sprite
        ;; doesn't cover): extreme vertical metrics would push the name low in
        ;; the row, so shift it by the measured offset once the font is known.
        ;; The label renders at `body-medium` (400/normal), which is the weight
        ;; and style we measure against.
        label-offset (use-optical-offset font-id
                                         (:family font)
                                         "400"
                                         "normal"
                                         (:name font))]
    (if in-sprite?
      ;; `fill: currentColor` (scss) makes the sprite glyph follow the row color.
      [:svg {:class (stl/css :font-item-preview)
             :role "img"
             :aria-label (:name font)}
       [:use {:href (dm/str "#" fonts/preview-sprite-prefix font-id)}]]
      ;; The vertical correction goes on an INNER span, not on `.font-item-label`
      ;; itself: that class carries its own `overflow: hidden` (from the
      ;; text-ellipsis mixin, needed to truncate long font names), and a
      ;; transform applied to a self-clipping element moves its clip region
      ;; along with it — a no-op. The inner span has no overflow of its own, so
      ;; the shift actually moves the ink within the outer's fixed clip area.
      [:span {:class (stl/css :font-item-label)}
       [:span {:style #js {:fontFamily (when loaded?
                                         (dm/str "\"" (:family font) "\", sans-serif"))
                           :transform  (when-not (zero? label-offset)
                                         (dm/str "translateY(" label-offset "em)"))}}
        (:name font)]])))

(mf/defc font-item*
  {::mf/wrap [mf/memo]}
  [{:keys [font is-current on-click style]}]
  (let [item-ref (mf/use-ref)
        on-click (mf/use-fn (mf/deps font) #(on-click font))]

    (mf/with-effect [is-current]
      (when is-current
        (let [element (mf/ref-val item-ref)]
          (when-not (dom/is-in-viewport? element)
            (dom/scroll-into-view! element)))))

    [:div {:class (stl/css :font-wrapper)
           :style style
           :ref item-ref
           :on-click on-click}
     [:div {:class  (stl/css-case :font-item true :selected is-current)}
      [:> font-item-preview* {:font font}]
      (when is-current
        [:> icon* {:icon-id i/tick
                   :size "s"}])]]))

(declare row-renderer)

(defn filter-fonts
  [{:keys [term backends]} fonts]
  (let [term (str/lower term)
        xform (cond-> (map identity)
                (seq term)
                (comp (filter #(str/includes? (str/lower (:name %)) term)))

                (seq backends)
                (comp (filter #(contains? backends (:backend %)))))]
    (into [] xform fonts)))

(mf/defc font-selector*
  [{:keys [on-select on-close current-font show-recent full-size]}]
  (let [selected     (mf/use-state current-font)
        state*       (mf/use-state
                      #(do {:term "" :backends #{}}))
        state        (deref state*)

        flist        (mf/use-ref)
        input        (mf/use-ref)

        all-fonts    (mf/deref fonts/fonts)
        fonts        (mf/with-memo [state all-fonts]
                       (filter-fonts state all-fonts))

        ;; Ids currently installed in fontsdb. Recent fonts that are no longer
        ;; available (deleted, or belonging to another team) must be hidden:
        ;; selecting one applies a missing/nil font-family and corrupts the
        ;; text content (fails the backend `validate-shape` schema).
        installed-ids (mf/with-memo [all-fonts]
                        (into #{} (map :id) all-fonts))

        sprite-status (:status (mf/deref fonts/preview-sprite))

        recent-fonts (mf/deref refs/recent-fonts)
        recent-fonts (mf/with-memo [state recent-fonts installed-ids]
                       (->> recent-fonts
                            (filter #(contains? installed-ids (:id %)))
                            (filter-fonts state)))

        ;; When the active font is not in the filtered results, pre-select
        ;; the first match visually so the user can confirm it with Enter —
        ;; without live-applying it on every keystroke.
        effective-selected
        (if (and (seq fonts) (not (d/seek #(= (:id %) (:id @selected)) fonts)))
          (first fonts)
          @selected)

        full-size?   (boolean (and full-size show-recent))

        select-next
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-next-font fonts)))

        select-prev
        (mf/use-fn
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-prev-font fonts)))

        on-key-down
        (mf/use-fn
         (mf/deps fonts on-select on-close)
         (fn [event]
           (cond
             (kbd/up-arrow? event)   (select-prev event)
             (kbd/down-arrow? event) (select-next event)
             (kbd/esc? event)        (on-close)
             (kbd/enter? event)
             (let [first-result (when-not (d/seek #(= (:id %) (:id @selected)) fonts)
                                  (first fonts))]
               (if first-result
                 (do (on-select first-result) (on-close))
                 (on-close)))
             :else                   (dom/focus! (mf/ref-val input)))))

        on-filter-change
        (mf/use-fn
         (fn [event]
           (swap! state* assoc :term event)))

        on-select-and-close
        (mf/use-fn
         (mf/deps on-select on-close)
         (fn [font]
           (on-select font)
           (on-close)))]

    (mf/with-effect [fonts on-key-down]
      (let [key (events/listen js/document "keydown" on-key-down)]
        #(events/unlistenByKey key)))

    ;; Materialize the preview sprite into the DOM only while the picker is open
    ;; (markup is prefetched on workspace load), removing it on close so its
    ;; ~2000 nodes aren't kept around idle. The attachment is deferred so the
    ;; dropdown can paint first with plain names, then the sprite swaps in on the
    ;; next tick.
    (mf/with-effect [sprite-status]
      (when (= :ready sprite-status)
        (let [node*  (volatile! nil)
              task   (tm/schedule
                      (fn []
                        (vreset! node* (fonts/attach-preview-sprite!))))]
          (fn []
            (tm/dispose! task)
            (when-some [n @node*]
              (fonts/detach-preview-sprite! n))))))

    (mf/with-effect [@selected]
      (let [node  (mf/ref-val flist)
            index (:index @selected)]
        ;; This is nil safe operation, do nothing if node or index are
        ;; invalid.
        (dom/scroll-to-row node index)))

    (mf/with-effect [@selected]
      (on-select @selected))

    (mf/with-effect []
      (st/emit! (dsc/push-shortcuts :typography {} :workspace))
      (fn []
        (st/emit! (dsc/pop-shortcuts :typography))))

    (mf/with-effect []
      (let [index (d/index-of-pred fonts #(= (:id %) (:id current-font)))
            node  (mf/ref-val flist)]
        (tm/schedule
         #(let [offset (.getOffsetForRow ^js node #js {:alignment "center" :index index})]
            ;; Safe operaton, do nothing if node or offset has invalid values
            (dom/scroll-to-position node offset)))))

    [:div {:class [(stl/css-case :font-selector true
                                 :fonts-on-modal (not full-size?))]}
     [:div {:class (stl/css-case :font-selector-dropdown true
                                 :font-selector-dropdown-full-size full-size?)}
      [:div {:class (stl/css :header)}
       [:> search-bar* {:on-change on-filter-change
                        :value (:term state)
                        :auto-focus true
                        :placeholder (tr "workspace.options.search-font")}]
       (when (and recent-fonts show-recent)
         [:section {:class (stl/css :show-recent)}
          [:p {:class (stl/css :header-title)} (tr "workspace.options.recent-fonts")]
          (for [[idx font] (d/enumerate recent-fonts)]
            [:> font-item* {:key (dm/str "font-" idx)
                            :font font
                            :style {}
                            :on-click on-select-and-close
                            :is-current (= (:id font) (:id effective-selected))}])])]

      [:div {:class (stl/css-case :fonts-list true
                                  :fonts-list-full-size full-size?)}
       [:> rvt/AutoSizer {}
        (fn [props]
          (let [width  (unchecked-get props "width")
                height (unchecked-get props "height")
                render #(row-renderer fonts effective-selected on-select-and-close %)]
            (mf/html
             [:> rvt/List #js {:height height
                               :ref flist
                               :width width
                               :rowCount (count fonts)
                               :rowHeight 36
                               :rowRenderer render}])))]]]]))

(defn row-renderer
  [fonts selected on-select props]
  (let [index (unchecked-get props "index")
        key   (unchecked-get props "key")
        style (unchecked-get props "style")
        font  (nth fonts index)]
    (mf/html
     [:> font-item* {:key key
                     :font font
                     :style style
                     :on-click on-select
                     :is-current (= (:id font) (:id selected))}])))

(mf/defc font-options*
  [{:keys [values on-change on-blur show-recent full-size-selector]}]
  (let [{:keys [font-id font-size font-variant-id]} values

        font-id         (or font-id (:font-id txt/default-typography))
        font-size       (or font-size (:font-size txt/default-typography))
        font-variant-id (or font-variant-id (:font-variant-id txt/default-typography))

        fonts           (mf/deref fonts/fontsdb)
        font            (get fonts font-id)

        last-font       (mf/use-ref nil)

        open-selector?  (mf/use-state false)

        change-font
        (mf/use-fn
         (mf/deps on-change fonts)
         (fn [new-font-id]
           (let [{:keys [family] :as font} (get fonts new-font-id)
                 {:keys [id name weight style]} (fonts/get-default-variant font)]
             ;; Guard against a font that is not present in fontsdb (unloaded
             ;; custom font, deleted font, shared library not yet resolved).
             ;; Without it `family`/`weight`/`style` come back nil and get written
             ;; onto the text spans, producing content that fails the backend
             ;; `validate-shape` schema (`:font-family`/`:font-weight`/`:font-style`
             ;; must be non-blank strings when present).
             (when (and (some? font) (some? family))
               (on-change {:font-id new-font-id
                           :font-family family
                           :font-variant-id (or id name)
                           :font-weight weight
                           :font-style style})
               (mf/set-ref-val! last-font font)))))

        on-font-size-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [new-font-size]
           (when-not (str/empty? new-font-size)
             (on-change {:font-size (str new-font-size)}))))

        on-font-variant-change
        (mf/use-fn
         (mf/deps font on-change)
         (fn [new-variant-id]
           (let [variant (d/seek #(= new-variant-id (:id %)) (:variants font))]
             (when-not (nil? variant)
               (on-change {:font-id (:id font)
                           :font-family (:family font)
                           :font-variant-id new-variant-id
                           :font-weight (:weight variant)
                           :font-style (:style variant)}))
             ;; NOTE: the select component we are using does not fire on-blur event
             ;; so we need to call on-blur manually
             (when (some? on-blur)
               (on-blur)))))

        on-font-select
        (mf/use-fn
         (mf/deps change-font)
         (fn [font*]
           (when (not= font font*)
             (change-font (:id font*)))

           (when (some? on-blur)
             (on-blur))))

        on-font-selector-close
        (mf/use-fn
         (fn []
           (reset! open-selector? false)
           (when (some? on-blur)
             (on-blur))
           (when (mf/ref-val last-font)
             (st/emit! (fts/add-recent-font (mf/ref-val last-font))))))]

    [:*
     (when @open-selector?
       [:> font-selector*
        {:current-font font
         :on-close on-font-selector-close
         :on-select on-font-select
         :full-size full-size-selector
         :origin "right-sidebar"
         :show-recent show-recent}])

     [:div {:class (stl/css :font-option)
            :title (tr "inspect.attributes.typography.font-family")
            :on-click #(reset! open-selector? true)}
      (cond
        (or (= :multiple font-id) (= "mixed" font-id))
        [:*
         [:span {:class (stl/css :font-option-name :font-family-mixed)}
          (tr "inspect.attributes.typography.mixed-font-family")]
         [:> icon* {:icon-id i/arrow-down
                    :class (stl/css :dropdown-icon)
                    :size "s"}]]

        (some? font)
        [:*
         [:span {:class (stl/css :font-option-name)}
          (:name font)]
         [:> icon* {:icon-id i/arrow-down
                    :class (stl/css :dropdown-icon)
                    :size "s"}]]

        :else
        (tr "dashboard.fonts.deleted-placeholder"))]

     [:div {:class (stl/css :font-modifiers)}
      [:div {:class (stl/css :font-size-options)
             :title (tr "inspect.attributes.typography.font-size")}
       (let [size-options [8 9 10 11 12 14 16 18 24 36 48 72]
             size-options (if (= font-size :multiple) (into [""] size-options) size-options)]
         [:& editable-select
          {:value (if (= font-size :multiple) :multiple (attr->string font-size))
           :class (stl/css :font-size-select)
           :aria-label (tr "inspect.attributes.typography.font-size")
           :input-class (stl/css :numeric-input)
           :options size-options
           :type "number"
           :placeholder (tr "settings.multiple")
           :min 3
           :max 1000
           :on-change on-font-size-change
           :on-blur on-blur}])]

      [:div {:class (stl/css :font-variant-options)
             :title (tr "inspect.attributes.typography.font-style")}
       (let [basic-variant-options (->> (:variants font)
                                        (map (fn [variant]
                                               {:value (:id variant)
                                                :key (pr-str variant)
                                                :label (:name variant)})))
             ;; When the selection mixes variants we prepend a "--" entry: it is
             ;; shown as the collapsed value (nothing single is selected) while
             ;; the real variants of the resolved font are still listed below it.
             variant-options (if (or (= font-variant-id :multiple) (= font-variant-id "mixed"))
                               (conj basic-variant-options
                                     {:value ""
                                      :key :multiple-variants
                                      :label "--"})
                               basic-variant-options)
             font-variant-value (attr->string font-variant-id)
             font-variant-value (if (= font-variant-value "mixed") "" font-variant-value)]

         ;;  TODO Add disabled mode
         [:& select
          {:class (stl/css :font-variant-select)
           :default-value font-variant-value
           :options variant-options
           :on-change on-font-variant-change
           :on-blur on-blur}])]]]))

(mf/defc spacing-options*
  [{:keys [values on-change on-blur]}]
  (let [{:keys [line-height
                letter-spacing]} values
        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")
        handle-change
        (fn [value attr]
          (on-change {attr (ust/format-precision value 2)}))]

    [:div {:class (stl/css :spacing-options)}
     [:div {:class (stl/css :line-height)
            :title (tr "inspect.attributes.typography.line-height")}
      [:span {:class (stl/css :icon)
              :alt (tr "workspace.options.text-options.line-height")}
       deprecated-icon/text-lineheight]
      [:> deprecated-input/numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "1.2"
        :class (stl/css :line-height-input)
        :aria-label (tr "inspect.attributes.typography.line-height")
        :value (attr->string line-height)
        :placeholder (if (= :multiple line-height) (tr "settings.multiple") "--")
        :is-nillable (= :multiple line-height)
        :on-change #(handle-change % :line-height)
        :on-blur on-blur}]]

     [:div {:class (stl/css :letter-spacing)
            :title (tr "inspect.attributes.typography.letter-spacing")}
      [:span
       {:class (stl/css :icon)
        :alt (tr "workspace.options.text-options.letter-spacing")}
       deprecated-icon/text-letterspacing]
      [:> deprecated-input/numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "0"
        :class (stl/css :letter-spacing-input)
        :aria-label (tr "inspect.attributes.typography.letter-spacing")
        :value (attr->string letter-spacing)
        :placeholder (if (= :multiple letter-spacing) (tr "settings.multiple") "--")
        :on-change #(handle-change % :letter-spacing)
        :is-nillable (= :multiple letter-spacing)
        :on-blur on-blur}]]]))

(mf/defc text-transform-options*
  [{:keys [values on-change on-blur]}]
  (let [text-transform (or (:text-transform values) "none")
        unset-value    (if (features/active-feature? @st/state "text-editor/v2") "none" "unset")
        handle-change
        (fn [type]
          (if (= text-transform type)
            (on-change {:text-transform unset-value})
            (on-change {:text-transform type}))
          (when (some? on-blur) (on-blur)))]

    [:div {:class (stl/css :text-transform)}
     [:& radio-buttons {:selected text-transform
                        :on-change handle-change
                        :name "text-transform"}
      [:& radio-button {:icon i/text-uppercase
                        :type "checkbox"
                        :title (tr "inspect.attributes.typography.text-transform.uppercase")
                        :value "uppercase"
                        :id "text-transform-uppercase"}]
      [:& radio-button {:icon i/text-mixed
                        :type "checkbox"
                        :value "capitalize"
                        :title (tr "inspect.attributes.typography.text-transform.capitalize")
                        :id "text-transform-capitalize"}]
      [:& radio-button {:icon i/text-lowercase
                        :type "checkbox"
                        :title (tr "inspect.attributes.typography.text-transform.lowercase")
                        :value "lowercase"
                        :id "text-transform-lowercase"}]]]))

(mf/defc text-options*
  [{:keys [ids editor values on-change on-blur show-recent]}]
  (let [full-size-selector? (and show-recent (= (mf/use-ctx ctx/sidebar) :right))
        opts (mf/props
              {:editor editor
               :ids ids
               :values values
               :on-change on-change
               :on-blur on-blur
               :show-recent show-recent
               :full-size-selector full-size-selector?})]
    [:div {:class (stl/css-case :text-options true
                                :text-options-full-size full-size-selector?)}
     [:> font-options* opts]
     [:div {:class (stl/css :typography-variations)}
      [:> spacing-options* opts]
      [:> text-transform-options* opts]]]))

(mf/defc typography-advanced-options*
  {::mf/wrap [mf/memo]}
  [{:keys [is-visible typography is-editable name-input-ref on-close on-change on-name-blur
           is-local navigate-to-library on-key-down file-id is-asset?]}]
  (let [ref            (mf/use-ref nil)
        font-data      (fonts/get-font-data (:font-id typography))
        typography-id  (:id typography)
        show-actions?  (and is-asset? is-editable)
        offset         (use-optical-offset (:font-id typography)
                                           (:font-family typography)
                                           (:font-weight typography)
                                           (:font-style typography)
                                           "Ag")

        on-delete
        (mf/use-fn
         (mf/deps typography-id file-id on-close)
         (fn []
           (on-close)
           (let [undo-id (js/Symbol)]
             (st/emit! (dwu/start-undo-transaction undo-id)
                       (dwl/delete-typography typography-id)
                       (dwl/sync-file file-id file-id :typographies typography-id)
                       (dwu/commit-undo-transaction undo-id)))))

        on-duplicate
        (mf/use-fn
         (mf/deps file-id typography-id)
         (fn []
           (st/emit! (dwl/duplicate-typography file-id typography-id))))]
    (fonts/ensure-loaded! (:font-id typography))

    (mf/use-effect
     (mf/deps is-visible)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when is-visible
           (dom/scroll-into-view-if-needed! node)))))

    (when is-visible
      [:div {:ref ref
             :class (stl/css :advanced-options-wrapper)}

       (if ^boolean is-editable
         [:*
          [:div {:class (stl/css :font-name-wrapper)}
           [:div {:class (stl/css :typography-sample-input)
                  :style (sample-container-style typography)}
            [:span {:style (sample-text-style offset)}
             (tr "workspace.assets.typography.sample")]]

           [:input
            {:class (stl/css :adv-typography-name)
             :type "text"
             :ref name-input-ref
             :default-value (:name typography)
             :max-length max-input-length
             :on-key-down on-key-down
             :on-blur on-name-blur}]

           [:div {:class (stl/css :action-btns)}
            (when show-actions?
              [:*
               [:> icon-button* {:variant "action"
                                 :aria-label (tr "workspace.assets.duplicate")
                                 :on-click on-duplicate
                                 :icon i/clipboard}]
               [:> icon-button* {:variant "action"
                                 :aria-label (tr "workspace.assets.delete")
                                 :on-click on-delete
                                 :icon i/delete}]])
            [:> icon-button* {:variant "action"
                              :aria-label (tr "labels.close")
                              :on-click on-close
                              :icon i/tick}]]]

          [:> text-options* {:values typography
                             :on-change on-change
                             :show-recent false}]]

         [:div {:class (stl/css :typography-info-wrapper)}
          [:div {:class (stl/css :typography-name-wrapper)}
           [:div {:class (stl/css :typography-sample)
                  :style (sample-container-style typography)}
            [:span {:style (sample-text-style offset)}
             (tr "workspace.assets.typography.sample")]]

           [:div {:class (stl/css :typography-name)
                  :title (:name typography)}
            (:name typography)]
           [:span {:class (stl/css :typography-font)}
            (:name font-data)]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click on-close
                             :icon i/menu}]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-style")]
           [:span {:class (stl/css :info-content)} (:font-variant-id typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-size")]
           [:span {:class (stl/css :info-content)} (:font-size typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.line-height")]
           [:span {:class (stl/css :info-content)} (:line-height typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.letter-spacing")]
           [:span {:class (stl/css :info-content)} (:letter-spacing typography)]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.text-transform")]
           [:span {:class (stl/css :info-content)} (:text-transform typography)]]

          (when-not is-local
            [:> button* {:variant "secondary"
                         :on-click navigate-to-library}
             (tr "workspace.assets.typography.go-to-edit")])])])))

(mf/defc typography-entry*
  [{:keys [file-id typography is-local is-selected on-click on-change on-detach on-context-menu is-editing is-renaming is-focus-name external-open* is-asset?]}]
  (let [name-input-ref       (mf/use-ref)
        read-only?           (mf/use-ctx ctx/workspace-read-only?)
        editable?            (and is-local (not read-only?))

        open*                (mf/use-state is-editing)
        open?                (deref open*)
        font-data            (fonts/get-font-data (:font-id typography))
        name-only?           (= (:name typography) (:name font-data))
        offset               (use-optical-offset (:font-id typography)
                                                 (:font-family typography)
                                                 (:font-weight typography)
                                                 (:font-style typography)
                                                 "Ag")

        on-name-blur
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [name (dom/get-target-val event)]
             (when-not (str/blank? name)
               (on-change {:name name})
               (st/emit! #(update % :workspace-global dissoc :rename-typography))))))

        on-open
        (mf/use-fn #(reset! open* true))

        on-close
        (mf/use-fn #(reset! open* false))

        navigate-to-library
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (when file-id
             (st/emit! (dcm/go-to-workspace :file-id file-id)))))

        on-key-down
        (mf/use-fn
         (fn [event]
           (let [enter?     (kbd/enter? event)
                 esc?       (kbd/esc? event)
                 input-node (dom/get-target event)]
             (when ^boolean enter?
               (dom/blur! input-node))
             (when ^boolean esc?
               (dom/blur! input-node)))))]

    (mf/with-effect [is-editing]
      (when is-editing
        (reset! open* is-editing)))

    (mf/with-effect [open?]
      (when (some? external-open*)
        (reset! external-open* open?)))

    (mf/with-effect [is-focus-name]
      (when is-focus-name
        (tm/schedule
         #(when-let [node (mf/ref-val name-input-ref)]
            (dom/focus! node)
            (dom/select-text! node)))))

    [:*
     [:div {:class (stl/css-case :typography-entry true
                                 :selected ^boolean is-selected)
            :style {:display (when ^boolean open? "none")}}
      (if is-renaming
        [:div {:class (stl/css :font-name-wrapper)}
         [:div
          {:class (stl/css :typography-sample-input)
           :style (sample-container-style typography)}
          [:span {:style (sample-text-style offset)}
           (tr "workspace.assets.typography.sample")]]

         [:input
          {:class (stl/css :adv-typography-name)
           :type "text"
           :ref name-input-ref
           :default-value (:name typography)
           :max-length max-input-length
           :on-key-down on-key-down
           :on-blur on-name-blur}]]
        [:div
         {:class (stl/css-case :typography-selection-wrapper true
                               :is-selectable ^boolean on-click)
          :on-click on-click
          :on-context-menu on-context-menu}
         [:div
          {:class (stl/css :typography-sample)
           :style (sample-container-style typography)}
          [:span {:style (sample-text-style offset)}
           (tr "workspace.assets.typography.sample")]]

         [:div {:class (stl/css :name-block)
                :title (if name-only?
                         (:name typography)
                         (dm/str (:name typography) " (" (:name font-data) ")"))}
          (if name-only?
            [:span  {:class (stl/css :typography-name)} (:name typography)]
            [:*
             (:name typography)
             [:span  {:class (stl/css :typography-name :typography-font)} (:name font-data)]])]])
      [:div {:class (stl/css :element-set-actions)}
       (when ^boolean on-detach
         [:> icon-button* {:variant "action"
                           :aria-label (tr "settings.detach")
                           :on-click on-detach
                           :icon i/detach}])
       [:> icon-button* {:variant "action"
                         :aria-label (tr "labels.open")
                         :on-click on-open
                         :icon i/menu}]]]

     [:> typography-advanced-options*
      {:is-visible open?
       :on-close on-close
       :typography  typography
       :is-editable editable?
       :name-input-ref  name-input-ref
       :on-change  on-change
       :on-name-blur on-name-blur
       :on-key-down on-key-down
       :file-id file-id
       :is-asset? is-asset?
       :is-local  is-local
       :navigate-to-library navigate-to-library}]]))
