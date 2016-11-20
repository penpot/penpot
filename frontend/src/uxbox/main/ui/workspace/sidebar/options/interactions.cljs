;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.interactions
  (:require [sablono.core :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.state :as st]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.ui.workspace.sidebar.sitemap :refer (pages-ref)]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as cp]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer (parse-int parse-float read-string)]))

;; --- Helpers

(defn- on-change
  ([form attr event]
   (dom/prevent-default event)
   (let [value (dom/event->value event)
         value (read-string value)]
     (swap! form assoc attr value)))
  ([form attr keep event]
   (let [data (select-keys @form keep)]
     (reset! form data)
     (on-change form attr event))))

;; --- Interactions List

(defn- translate-trigger-name
  [trigger]
  (case trigger
    :click "Click"
    :doubleclick "Double Click"
    :rightclick "Right Click"
    :hover "Hover"
    :mousein "Mouse In"
    :mouseout "Mouse Out"
    ;; :swiperight "Swipe Right"
    ;; :swipeleft "Swipe Left"
    ;; :swipedown "Swipe Down"
    ;; :touchandhold "Touch and Hold"
    ;; :holdrelease "Hold release"
    (pr-str trigger)))

(defn- interactions-list-render
  [own shape form-ref]
  (letfn [(on-edit [item event]
            (dom/prevent-default event)
            (reset! form-ref item))
          (delete [item]
            (let [sid (:id shape)
                  id (:id item)]
              (rs/emit! (uds/delete-interaction sid id))))
          (on-delete [item event]
            (dom/prevent-default event)
            (let [delete (partial delete item)]
              (udl/open! :confirm {:on-accept delete})))]
    (html
     [:ul.element-list
      (for [item (vals (:interactions shape))
            :let [key (pr-str (:id item))]]
        [:li {:key key}
         [:div.list-icon i/action]
         [:span (translate-trigger-name (:trigger item))]
         [:div.list-actions
          [:a {:on-click (partial on-edit item)} i/pencil]
          [:a {:on-click (partial on-delete item)} i/trash]]])])))

(def interactions-list
  (mx/component
   {:render interactions-list-render
    :name "interactions-list"
    :mixins [mx/static]}))

;; --- Trigger Input

(defn- trigger-input-render
  [own form-ref]
  (when-not (:trigger @form-ref)
    (swap! form-ref assoc :trigger :click))
  (html
   [:div
    [:span "Trigger"]
    [:div.row-flex
     [:select.input-select {:placeholder "Choose a trigger"
                            :on-change (partial on-change form-ref :trigger)
                            :value (pr-str (:trigger @form-ref))}
      [:option {:value ":click"} "Click"]
      [:option {:value ":doubleclick"} "Double-click"]
      [:option {:value ":rightclick"} "Right-click"]
      [:option {:value ":hover"} "Hover"]
      [:option {:value ":mousein"} "Mouse in"]
      [:option {:value ":mouseout"} "Mouse out"]
      #_[:option {:value ":swiperight"} "Swipe right"]
      #_[:option {:value ":swipeleft"} "Swipe left"]
      #_[:option {:value ":swipedown"} "Swipe dpwn"]
      #_[:option {:value ":touchandhold"} "Touch and hold"]
      #_[:option {:value ":holdrelease"} "Hold release"]
      #_[:option {:value ":keypress"} "Key press"]
      #_[:option {:value ":pageisloaded"} "Page is loaded"]
      #_[:option {:value ":windowscroll"} "Window is scrolled to"]]]]))

(def trigger-input
  (mx/component
   {:render trigger-input-render
    :name "trigger-input"
    :mixins [mx/static]}))

;; --- URL Input

(defn- url-input-render
  [own form-ref]
  (html
   [:div
    [:span "Url"]
    [:div.row-flex
     [:input.input-text
      {:placeholder "http://"
       :on-change (partial on-change form-ref :url)
       :value (:url @form-ref "")
       :type "url"}]]]))

(def url-input
  (mx/component
   {:render url-input-render
    :name "url-input"}))

;; --- Elements Input

(defn- collect-shapes
  [state page]
  (let [shapes-by-id (:shapes state)
        shapes (get-in state [:pages page :shapes])]
    (letfn [(resolve-shape [acc id]
              (let [shape (get shapes-by-id id)]
                (if (= (:type shape) :group)
                  (reduce resolve-shape (conj acc shape) (:items shape))
                  (conj acc shape))))]
      (reduce resolve-shape [] shapes))))

(defn- elements-input-render
  [own page form-ref]
  (let [shapes (collect-shapes @st/state page)]
    (html
     [:div
      [:span "Element"]
      [:div.row-flex
       [:select.input-select
        {:placeholder "Choose an element"
         :on-change (partial on-change form-ref :element)
         :value (pr-str (:element @form-ref))}
        [:option {:value "nil"} "---"]
        (for [shape shapes
              :let [key (pr-str (:id shape))]]
          [:option {:key key :value key} (:name shape)])]]])))

(def elements-input
  (mx/component
   {:render elements-input-render
    :name "elements-input"}))

;; --- Page Input

(defn- pages-input-render
  [own form-ref path]
  (let [pages @pages-ref]
    (when (and (not (:page @form-ref))
               (pos? (count pages)))
      (swap! form-ref assoc :page (:id (first pages))))
    (html
     [:div
      [:span "Page"]
      [:div.row-flex
       [:select.input-select {:placeholder "Choose a page"
                              :on-change (partial on-change form-ref :page)
                              :value (pr-str (:page @form-ref))}
        (for [page pages
              :let [key (:index page)]]
          [:option {:key key :value key} (:name page)])]]])))

(def pages-input
  (mx/component
   {:render pages-input-render
    :name "pages-input"}))

;; --- Animation

(defn- animation-input-render
  [own form-ref]
  (when-not (:action @form-ref)
    (swap! form-ref assoc :animation :none))
  (html
   [:div
    [:span "Animation"]
    [:div.row-flex
     [:select.input-select
      {:placeholder "Animation"
       :on-change (partial on-change form-ref :animation)
       :value (pr-str (:animation @form-ref))}
      [:option {:value ":none"} "None"]
      [:option {:value ":fade"} "Fade"]
      [:option {:value ":slide"} "Slide"]]]]))

(def animation-input
  (mx/component
   {:render animation-input-render
    :name "animation-input"}))

;; --- MoveTo Input

(defn- moveto-input-render
  [own form-ref]
  (when-not (:moveto-x @form-ref)
    (swap! form-ref assoc :moveto-x 0))
  (when-not (:moveto-y @form-ref)
    (swap! form-ref assoc :moveto-y 0))
  (html
   [:div
    [:span "Move to position"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "X"
        :on-change (partial on-change form-ref :moveto-x)
        :type "number"
        :value (:moveto-x @form-ref "")}]]
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Y"
        :on-change (partial on-change form-ref :moveto-y)
        :type "number"
        :value (:moveto-y @form-ref "")}]]]]))

(def moveto-input
  (mx/component
   {:render moveto-input-render
    :name "moveto-input"}))

;; --- MoveBy Input

(defn- moveby-input-render
  [own form-ref]
  (when-not (:moveby-x @form-ref)
    (swap! form-ref assoc :moveby-x 0))
  (when-not (:moveby-y @form-ref)
    (swap! form-ref assoc :moveby-y 0))
  (html
   [:div
    [:span "Move to position"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "X"
        :on-change (partial on-change form-ref :moveby-x)
        :type "number"
        :value (:moveby-x @form-ref "")}]]
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Y"
        :on-change (partial on-change form-ref :moveby-y)
        :type "number"
        :value (:moveby-y @form-ref "")}]]]]))

(def moveby-input
  (mx/component
   {:render moveby-input-render
    :name "moveby-input"}))

;; --- Opacity Input

(defn- opacity-input-render
  [own form-ref]
  (when-not (:opacity @form-ref)
    (swap! form-ref assoc :opacity 100))
  (html
   [:div
    [:span "Opacity"]
    [:div.row-flex
     [:div.input-element.percentail
      [:input.input-text
       {:placeholder "%"
        :on-change (partial on-change form-ref :opacity)
        :min "0"
        :max "100"
        :type "number"
        :value (:opacity @form-ref "")}]]]]))

(def opacity-input
  (mx/component
   {:render opacity-input-render
    :name "opacity-input"}))

;; --- Rotate Input

(defn rotate-input-render
  [own form-ref]
  (html
   [:div
    [:span "Rotate (dg)"]
    [:div.row-flex
     [:div.input-element.degrees
      [:input.input-text
       {:placeholder "dg"
        :on-change (partial on-change form-ref :rotation)
        :type "number"
        :value (:rotation @form-ref "")}]]]]))

(def rotate-input
  (mx/component
   {:render rotate-input-render
    :name "rotate-input"}))

;; --- Resize Input

(defn- resize-input-render
  [own form-ref]
  (html
   [:div
    [:span "Resize"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Width"
        :on-change (partial on-change form-ref :resize-width)
        :type "number"
        :value (:resize-width @form-ref "")}]]
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Height"
        :on-change (partial on-change form-ref :resize-height)
        :type "number"
        :value (:resize-height @form-ref "")}]]]]))

(def resize-input
  (mx/component
   {:render resize-input-render
    :name "resize-input"}))

;; --- Color Input

(defn- colorpicker-render
  [own {:keys [x y on-change value]}]
  (let [left (- x 260)
        top (- y 50)]
    (html
     [:div.colorpicker-tooltip
      {:style {:left (str left "px")
               :top (str top "px")}}

      (cp/colorpicker
       :theme :small
       :value value
       :on-change on-change)])))

(def ^:private colorpicker
  (mx/component
   {:render colorpicker-render
    :name "colorpicker"
    :mixins [mx/reactive mx/static]}))

(defmethod lbx/render-lightbox :interactions/colorpicker
  [params]
  (colorpicker params))

(defn- color-input-render
  [own form-ref]
  (when-not (:fill-color @form-ref)
    (swap! form-ref assoc :fill-color "#000000"))
  (when-not (:stroke-color @form-ref)
    (swap! form-ref assoc :stroke-color "#000000"))
  (letfn [(on-change [attr color]
            (swap! form-ref assoc attr color))
          (show-picker [attr event]
            (let [x (.-clientX event)
                  y (.-clientY event)
                  opts {:x x :y y
                        :on-change (partial on-change attr)
                        :value (get @form-ref attr)
                        :transparent? true}]
              (udl/open! :interactions/colorpicker opts)))]
    (let [stroke-color (:stroke-color @form-ref)
          fill-color (:fill-color @form-ref)]
      (html
       [:div
        [:div.row-flex
         [:div.column-half
          [:span "Fill"]
          [:div.color-data
           [:span.color-th
            {:style {:background-color fill-color}
             :on-click (partial show-picker :fill-color)}]
           [:div.color-info
            [:span fill-color]]]]
         [:div.column-half
          [:span "Stroke"]
          [:div.color-data
           [:span.color-th
            {:style {:background-color stroke-color}
             :on-click (partial show-picker :stroke-color)}]
           [:div.color-info
            [:span stroke-color]]]]]]))))

(def color-input
  (mx/component
   {:render color-input-render
    :name "color-input"}))

;; --- Easing Input

(defn- easing-input-render
  [own form-ref]
  (when-not (:easing @form-ref)
    (swap! form-ref assoc :easing :linear))
  (html
   [:div
    [:span "Easing"]
    [:div.row-flex
     [:select.input-select
      {:placeholder "Easing"
       :on-change (partial on-change form-ref :easing)
       :value (pr-str (:easing @form-ref))}
      [:option {:value ":linear"} "Linear"]
      [:option {:value ":easein"} "Ease in"]
      [:option {:value ":easeout"} "Ease out"]
      [:option {:value ":easeinout"} "Ease in out"]]]]))

(def easing-input
  (mx/component
   {:render easing-input-render
    :name "easing-input"}))

;; --- Duration Input

(defn- duration-input-render
  [own form-ref]
  (when-not (:duration @form-ref)
    (swap! form-ref assoc :duration 300))
  (when-not (:delay @form-ref)
    (swap! form-ref assoc :delay 0))
  (html
   [:div
    [:span "Duration  |  Delay"]
    [:div.row-flex
     [:div.input-element.miliseconds
      [:input.input-text
       {:placeholder "Duration"
        :type "number"
        :on-change (partial on-change form-ref :duration)
        :value (pr-str (:duration @form-ref))}]]
     [:div.input-element.miliseconds
      [:input.input-text {:placeholder "Delay"
                         :type "number"
                         :on-change (partial on-change form-ref :delay)
                         :value (pr-str (:delay @form-ref))}]]]]))

(def duration-input
  (mx/component
   {:render duration-input-render
    :name "duration-input"}))

;; --- Action Input

(defn- action-input-render
  [own page form-ref]
  (when-not (:action @form-ref)
    (swap! form-ref assoc :action :show))

  (let [form @form-ref
        simple? #{:gotourl :gotopage}
        elements? (complement simple?)
        animation? #{:show :hide :toggle}
        only-easing? (complement animation?)]
    (html
     [:div
      [:span "Action"]
      [:div.row-flex
       [:select.input-select
        {:placeholder "Choose an action"
         :on-change (partial on-change form-ref :action [:trigger])
         :value (pr-str (:action form))}
        [:option {:value ":show"} "Show"]
        [:option {:value ":hide"} "Hide"]
        [:option {:value ":toggle"} "Toggle"]
        ;; [:option {:value ":moveto"} "Move to"]
        [:option {:value ":moveby"} "Move by"]
        [:option {:value ":opacity"} "Opacity"]
        [:option {:value ":size"} "Size"]
        [:option {:value ":color"} "Color"]
        ;; [:option {:value ":rotate"} "Rotate"]
        [:option {:value ":gotopage"} "Go to page"]
        [:option {:value ":gotourl"} "Go to URL"]
        #_[:option {:value ":goback"} "Go back"]
        [:option {:value ":scrolltoelement"} "Scroll to element"]]]

      (case (:action form)
        :gotourl (url-input form-ref)
        :gotopage (pages-input form-ref)
        :color (color-input form-ref)
        :rotate (rotate-input form-ref)
        :size (resize-input form-ref)
        :moveto (moveto-input form-ref)
        :moveby (moveby-input form-ref)
        :opacity (opacity-input form-ref)
        nil)

      (when (elements? (:action form))
        (elements-input page form-ref))

      (when (and (animation? (:action form))
                 (:element @form-ref))
        (animation-input form-ref))

      (when (or (not= (:animation form :none) :none)
                (and (only-easing? (:action form))
                     (:element form)))
        (mx/concat
         (easing-input form-ref)
         (duration-input form-ref)))
      ])))

(def action-input
  (mx/component
   {:render action-input-render
    :name "action-input"}))

;; --- Form

(defn- interactions-form-render
  [own shape form-ref]
  (letfn [(on-submit [event]
            (dom/prevent-default event)
            (let [shape-id (:id shape)
                  data (deref form-ref)]
              (rs/emit! (uds/update-interaction shape-id data))
              (reset! form-ref nil)))
          (on-cancel [event]
            (dom/prevent-default event)
            (reset! form-ref nil))]
    (html
     [:form {:on-submit on-submit}
      (trigger-input form-ref)
      (action-input (:page shape) form-ref)
      [:div.row-flex
       [:input.btn-primary.btn-small.save-btn
        {:value "Save" :type "submit"}]
       [:a.cancel-btn {:on-click on-cancel}
        "Cancel"]]])))

(def interactions-form
  (mx/component
   {:render interactions-form-render
    :name "interactions-form"}))

;; --- Interactions Menu

(defn- interactions-menu-render
  [own menu shape]
  (let [local (:rum/local own)
        form-ref (l/derive (l/key :form) local)
        interactions (:interactions shape)
        create-interaction #(reset! form-ref {})]
    (html
     [:div.element-set {:key (str (:id menu))}
      [:div.element-set-title (:name menu)]
      [:div.element-set-content
       (if @form-ref
         (interactions-form shape form-ref)
         [:div
          (interactions-list shape form-ref)
          [:input.btn-primary.btn-small
           {:value "New interaction"
            :on-click create-interaction
            :type "button"}]])]])))

(def interactions-menu
  (mx/component
   {:render interactions-menu-render
    :name "interactions-menu"
    :mixins [mx/static (mx/local)]}))


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
