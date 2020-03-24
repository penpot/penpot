;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.interactions
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.lightbox :as udl]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.colorpicker :as cp]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]))

;; --- Helpers

;; (defn- on-change
;;   ([form attr event]
;;    (dom/prevent-default event)
;;    (let [value (dom/event->value event)
;;          value (read-string value)]
;;      (swap! form assoc attr value)))
;;   ([form attr keep event]
;;    (let [data (select-keys @form keep)]
;;      (reset! form data)
;;      (on-change form attr event))))

;; ;; --- Interactions List

;; (defn- translate-trigger-name
;;   [trigger]
;;   (case trigger
;;     :click "Click"
;;     :doubleclick "Double Click"
;;     :rightclick "Right Click"
;;     :hover "Hover"
;;     :mousein "Mouse In"
;;     :mouseout "Mouse Out"
;;     ;; :swiperight "Swipe Right"
;;     ;; :swipeleft "Swipe Left"
;;     ;; :swipedown "Swipe Down"
;;     ;; :touchandhold "Touch and Hold"
;;     ;; :holdrelease "Hold release"
;;     (pr-str trigger)))

;; (mf/defc interactions-list
;;   [{:keys [shape form] :as props}]
;;   (letfn [(on-edit [item event]
;;             (dom/prevent-default event)
;;             (reset! form item))
;;           (delete [item]
;;             (let [sid (:id shape)
;;                   id (:id item)]
;;               (st/emit! (dw/delete-interaction sid id))))
;;           (on-delete [item event]
;;             (dom/prevent-default event)
;;             (let [delete (partial delete item)]
;;               (udl/open! :confirm {:on-accept delete})))]
;;     [:ul.element-list
;;      (for [item (vals (:interactions shape))
;;            :let [key (pr-str (:id item))]]
;;        [:li {:key key}
;;         [:div.list-icon i/action]
;;         [:span (translate-trigger-name (:trigger item))]
;;         [:div.list-actions
;;          [:a {:on-click (partial on-edit item)} i/pencil]
;;          [:a {:on-click (partial on-delete item)} i/trash]]])]))

;; ;; --- Trigger Input

;; (mf/defc trigger-input
;;   [{:keys [form] :as props}]
;;   ;; (mf/use-effect
;;   ;;  {:init #(when-not (:trigger @form) (swap! form assoc :trigger :click))
;;   ;;   :deps true})
;;   [:div
;;    [:span "Trigger"]
;;    [:div.row-flex
;;     [:select.input-select {:placeholder "Choose a trigger"
;;                            :on-change (partial on-change form :trigger)
;;                            :value (pr-str (:trigger @form))}
;;      [:option {:value ":click"} "Click"]
;;      [:option {:value ":doubleclick"} "Double-click"]
;;      [:option {:value ":rightclick"} "Right-click"]
;;      [:option {:value ":hover"} "Hover"]
;;      [:option {:value ":mousein"} "Mouse in"]
;;      [:option {:value ":mouseout"} "Mouse out"]
;;      #_[:option {:value ":swiperight"} "Swipe right"]
;;      #_[:option {:value ":swipeleft"} "Swipe left"]
;;      #_[:option {:value ":swipedown"} "Swipe dpwn"]
;;      #_[:option {:value ":touchandhold"} "Touch and hold"]
;;      #_[:option {:value ":holdrelease"} "Hold release"]
;;      #_[:option {:value ":keypress"} "Key press"]
;;      #_[:option {:value ":pageisloaded"} "Page is loaded"]
;;      #_[:option {:value ":windowscroll"} "Window is scrolled to"]]]])

;; ;; --- URL Input

;; (mf/defc url-input
;;   [form]
;;   [:div
;;    [:span "Url"]
;;    [:div.row-flex
;;     [:input.input-text
;;      {:placeholder "http://"
;;       :on-change (partial on-change form :url)
;;       :value (:url @form "")
;;       :type "url"}]]])

;; ;; --- Elements Input

;; (defn- collect-shapes
;;   [state page]
;;   (let [shapes-by-id (:shapes state)
;;         shapes (get-in state [:pages page :shapes])]
;;     (letfn [(resolve-shape [acc id]
;;               (let [shape (get shapes-by-id id)]
;;                 (if (= (:type shape) :group)
;;                   (reduce resolve-shape (conj acc shape) (:items shape))
;;                   (conj acc shape))))]
;;       (reduce resolve-shape [] shapes))))

;; (mf/defc elements-input
;;   [{:keys [page-id form] :as props}]
;;   (let [shapes (collect-shapes @st/state page-id)]
;;     [:div
;;      [:span "Element"]
;;      [:div.row-flex
;;       [:select.input-select
;;        {:placeholder "Choose an element"
;;         :on-change (partial on-change form :element)
;;         :value (pr-str (:element @form))}
;;        [:option {:value "nil"} "---"]
;;        (for [shape shapes
;;              :let [key (pr-str (:id shape))]]
;;          [:option {:key key :value key} (:name shape)])]]]))

;; ;; --- Page Input

;; (mf/defc pages-input
;;   [form-ref path]
;;   ;; FIXME: react on ref
;;   #_(let [pages (mx/react refs/selected-project-pages)]
;;     (when (and (not (:page @form-ref))
;;                (pos? (count pages)))
;;       (swap! form-ref assoc :page (:id (first pages))))
;;     [:div
;;      [:span "Page"]
;;      [:div.row-flex
;;       [:select.input-select {:placeholder "Choose a page"
;;                              :on-change (partial on-change form-ref :page)
;;                              :value (pr-str (:page @form-ref))}
;;        (for [page pages
;;              :let [key (pr-str (:id page))]]
;;          [:option {:key key :value key} (:name page)])]]]))

;; ;; --- Animation

;; (mf/defc animation-input
;;   [{:keys [form] :as props}]
;;   (when-not (:action @form)
;;     (swap! form assoc :animation :none))
;;   [:div
;;    [:span "Animation"]
;;    [:div.row-flex
;;     [:select.input-select
;;      {:placeholder "Animation"
;;       :on-change (partial on-change form :animation)
;;       :value (pr-str (:animation @form))}
;;      [:option {:value ":none"} "None"]
;;      [:option {:value ":fade"} "Fade"]
;;      [:option {:value ":slide"} "Slide"]]]])

;; ;; --- MoveTo Input

;; (mf/defc moveto-input
;;   [{:keys [form] :as props}]
;;   (when-not (:moveto-x @form)
;;     (swap! form assoc :moveto-x 0))
;;   (when-not (:moveto-y @form)
;;     (swap! form assoc :moveto-y 0))
;;   [:div
;;    [:span "Move to position"]
;;    [:div.row-flex
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "X"
;;        :on-change (partial on-change form :moveto-x)
;;        :type "number"
;;        :value (:moveto-x @form "")}]]
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "Y"
;;        :on-change (partial on-change form :moveto-y)
;;        :type "number"
;;        :value (:moveto-y @form "")}]]]])

;; ;; --- MoveBy Input

;; (mf/defc moveby-input
;;   [{:keys [form] :as props}]
;;   (when-not (:moveby-x @form)
;;     (swap! form assoc :moveby-x 0))
;;   (when-not (:moveby-y @form)
;;     (swap! form assoc :moveby-y 0))
;;   [:div
;;    [:span "Move to position"]
;;    [:div.row-flex
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "X"
;;        :on-change (partial on-change form :moveby-x)
;;        :type "number"
;;        :value (:moveby-x @form "")}]]
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "Y"
;;        :on-change (partial on-change form :moveby-y)
;;        :type "number"
;;        :value (:moveby-y @form "")}]]]])

;; ;; --- Opacity Input

;; (mf/defc opacity-input
;;   [{:keys [form] :as props}]
;;   (when-not (:opacity @form)
;;     (swap! form assoc :opacity 100))
;;   [:div
;;    [:span "Opacity"]
;;    [:div.row-flex
;;     [:div.input-element.percentail
;;      [:input.input-text
;;       {:placeholder "%"
;;        :on-change (partial on-change form :opacity)
;;        :min "0"
;;        :max "100"
;;        :type "number"
;;        :value (:opacity @form "")}]]]])

;; ;; --- Rotate Input

;; ;; (mx/defc rotate-input
;; ;;   [form]
;; ;;   [:div
;; ;;    [:span "Rotate (dg)"]
;; ;;    [:div.row-flex
;; ;;     [:div.input-element.degrees
;; ;;      [:input.input-text
;; ;;       {:placeholder "dg"
;; ;;        :on-change (partial on-change form :rotation)
;; ;;        :type "number"
;; ;;        :value (:rotation @form "")}]]]])

;; ;; --- Resize Input

;; (mf/defc resize-input
;;   [{:keys [form] :as props}]
;;   [:div
;;    [:span "Resize"]
;;    [:div.row-flex
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "Width"
;;        :on-change (partial on-change form :resize-width)
;;        :type "number"
;;        :value (:resize-width @form "")}]]
;;     [:div.input-element.pixels
;;      [:input.input-text
;;       {:placeholder "Height"
;;        :on-change (partial on-change form :resize-height)
;;        :type "number"
;;        :value (:resize-height @form "")}]]]])

;; ;; --- Color Input

;; (mf/defc colorpicker
;;   [{:keys [x y on-change value]}]
;;   (let [left (- x 260)
;;         top (- y 50)]
;;     [:div.colorpicker-tooltip
;;      {:style {:left (str left "px")
;;               :top (str top "px")}}

;;      (cp/colorpicker
;;       :theme :small
;;       :value value
;;       :on-change on-change)]))

;; (defmethod lbx/render-lightbox :interactions/colorpicker
;;   [params]
;;   (colorpicker params))

;; (mf/defc color-input
;;   [{:keys [form] :as props}]
;;   (when-not (:fill-color @form)
;;     (swap! form assoc :fill-color "#000000"))
;;   (when-not (:stroke-color @form)
;;     (swap! form assoc :stroke-color "#000000"))
;;   (letfn [(on-change [attr color]
;;             (swap! form assoc attr color))
;;           (on-change-fill-color [event]
;;             (let [value (dom/event->value event)]
;;               (when (color? value)
;;                 (on-change :fill-color value))))
;;           (on-change-stroke-color [event]
;;             (let [value (dom/event->value event)]
;;               (when (color? value)
;;                 (on-change :stroke-color value))))
;;           (show-picker [attr event]
;;             (let [x (.-clientX event)
;;                   y (.-clientY event)
;;                   opts {:x x :y y
;;                         :on-change (partial on-change attr)
;;                         :value (get @form attr)
;;                         :transparent? true}]
;;               (udl/open! :interactions/colorpicker opts)))]
;;     (let [stroke-color (:stroke-color @form)
;;           fill-color (:fill-color @form)]
;;       [:div
;;        [:div.row-flex
;;         [:div.column-half
;;          [:span "Fill"]
;;          [:div.color-data
;;           [:span.color-th
;;            {:style {:background-color fill-color}
;;             :on-click (partial show-picker :fill-color)}]
;;           [:div.color-info
;;            [:input
;;             {:on-change on-change-fill-color
;;              :value fill-color}]]]]
;;         [:div.column-half
;;          [:span "Stroke"]
;;          [:div.color-data
;;           [:span.color-th
;;            {:style {:background-color stroke-color}
;;             :on-click (partial show-picker :stroke-color)}]
;;           [:div.color-info
;;            [:input
;;             {:on-change on-change-stroke-color
;;              :value stroke-color}]]]]]])))

;; ;; --- Easing Input

;; (mf/defc easing-input
;;   [{:keys [form] :as props}]
;;   (when-not (:easing @form)
;;     (swap! form assoc :easing :linear))
;;   [:div
;;    [:span "Easing"]
;;    [:div.row-flex
;;     [:select.input-select
;;      {:placeholder "Easing"
;;       :on-change (partial on-change form :easing)
;;       :value (pr-str (:easing @form))}
;;      [:option {:value ":linear"} "Linear"]
;;      [:option {:value ":easein"} "Ease in"]
;;      [:option {:value ":easeout"} "Ease out"]
;;      [:option {:value ":easeinout"} "Ease in out"]]]])

;; ;; --- Duration Input

;; (mf/defc duration-input
;;   [{:keys [form] :as props}]
;;   (when-not (:duration @form)
;;     (swap! form assoc :duration 300))
;;   (when-not (:delay @form)
;;     (swap! form assoc :delay 0))
;;   [:div
;;    [:span "Duration  |  Delay"]
;;    [:div.row-flex
;;     [:div.input-element.miliseconds
;;      [:input.input-text
;;       {:placeholder "Duration"
;;        :type "number"
;;        :on-change (partial on-change form :duration)
;;        :value (pr-str (:duration @form))}]]
;;     [:div.input-element.miliseconds
;;      [:input.input-text {:placeholder "Delay"
;;                          :type "number"
;;                          :on-change (partial on-change form :delay)
;;                          :value (pr-str (:delay @form))}]]]])

;; ;; --- Action Input

;; (mf/defc action-input
;;   [{:keys [shape form] :as props}]
;;   ;; (when-not (:action @form)
;;   ;;   (swap! form assoc :action :show))
;;   (let [form-data (deref form)
;;         simple? #{:gotourl :gotopage}
;;         elements? (complement simple?)
;;         animation? #{:show :hide :toggle}
;;         only-easing? (complement animation?)]
;;     [:div
;;      [:span "Action"]
;;      [:div.row-flex
;;       [:select.input-select
;;        {:placeholder "Choose an action"
;;         :on-change (partial on-change form :action [:trigger])
;;         :value (pr-str (:action form-data))}
;;        [:option {:value ":show"} "Show"]
;;        [:option {:value ":hide"} "Hide"]
;;        [:option {:value ":toggle"} "Toggle"]
;;        ;; [:option {:value ":moveto"} "Move to"]
;;        [:option {:value ":moveby"} "Move by"]
;;        [:option {:value ":opacity"} "Opacity"]
;;        [:option {:value ":size"} "Size"]
;;        [:option {:value ":color"} "Color"]
;;        ;; [:option {:value ":rotate"} "Rotate"]
;;        [:option {:value ":gotopage"} "Go to page"]
;;        [:option {:value ":gotourl"} "Go to URL"]
;;        #_[:option {:value ":goback"} "Go back"]
;;        [:option {:value ":scrolltoelement"} "Scroll to element"]]]

;;      (case (:action form-data)
;;        :gotourl   [:& url-input {:form form}]
;;        ;; :gotopage (pages-input form)
;;        :color     [:& color-input {:form form}]
;;        ;; :rotate (rotate-input form)
;;        :size      [:& resize-input {:form form}]
;;        :moveto    [:& moveto-input {:form form}]
;;        :moveby    [:& moveby-input {:form form}]
;;        :opacity   [:& opacity-input {:form form}]
;;        nil)

;;      (when (elements? (:action form-data))
;;        [:& elements-input {:page-id (:page shape)
;;                            :form form}])

;;      (when (and (animation? (:action form-data))
;;                 (:element form-data))
;;        [:& animation-input {:form form}])

;;      (when (or (not= (:animation form-data :none) :none)
;;                (and (only-easing? (:action form-data))
;;                     (:element form-data)))
;;        [:*
;;         [:& easing-input {:form form}]
;;         [:& duration-input {:form form}]])]))


;; ;; --- Form

;; (mf/defc interactions-form
;;   [{:keys [shape form] :as props}]
;;   (letfn [(on-submit [event]
;;             (dom/prevent-default event)
;;             (let [sid (:id shape)
;;                   data (deref form)]
;;               (st/emit! (dw/update-interaction sid data))
;;               (reset! form nil)))
;;           (on-cancel [event]
;;             (dom/prevent-default event)
;;             (reset! form nil))]
;;     [:form {:on-submit on-submit}
;;      [:& trigger-input {:form form}]
;;      [:& action-input {:shape shape :form form}]
;;      [:div.row-flex
;;       [:input.btn-primary.btn-small.save-btn
;;         {:value "Save" :type "submit"}]
;;       [:a.cancel-btn {:on-click on-cancel}
;;        "Cancel"]]]))

;; --- Interactions Menu

(def +initial-form+
  {:trigger :click
   :action :show})

(mf/defc interactions-menu
  [{:keys [menu shape] :as props}]
  #_(let [form (mf/use-state nil)
        interactions (:interactions shape)]
    [:div.element-set {:key (str (:id menu))}
     [:div.element-set-title (:name menu)]
     [:div.element-set-content
      (if form
        [:& interactions-form {:form form :shape shape}]
        [:div
         [:& interactions-list {:form form :shape shape}]
         [:input.btn-primary.btn-small
          {:value "New interaction"
           :on-click #(reset! form +initial-form+)
           :type "button"}]])]]))

;; --- Not implemented stuff

;;      [:span "Key"]
;;      [:div.row-flex
;;       [:select.input-select {:placeholder "Choose a key"
;;                              :value ""}
;;        [:option {:value ":1"} "key 1"]
;;        [:option {:value ":2"} "key 2"]
;;        [:option {:value ":3"} "key 3"]
;;        [:option {:value ":4"} "key 4"]
;;        [:option {:value ":5"} "key 5"]]]

;;      [:span "Scrolled to (px)"]
;;      [:div.row-flex
;;       [:input.input-text {:placeholder "px"
;;                           :type "number"
;;                           :value ""}]]
