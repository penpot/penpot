;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.shapes.frame
  (:require
   [app.common.data :as d]
   [app.common.math :as math]
   [app.main.data.workspace :as udw]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.blur :refer [blur-menu]]
   [app.main.ui.workspace.sidebar.options.menus.fill :refer [fill-attrs fill-menu]]
   [app.main.ui.workspace.sidebar.options.menus.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.sidebar.options.menus.layer :refer [layer-attrs layer-menu]]
   [app.main.ui.workspace.sidebar.options.menus.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.menus.stroke :refer [stroke-attrs stroke-menu]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.alpha :as mf]))

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
        (fn [value attr]
          (st/emit! (udw/update-dimensions [(:id shape)] attr value)))

        on-proportion-lock-change
        (fn [_]
          (st/emit! (udw/set-shape-proportion-lock (:id shape) (not (:proportion-lock shape)))))

        on-position-change
        (fn [value attr]
          (st/emit! (udw/update-position (:id shape) {attr value})))

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
       [:span.orientation-icon {:on-click #(on-orientation-clicked :vert)} i/size-vert]
       [:span.orientation-icon {:on-click #(on-orientation-clicked :horiz)} i/size-horiz]]

      ;; WIDTH & HEIGHT
      [:div.row-flex
       [:span.element-set-subtitle (tr "workspace.options.size")]
       [:div.input-element.pixels {:title (tr "workspace.options.width")}
        [:> numeric-input {:min 1
                           :on-click select-all
                           :on-change on-width-change
                           :value (-> (:width shape)
                                      (math/precision 2)
                                      (d/coalesce-str "1"))}]]

       [:div.input-element.pixels {:title (tr "workspace.options.height")}
        [:> numeric-input {:min 1
                           :on-click select-all
                           :on-change on-height-change
                           :value (-> (:height shape)
                                      (math/precision 2)
                                      (d/coalesce-str "1"))}]]

       [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                        :on-click on-proportion-lock-change}
        (if (:proportion-lock shape)
          i/lock
          i/unlock)]]

      ;; POSITION
      [:div.row-flex
       [:span.element-set-subtitle (tr "workspace.options.position")]
       [:div.input-element.pixels {:title (tr "workspace.options.x")}
        [:> numeric-input {:placeholder "x"
                           :on-click select-all
                           :on-change on-pos-x-change
                           :value (-> (:x shape)
                                      (math/precision 2)
                                      (d/coalesce-str "0"))}]]
       [:div.input-element.pixels {:title (tr "workspace.options.y")}
        [:> numeric-input {:placeholder "y"
                           :on-click select-all
                           :on-change on-pos-y-change
                           :value (-> (:y shape)
                                      (math/precision 2)
                                      (d/coalesce-str "0"))}]]]]]))

(def +size-presets+
  [{:name "APPLE"}
   {:name "iPhone 12/12 Pro"
    :width 390
    :height 844}
   {:name "iPhone 12 Mini"
    :width 360
    :height 780}
   {:name "iPhone 12 Pro Max"
    :width 428
    :height 926}
   {:name "iPhone X/XS/11 Pro"
    :width 375
    :height 812}
   {:name "iPhone XS Max/XR/11"
    :width 414
    :height 896}
   {:name "iPhone 6/7/8 Plus"
    :width 414
    :height 736}
   {:name "iPhone 6/7/8/SE2"
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
   {:name "Watch 44mm"
    :width 368
    :height 448}
   {:name "Watch 42mm"
    :width 312
    :height 390}
   {:name "Watch 40mm"
    :width 324
    :height 394}
   {:name "Watch 38mm"
    :width 272
    :height 340}

   {:name "ANDROID"}
   {:name "Mobile"
    :width 360
    :height 640}
   {:name "Tablet"
    :width 768
    :height 1024}
   {:name "Google Pixel 4a/5"
    :width 393
    :height 851}
   {:name "Samsung Galaxy S20+"
    :width 384
    :height 854}
   {:name "Samsung Galaxy A71/A51"
    :width 412
    :height 914}

   {:name "MICROSOFT"}
   {:name "Surface Pro 3"
    :width 1440
    :height 960}
   {:name "Surface Pro 4/5/6/7"
    :width 1368
    :height 912}

   {:name "WEB"}
   {:name "Web 1280"
    :width 1280
    :height 800}
   {:name "Web 1366"
    :width 1366
    :height 768}
   {:name "Web 1024"
    :width 1024
    :height 768}
   {:name "Web 1920"
    :width 1920
    :height 1080}

   {:name "PRINT (96dpi)"}
   {:name "A0"
    :width 3179
    :height 4494}
   {:name "A1"
    :width 2245
    :height 3179}
   {:name "A2"
    :width 1587
    :height 2245}
   {:name "A3"
    :width 1123
    :height 1587}
   {:name "A4"
    :width 794
    :height 1123}
   {:name "A5"
    :width 559
    :height 794}
   {:name "A6"
    :width 397
    :height 559}
   {:name "Letter"
    :width 816
    :height 1054}
   {:name "DIN Lang"
    :width 835
    :height 413}

   {:name "SOCIAL MEDIA"}
   {:name "Instagram profile"
    :width 320
    :height 320}
   {:name "Instagram post"
    :width 1080
    :height 1080}
   {:name "Instagram story"
    :width 1080
    :height 1920}
   {:name "Facebook profile"
    :width 720
    :height 720}
   {:name "Facebook cover"
    :width 820
    :height 312}
   {:name "Facebook post"
    :width 1200
    :height 630}
   {:name "LinkedIn profile"
    :width 400
    :height 400}
   {:name "LinkedIn cover"
    :width 1584
    :height 396}
   {:name "LinkedIn post"
    :width 1200
    :height 627}
   {:name "Twitter profile"
    :width 400
    :height 400}
   {:name "Twitter header"
    :width 1500
    :height 500}
   {:name "Twitter post"
    :width 1024
    :height 512}
   {:name "YouTube profile"
    :width 800
    :height 800}
   {:name "YouTube banner"
    :width 2560
    :height 1440}
   {:name "YouTube thumb"
    :width 1280
    :height 720}
   ])

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)
        stroke-values (select-keys shape stroke-attrs)
        layer-values (select-keys shape layer-attrs)]
    [:*
     [:& measures-menu {:shape shape}]
     [:& layer-menu {:ids ids
                     :type type
                     :values layer-values}]
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

