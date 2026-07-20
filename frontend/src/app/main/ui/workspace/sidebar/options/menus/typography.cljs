;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require-macros [app.main.style :as stl])
  (:require
   ["react-virtualized" :as rvt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.types.text :as txt]
   [app.config :as cf]
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

;; --- FEATURE: font preview (flag :font-preview) ------------------------------
;; font-item-preview* and use-font-lazy-load are the whole feature. They are only
;; rendered/called behind the `:font-preview` flag check in font-item* below, so
;; their hooks never run when the flag is off. To remove the flag, inline
;; font-item-preview* into font-item* and drop the plain-name branch.

(mf/defc font-item-preview*
  "Row content with previews: a vector preview from the shared sprite for catalog
  fonts, or the font's own name lazily loaded for custom fonts the sprite doesn't
  cover."
  {::mf/wrap [mf/memo]}
  [{:keys [font]}]
  (let [font-id    (:id font)
        sprite     (mf/deref fonts/preview-sprite)
        in-sprite? (contains? (:ids sprite) font-id)

        ;; Fallback is ONLY for custom fonts: ones the (ready) sprite doesn't
        ;; cover. If the sprite isn't ready (loading/error) we show the plain name
        ;; rather than runtime-loading the whole catalog.
        fallback?  (and (= :ready (:status sprite))
                        (not in-sprite?))
        loaded?    (use-font-lazy-load font-id fallback?)]
    (if in-sprite?
      ;; `fill: currentColor` (scss) makes the sprite glyph follow the row color.
      [:svg {:class (stl/css :font-item-preview)
             :role "img"
             :aria-label (:name font)}
       [:use {:href (dm/str "#" fonts/preview-sprite-prefix font-id)}]]
      [:span {:class (stl/css :font-item-label)
              :style (when loaded?
                       #js {:fontFamily (dm/str "\"" (:family font) "\", sans-serif")})}
       (:name font)])))

(mf/defc font-item*
  {::mf/wrap [mf/memo]}
  [{:keys [font is-current on-click style]}]
  (let [item-ref (mf/use-ref)
        on-click (mf/use-fn (mf/deps font) #(on-click font))
        ;; FLAG :font-preview — gates the feature markup AND its row styling
        ;; (.font-item-preview-on in the scss). Remove this and its two uses below.
        preview? (contains? cf/flags :font-preview)]

    (mf/use-effect
     (mf/deps is-current)
     (fn []
       (when is-current
         (let [element (mf/ref-val item-ref)]
           (when-not (dom/is-in-viewport? element)
             (dom/scroll-into-view! element))))))

    [:div {:class (stl/css :font-wrapper)
           :style style
           :ref item-ref
           :on-click on-click}
     [:div {:class  (stl/css-case :font-item true
                                  :font-item-preview-on preview?
                                  :selected is-current)}
      (if preview?
        [:> font-item-preview* {:font font}]
        [:span {:class (stl/css :font-item-label)} (:name font)])
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

        fonts        (mf/deref fonts/fonts)
        fonts        (mf/with-memo [state fonts]
                       (filter-fonts state fonts))

        sprite-status (:status (mf/deref fonts/preview-sprite))

        recent-fonts (mf/deref refs/recent-fonts)
        recent-fonts (mf/with-memo [state recent-fonts]
                       (filter-fonts state recent-fonts))


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
         (mf/deps fonts)
         (fn [event]
           (cond
             (kbd/up-arrow? event)   (select-prev event)
             (kbd/down-arrow? event) (select-next event)
             (kbd/esc? event)        (on-close)
             (kbd/enter? event)      (on-close)
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

    (mf/with-effect [fonts]
      (let [key (events/listen js/document "keydown" on-key-down)]
        #(events/unlistenByKey key)))

    ;; FLAG :font-preview — materialize the preview sprite into the DOM only while
    ;; the picker is open (markup is prefetched on workspace load), removing it on
    ;; close so its ~2000 nodes aren't kept around idle. Remove the flag clause to
    ;; drop the feature.
    (mf/with-effect [sprite-status]
      (when (and (contains? cf/flags :font-preview)
                 (= :ready sprite-status))
        (let [node (fonts/attach-preview-sprite!)]
          #(fonts/detach-preview-sprite! node))))

    (mf/with-effect [@selected]
      (when-let [inst (mf/ref-val flist)]
        (when-let [index (:index @selected)]
          (.scrollToRow ^js inst index))))

    (mf/with-effect [@selected]
      (on-select @selected))

    (mf/with-effect []
      (st/emit! (dsc/push-shortcuts :typography {}))
      (fn []
        (st/emit! (dsc/pop-shortcuts :typography))))

    (mf/with-effect []
      (let [index  (d/index-of-pred fonts #(= (:id %) (:id current-font)))
            inst   (mf/ref-val flist)]
        (tm/schedule
         #(let [offset (.getOffsetForRow ^js inst #js {:alignment "center" :index index})]
            (.scrollToPosition ^js inst offset)))))

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
                            :is-current (= (:id font) (:id @selected))}])])]

      [:div {:class (stl/css-case :fonts-list true
                                  :fonts-list-full-size full-size?)}
       [:> rvt/AutoSizer {}
        (fn [props]
          (let [width  (unchecked-get props "width")
                height (unchecked-get props "height")
                render #(row-renderer fonts @selected on-select-and-close %)]
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
             (on-change {:font-id new-font-id
                         :font-family family
                         :font-variant-id (or id name)
                         :font-weight weight
                         :font-style style})
             (mf/set-ref-val! last-font font))))

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
                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

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

                  :style {:font-family (:font-family typography)
                          :font-weight (:font-weight typography)
                          :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.sample")]

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
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

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
           :style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (tr "workspace.assets.typography.sample")]

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
