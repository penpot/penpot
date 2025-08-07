;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require-macros [app.main.style :as stl])
  (:require
   ["react-virtualized" :as rvt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.text :as txt]
   [app.main.constants :refer [max-input-length]]
   [app.main.data.common :as dcm]
   [app.main.data.fonts :as fts]
   [app.main.data.shortcuts :as dsc]
   [app.main.features :as features]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.strings :as ust]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [goog.events :as events]
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

(mf/defc font-item*
  {::mf/wrap [mf/memo]}
  [{:keys [font is-current on-click style]}]
  (let [item-ref (mf/use-ref)
        on-click (mf/use-fn (mf/deps font) #(on-click font))]

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
                                  :selected is-current)}
      [:span {:class (stl/css :label)} (:name font)]
      [:span {:class (stl/css :icon)} (when is-current i/tick)]]]))

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

    [:div {:class (stl/css :font-selector)}
     [:div {:class (stl/css-case :font-selector-dropdown true :font-selector-dropdown-full-size full-size?)}
      [:div {:class (stl/css :header)}
       [:& search-bar {:on-change on-filter-change
                       :value (:term state)
                       :auto-focus true
                       :placeholder (tr "workspace.options.search-font")}]
       (when (and recent-fonts show-recent)
         [:section {:class (stl/css :show-recent)}
          [:p {:class (stl/css :title)} (tr "workspace.options.recent-fonts")]
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

(mf/defc font-options
  {::mf/wrap-props false}
  [{:keys [values on-change on-blur show-recent full-size-selector]}]
  (let [{:keys [font-id font-size font-variant-id]} values

        font-id         (or font-id (:font-id txt/default-text-attrs))
        font-size       (or font-size (:font-size txt/default-text-attrs))
        font-variant-id (or font-variant-id (:font-variant-id txt/default-text-attrs))

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
             (on-change {:font-id (:id font)
                         :font-family (:family font)
                         :font-variant-id new-variant-id
                         :font-weight (:weight variant)
                         :font-style (:style variant)})
             (dom/blur! (dom/get-target new-variant-id)))))

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
         :show-recent show-recent}])

     [:div {:class (stl/css :font-option)
            :title (tr "inspect.attributes.typography.font-family")
            :on-click #(reset! open-selector? true)}
      (cond
        (= :multiple font-id)
        "--"

        (some? font)
        [:*
         [:span {:class (stl/css :name)}
          (:name font)]
         [:span {:class (stl/css :icon)}
          i/arrow]]

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
             variant-options (if (= font-size :multiple)
                               (conj basic-variant-options
                                     {:value :multiple
                                      :key :multiple-variants
                                      :label "--"})
                               basic-variant-options)]
         ;;  TODO Add disabled mode
         [:& select
          {:class (stl/css :font-variant-select)
           :default-value (attr->string font-variant-id)
           :options variant-options
           :on-change on-font-variant-change
           :on-blur on-blur}])]]]))

(mf/defc spacing-options
  {::mf/wrap-props false}
  [{:keys [values on-change on-blur]}]
  (let [{:keys [line-height
                letter-spacing]} values
        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")
        handle-change
        (fn [value attr]
          (on-change {attr (str value)}))]

    [:div {:class (stl/css :spacing-options)}
     [:div {:class (stl/css :line-height)
            :title (tr "inspect.attributes.typography.line-height")}
      [:span {:class (stl/css :icon)
              :alt (tr "workspace.options.text-options.line-height")}
       i/text-lineheight]
      [:> numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "1.2"
        :class (stl/css :line-height-input)
        :value (attr->string line-height)
        :placeholder (if (= :multiple line-height) (tr "settings.multiple") "--")
        :nillable (= :multiple line-height)
        :on-change #(handle-change % :line-height)
        :on-blur on-blur}]]

     [:div {:class (stl/css :letter-spacing)
            :title (tr "inspect.attributes.typography.letter-spacing")}
      [:span
       {:class (stl/css :icon)
        :alt (tr "workspace.options.text-options.letter-spacing")}
       i/text-letterspacing]
      [:> numeric-input*
       {:min -200
        :max 200
        :step 0.1
        :default-value "0"
        :class (stl/css :letter-spacing-input)
        :value (attr->string letter-spacing)
        :placeholder (if (= :multiple letter-spacing) (tr "settings.multiple") "--")
        :on-change #(handle-change % :letter-spacing)
        :nillable (= :multiple letter-spacing)
        :on-blur on-blur}]]]))

(mf/defc text-transform-options
  {::mf/wrap-props false}
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

(mf/defc text-options
  {::mf/wrap-props false}
  [{:keys [ids editor values on-change on-blur show-recent]}]
  (let [full-size-selector? (and show-recent (= (mf/use-ctx ctx/sidebar) :right))
        opts #js {:editor editor
                  :ids ids
                  :values values
                  :on-change on-change
                  :on-blur on-blur
                  :show-recent show-recent
                  :full-size-selector full-size-selector?}]
    [:div {:class (stl/css-case :text-options true
                                :text-options-full-size full-size-selector?)}
     [:> font-options opts]
     [:div {:class (stl/css :typography-variations)}
      [:> spacing-options opts]
      [:> text-transform-options opts]]]))

(mf/defc typography-advanced-options
  {::mf/wrap [mf/memo]}
  [{:keys [visible? typography editable? name-input-ref on-close on-change on-name-blur local? navigate-to-library on-key-down]}]
  (let [ref       (mf/use-ref nil)
        font-data (fonts/get-font-data (:font-id typography))]
    (fonts/ensure-loaded! (:font-id typography))

    (mf/use-effect
     (mf/deps visible?)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when visible?
           (dom/scroll-into-view-if-needed! node)))))

    (when visible?
      [:div {:ref ref
             :class (stl/css :advanced-options-wrapper)}

       (if ^boolean editable?
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

           [:div {:class (stl/css :action-btn)
                  :on-click on-close}
            i/tick]]

          [:& text-options {:values typography
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
           [:div {:class (stl/css :action-btn)
                  :on-click on-close}
            i/menu]]

          [:div {:class (stl/css :info-row)}
           [:span {:class (stl/css :info-label)}  (tr "workspace.assets.typography.font-variant-id")]
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

          (when-not local?
            [:a {:class (stl/css :link-btn)
                 :on-click navigate-to-library}
             (tr "workspace.assets.typography.go-to-edit")])])])))

(mf/defc typography-entry
  {::mf/wrap-props false}
  [{:keys [file-id typography local? selected? on-click on-change on-detach on-context-menu editing? renaming? focus-name? external-open*]}]
  (let [name-input-ref       (mf/use-ref)
        read-only?           (mf/use-ctx ctx/workspace-read-only?)
        editable?            (and local? (not read-only?))

        open*                (mf/use-state editing?)
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

    (mf/with-effect [editing?]
      (when editing?
        (reset! open* editing?)))

    (mf/with-effect [open?]
      (when (some? external-open*)
        (reset! external-open* open?)))

    (mf/with-effect [focus-name?]
      (when focus-name?
        (tm/schedule
         #(when-let [node (mf/ref-val name-input-ref)]
            (dom/focus! node)
            (dom/select-text! node)))))

    [:*
     [:div {:class (stl/css-case :typography-entry true
                                 :selected ^boolean selected?)
            :style {:display (when ^boolean open? "none")}}
      (if renaming?
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

         [:div {:class (stl/css :typography-name)
                :title (:name typography)} (:name typography)]

         (when-not name-only?
           [:div {:class (stl/css :typography-font)
                  :title (:name font-data)}
            (:name font-data)])])
      [:div {:class (stl/css :element-set-actions)}
       (when ^boolean on-detach
         [:button {:class (stl/css :element-set-actions-button)
                   :on-click on-detach}
          i/detach])
       [:button {:class (stl/css :menu-btn)
                 :on-click on-open}
        i/menu]]]

     [:& typography-advanced-options
      {:visible? open?
       :on-close on-close
       :typography  typography
       :editable? editable?
       :name-input-ref  name-input-ref
       :on-change  on-change
       :on-name-blur on-name-blur
       :on-key-down on-key-down
       :local?  local?
       :navigate-to-library navigate-to-library}]]))
