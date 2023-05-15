;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.typography
  (:require
   ["react-virtualized" :as rvt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.text :as txt]
   [app.main.data.fonts :as fts]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace :as dw]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
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

(defn select-all [event]
  (some-> event dom/get-target dom/select-text!))

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

(mf/defc font-item
  {::mf/wrap [mf/memo]}
  [{:keys [font current? on-click style]}]
  (let [item-ref (mf/use-ref)
        on-click (mf/use-callback (mf/deps font) #(on-click font))]

    (mf/use-effect
     (mf/deps current?)
     (fn []
       (when current?
         (let [element (mf/ref-val item-ref)]
           (when-not (dom/is-in-viewport? element)
             (dom/scroll-into-view! element))))))

    [:div.font-item {:ref item-ref
                     :style style
                     :class (when current? "selected")
                     :on-click on-click}
     [:span.icon (when current? i/tick)]
     [:span.label (:name font)]]))

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

;; (defn- toggle-backend
;;   [backends id]
;;   (if (contains? backends id)
;;     (disj backends id)
;;     (conj backends id)))

(mf/defc font-selector
  [{:keys [on-select on-close current-font show-recent] :as props}]
  (let [selected     (mf/use-state current-font)
        state        (mf/use-state {:term "" :backends #{}})

        flist        (mf/use-ref)
        input        (mf/use-ref)

        fonts        (mf/use-memo (mf/deps @state) #(filter-fonts @state @fonts/fonts))
        recent-fonts (mf/deref refs/workspace-recent-fonts)

        select-next
        (mf/use-callback
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-next-font fonts)))

        select-prev
        (mf/use-callback
         (mf/deps fonts)
         (fn [event]
           (dom/stop-propagation event)
           (dom/prevent-default event)
           (swap! selected get-prev-font fonts)))

        on-key-down
        (mf/use-callback
         (mf/deps fonts)
         (fn [event]
           (cond
             (kbd/up-arrow? event)   (select-prev event)
             (kbd/down-arrow? event) (select-next event)
             (kbd/esc? event)        (on-close)
             (kbd/enter? event)      (on-close)
             :else                   (dom/focus! (mf/ref-val input)))))

        on-filter-change
        (mf/use-callback
         (mf/deps)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (swap! state assoc :term value))))

        on-select-and-close
        (mf/use-callback
         (mf/deps on-select on-close)
         (fn [font]
           (on-select font)
           (on-close)))]

    (mf/use-effect
     (fn []
       (st/emit! (fts/load-recent-fonts))))

    (mf/use-effect
     (mf/deps fonts)
     (fn []
       (let [key (events/listen js/document "keydown" on-key-down)]
         #(events/unlistenByKey key))))

    (mf/use-effect
     (mf/deps @selected)
     (fn []
       (when-let [inst (mf/ref-val flist)]
         (when-let [index (:index @selected)]
           (.scrollToRow ^js inst index)))))

    (mf/use-effect
     (mf/deps @selected)
     (fn []
       (on-select @selected)))

    (mf/use-effect
     (fn []
       (st/emit! (dsc/push-shortcuts :typography {}))
       (fn []
         (st/emit! (dsc/pop-shortcuts :typography)))))

    (mf/use-effect
     (fn []
       (let [index  (d/index-of-pred fonts #(= (:id %) (:id current-font)))
             inst   (mf/ref-val flist)]
         (tm/schedule
          #(let [offset (.getOffsetForRow ^js inst #js {:alignment "center" :index index})]
             (.scrollToPosition ^js inst offset))))))

    [:div.font-selector
     [:div.font-selector-dropdown
      [:header
       [:input {:placeholder (tr "workspace.options.search-font")
                :value (:term @state)
                :ref input
                :spell-check false
                :on-change on-filter-change}]
       (when (and recent-fonts show-recent)
         [:hr]
         [*
          [:p.title (tr "workspace.options.recent-fonts")]
          (for [[idx font] (d/enumerate recent-fonts)]
            [:& font-item {:key (dm/str "font-" idx)
                           :font font
                           :style {}
                           :on-click on-select-and-close
                           :current? (= (:id font) (:id @selected))}])])

       #_[:div.options
          {:on-click #(swap! state assoc :show-options true)
           :class (when (seq (:backends @state)) "active")}
          i/picker-hsv]

       #_[:& dropdown {:show (:show-options @state false)
                       :on-close #(swap! state dissoc :show-options)}
          (let [backends (:backends @state)]
            [:div.backend-filters.dropdown {:ref ddown}
             [:div.backend-filter
              {:class (when (backends :custom) "selected")
               :on-click #(swap! state update :backends toggle-backend :custom)}
              [:div.checkbox-icon i/tick]
              [:div.backend-name (tr "labels.custom-fonts")]]
             [:div.backend-filter
              {:class (when (backends :google) "selected")
               :on-click #(swap! state update :backends toggle-backend :google)}
              [:div.checkbox-icon i/tick]
              [:div.backend-name "Google Fonts"]]])]]

      [:hr]

      [:div.fonts-list
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
                               :rowHeight 32
                               :rowRenderer render}])))]]]]))
(defn row-renderer
  [fonts selected on-select props]
  (let [index (unchecked-get props "index")
        key   (unchecked-get props "key")
        style (unchecked-get props "style")
        font  (nth fonts index)]
    (mf/html
     [:& font-item {:key key
                    :font font
                    :style style
                    :on-click on-select
                    :current? (= (:id font) (:id selected))}])))

(mf/defc font-options
  {::mf/wrap-props false}
  [{:keys [values on-change on-blur show-recent]}]
  (let [{:keys [font-id font-size font-variant-id]} values

        font-id         (or font-id (:font-id txt/default-text-attrs))
        font-size       (or font-size (:font-size txt/default-text-attrs))
        font-variant-id (or font-variant-id (:font-variant-id txt/default-text-attrs))

        fonts           (mf/deref fonts/fontsdb)
        font            (get fonts font-id)
        recent-fonts    (mf/deref refs/workspace-recent-fonts)
        last-font       (mf/use-ref nil)

        open-selector?  (mf/use-state false)

        change-font
        (mf/use-callback
         (mf/deps on-change fonts recent-fonts)
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
        (mf/use-callback
         (mf/deps on-change)
         (fn [new-font-size]
           (when-not (str/empty? new-font-size)
             (on-change {:font-size (str new-font-size)}))))

        on-font-variant-change
        (mf/use-callback
         (mf/deps font on-change)
         (fn [event]
           (let [new-variant-id (dom/get-target-val event)
                 variant (d/seek #(= new-variant-id (:id %)) (:variants font))]
             (on-change {:font-id (:id font)
                         :font-family (:family font)
                         :font-variant-id new-variant-id
                         :font-weight (:weight variant)
                         :font-style (:style variant)})
             (dom/blur! (dom/get-target event)))))

        on-font-select
        (mf/use-callback
         (mf/deps change-font)
         (fn [font*]
           (when (not= font font*)
             (change-font (:id font*)))

           (when (some? on-blur)
             (on-blur))))

        on-font-selector-close
        (mf/use-callback
         (fn []
           (reset! open-selector? false)
           (when (some? on-blur)
             (on-blur))
           (when (mf/ref-val last-font)
             (st/emit! (fts/add-recent-font (mf/ref-val last-font))))
           ))]

    [:*
     (when @open-selector?
       [:& font-selector
        {:current-font font
         :on-close on-font-selector-close
         :on-select on-font-select
         :show-recent show-recent}])

     [:div.row-flex
      [:div.input-select.font-option
       {:on-click #(reset! open-selector? true)}
       (cond
         (= :multiple font-id)
         "--"

         (some? font)
         (:name font)

         :else
         (tr "dashboard.fonts.deleted-placeholder"))]]


     [:div.row-flex
      {:on-focus select-all}
      (let [size-options [8 9 10 11 12 14 16 18 24 36 48 72]
            size-options (if (= font-size :multiple) (into [""] size-options) size-options)]
        [:& editable-select
         {:value (attr->string font-size)
          :class "input-option size-option"
          :options size-options
          :type "number"
          :placeholder "--"
          :min 3
          :max 1000
          :on-change on-font-size-change
          :on-blur on-blur}])

      [:select.input-select.variant-option
       {:disabled (= font-id :multiple)
        :value (attr->string font-variant-id)
        :on-change on-font-variant-change
        :on-blur on-blur}
       (when (or (= font-id :multiple) (= font-variant-id :multiple))
         [:option {:value ""} "--"])
       (for [variant (:variants font)]
         [:option {:value (:id variant)
                   :key (pr-str variant)}
          (:name variant)])]]]))


(mf/defc spacing-options
  {::mf/wrap-props false}
  [{:keys [values on-change on-blur]}]
  (let [{:keys [line-height
                letter-spacing]} values

        line-height (or line-height "1.2")
        letter-spacing (or letter-spacing "0")

        line-height-nillable (if (= (str line-height) "1.2") false true)

        handle-change
        (fn [value attr]
          (on-change {attr (str value)}))]

    [:div.spacing-options
     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt (tr "workspace.options.text-options.line-height")}
       i/line-height]
      [:> numeric-input
       {:min -200
        :max 200
        :step 0.1
        :default "1.2"
        :value (attr->string line-height)
        :placeholder (tr "settings.multiple")
        :nillable line-height-nillable
        :on-change #(handle-change % :line-height)
        :on-focus select-all
        :on-blur on-blur}]]

     [:div.input-icon
      [:span.icon-before.tooltip.tooltip-bottom
       {:alt (tr "workspace.options.text-options.letter-spacing")}
       i/letter-spacing]
      [:> numeric-input
       {:min -200
        :max 200
        :step 0.1
        :value (attr->string letter-spacing)
        :placeholder (tr "settings.multiple")
        :on-change #(handle-change % :letter-spacing)
        :on-focus select-all
        :on-blur on-blur}]]]))

(mf/defc text-transform-options
  {::mf/wrap-props false}
  [{:keys [values on-change on-blur]}]
  (let [text-transform (or (:text-transform values) "none")
        handle-change
        (fn [_ type]
          (on-change {:text-transform type})
          (when (some? on-blur) (on-blur)))]
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.none")
       :class (dom/classnames :current (= "none" text-transform))
       :on-focus #(dom/prevent-default %)
       :on-click #(handle-change % "none")}
      i/minus]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.uppercase")
       :class (dom/classnames :current (= "uppercase" text-transform))
       :on-click #(handle-change % "uppercase")}
      i/uppercase]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.lowercase")
       :class (dom/classnames :current (= "lowercase" text-transform))
       :on-click #(handle-change % "lowercase")}
      i/lowercase]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.titlecase")
       :class (dom/classnames :current (= "capitalize" text-transform))
       :on-click #(handle-change % "capitalize")}
      i/titlecase]]))

(mf/defc typography-options
  {::mf/wrap-props false}
  [{:keys [ids editor values on-change on-blur show-recent]}]
  (let [opts #js {:editor editor
                  :ids ids
                  :values values
                  :on-change on-change
                  :on-blur on-blur
                  :show-recent show-recent}]
    [:div.element-set-content
     [:> font-options opts]
     [:div.row-flex
      [:> spacing-options opts]]
     [:div.row-flex
      [:> text-transform-options opts]]]))

(mf/defc typography-entry
  {::mf/wrap-props false}
  [{:keys [file-id typography local? selected? on-click on-change on-detach on-context-menu editing? focus-name? external-open*]}]
  (let [hover-detach*        (mf/use-state false)
        hover-detach?        (deref hover-detach*)

        name-input-ref       (mf/use-ref)
        read-only?           (mf/use-ctx ctx/workspace-read-only?)
        editable?            (and local? (not read-only?))

        open*                (mf/use-state editing?)
        open?                (deref open*)

        on-name-blur
        (mf/use-callback
         (mf/deps on-change)
         (fn [event]
           (let [name (dom/get-target-val event)]
             (when-not (str/blank? name)
               (on-change {:name name})))))

        on-pointer-enter
        (mf/use-fn #(reset! hover-detach* true))

        on-pointer-leave
        (mf/use-fn #(reset! hover-detach* false))

        on-open
        (mf/use-fn #(reset! open* true))

        on-close
        (mf/use-fn #(reset! open* false))

        navigate-to-library
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (when file-id
             (st/emit! (dw/navigate-to-library file-id)))))

        ]

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
     [:div.element-set-options-group.typography-entry
      {:class (when ^boolean selected? "selected")
       :style {:display (when ^boolean open? "none")}}
      [:div.typography-selection-wrapper
       {:class (when ^boolean on-click "is-selectable")
        :on-click on-click
        :on-context-menu on-context-menu}
       [:div.typography-sample
        {:style {:font-family (:font-family typography)
                 :font-weight (:font-weight typography)
                 :font-style (:font-style typography)}}
        (tr "workspace.assets.typography.sample")]
       [:div.typography-name {:title (:name typography)}(:name typography)]]
      [:div.element-set-actions
       (when ^boolean on-detach
         [:div.element-set-actions-button
          {:on-pointer-enter on-pointer-enter
           :on-pointer-leave on-pointer-leave
           :on-click on-detach}
          (if ^boolean hover-detach? i/unchain i/chain)])

       [:div.element-set-actions-button
        {:on-click on-open}
        i/actions]]]

     [:& advanced-options {:visible? open? :on-close on-close}
      (if ^boolean editable?
        [:*
         [:div.element-set-content
          [:div.row-flex
           [:input.element-name.adv-typography-name
            {:type "text"
             :ref name-input-ref
             :default-value (:name typography)
             :on-blur on-name-blur}]

           [:div.element-set-actions-button
            {:on-click on-close}
            i/actions]]]

         [:& typography-options {:values typography
                                 :on-change on-change
                                 :show-recent false}]]

        [:div.element-set-content.typography-read-only-data
         [:div.row-flex.typography-name
          [:span {:title (:name typography)} (:name typography)]]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.font-id")]
          [:span (:font-id typography)]]

         [:div.element-set-actions-button.actions-inside
          {:on-click on-close}
          i/actions]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.font-variant-id")]
          [:span (:font-variant-id typography)]]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.font-size")]
          [:span (:font-size typography)]]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.line-height")]
          [:span (:line-height typography)]]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.letter-spacing")]
          [:span (:letter-spacing typography)]]

         [:div.row-flex
          [:span.label (tr "workspace.assets.typography.text-transform")]
          [:span (:text-transform typography)]]

         (when-not local?
           [:div.row-flex
            [:a.go-to-lib-button
             {:on-click navigate-to-library}
             (tr "workspace.assets.typography.go-to-edit")]])]

        )]]))
