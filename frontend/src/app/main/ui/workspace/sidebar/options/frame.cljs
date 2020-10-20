;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns app.main.ui.workspace.sidebar.options.frame
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.common.geom.point :as gpt]
   [app.util.i18n :refer [tr]]
   [app.common.math :as math]
   [app.main.store :as st]
   [app.main.data.workspace :as udw]
   [app.main.ui.icons :as i]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.stroke :refer [stroke-attrs stroke-menu]]
   [app.main.ui.workspace.sidebar.options.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]))

(declare +size-presets+)

(mf/defc measures-menu
  [{:keys [shape] :as props}]

  (let [show-presets-dropdown? (mf/use-state false)

        on-preset-selected
        (fn [width height]
          (st/emit! (udw/update-dimensions [(:id shape)] :width width)
                    (udw/update-dimensions [(:id shape)] :height height)))

        on-orientation-clicked
        (fn [orientation]
          (let [width (:width shape)
                height (:height shape)
                new-width (if (= orientation :horiz) (max width height) (min width height))
                new-height (if (= orientation :horiz) (min width height) (max width height))]
            (st/emit! (udw/update-dimensions [(:id shape)] :width new-width)
                      (udw/update-dimensions [(:id shape)] :height new-height))))

        on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-dimensions [(:id shape)] attr value))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/set-shape-proportion-lock (:id shape) (not (:proportion-lock shape)))))

        on-position-change
        (fn [event attr]
          (let [cval (-> (dom/get-target event)
                         (dom/get-value)
                         (d/parse-integer 0))]
            ;; TODO: Change so not apply the modifiers until blur
            (when cval
              (st/emit! (udw/update-position (:id shape) {attr cval})))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)
        select-all #(-> % (dom/get-target) (.select))]

    [:div.element-set

     [:div.element-set-content

      [:div.row-flex
       [:div.presets.custom-select.flex-grow {:on-click #(reset! show-presets-dropdown? true)}
        [:span (tr "workspace.options.size-presets")]
        [:span.dropdown-button i/arrow-down]
        [:& dropdown {:show @show-presets-dropdown?
                      :on-close #(reset! show-presets-dropdown? false)}
         [:ul.custom-select-dropdown
          (for [size-preset +size-presets+]
            (if-not (:width size-preset)
              [:li.dropdown-label {:key (:name size-preset)}
               [:span (:name size-preset)]]
              [:li {:key (:name size-preset)
                    :on-click #(on-preset-selected (:width size-preset) (:height size-preset))}
               (:name size-preset)
               [:span (:width size-preset) " x " (:height size-preset)]]))]]]
       [:span.orientation-icon {on-click #(on-orientation-clicked :vert)} i/size-vert]
       [:span.orientation-icon {on-click #(on-orientation-clicked :horiz)} i/size-horiz]]

      ;; WIDTH & HEIGHT
      [:div.row-flex
       [:span.element-set-subtitle (tr "workspace.options.size")]
       [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                        :on-click on-proportion-lock-change}
        (if (:proportion-lock shape)
          i/lock
          i/unlock)]
       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :on-click select-all
                            :on-change on-width-change
                            :value (-> (:width shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]


       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :on-click select-all
                            :on-change on-height-change
                            :value (-> (:height shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]]

      ;; POSITION
      [:div.row-flex
       [:span.element-set-subtitle (tr "workspace.options.position")]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "x"
                            :type "number"
                            :on-click select-all
                            :on-change on-pos-x-change
                            :value (-> (:x shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "y"
                            :type "number"
                            :on-click select-all
                            :on-change on-pos-y-change
                            :value (-> (:y shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]]]]))

(def +size-presets+
  [{:name "APPLE"}
   {:name "iPhone X"
    :width 375
    :height 812}
   {:name "iPhone 6/7/8 Plus"
    :width 414
    :height 736}
   {:name "iPhone 6/7/8"
    :width 375
    :height 667}
   {:name "iPhone 5/SE"
    :width 320
    :height 568}
   {:name "iPad"
    :width 768
    :height 1024}
   {:name "iPad Pro 10.5in"
    :width 834
    :height 1112}
   {:name "iPad Pro 12.9in"
    :width 1024
    :height 1366}
   {:name "Watch 42mm"
    :width 312
    :height 390}
   {:name "Watch 38mm"
    :width 272
    :height 340}

   {:name "GOOGLE"}
   {:name "Android mobile"
    :width 360
    :height 640}
   {:name "Android tablet"
    :width 768
    :height 1024}

   {:name "MICROSOFT"}
   {:name "Surface Pro 3"
    :width 1440
    :height 960}
   {:name "Surface Pro 4"
    :width 1368
    :height 912}

   {:name "WEB"}
   {:name "Web 1280"
    :width 1280
    :height 800}
   {:name "Web 1366"
    :width 1366
    :height 768}
   {:name "Web 1920"
    :width 1920
    :height 1080}
   ])

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        stroke-values (select-keys shape stroke-attrs)]
    [:*
     [:& measures-menu {:shape shape}]
     [:& fill-menu {:ids ids
                    :type type
                    :values (select-keys shape fill-attrs)}]
     [:& stroke-menu {:ids ids
                      :type type
                      :values stroke-values}]
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& frame-grid {:shape shape}]]))

