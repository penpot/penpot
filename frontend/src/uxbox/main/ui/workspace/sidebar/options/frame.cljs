;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.frame
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :as math]))

(declare +size-presets+)

(mf/defc measures-menu
  [{:keys [shape] :as props}]

  (let [show-presets-dropdown? (mf/use-state false)

        on-preset-selected
        (fn [width height]
          (st/emit! (udw/update-rect-dimensions (:id shape) :width width)
                    (udw/update-rect-dimensions (:id shape) :height height)))

        on-orientation-clicked
        (fn [orientation]
         (let [width (:width shape)
               height (:height shape)
               new-width (if (= orientation :horiz) (max width height) (min width height))
               new-height (if (= orientation :horiz) (min width height) (max width height))]
            (st/emit! (udw/update-rect-dimensions (:id shape) :width new-width)
                      (udw/update-rect-dimensions (:id shape) :height new-height))))

        on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-rect-dimensions (:id shape) attr value))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/toggle-shape-proportion-lock (:id shape))))

        on-position-change
        (fn [event attr]
          (let [cval (-> (dom/get-target event)
                         (dom/get-value)
                         (d/parse-integer))
                pval (get shape attr)
                delta (if (= attr :x)
                        (gpt/point (math/neg (- pval cval)) 0)
                        (gpt/point 0 (math/neg (- pval cval))))]
            (st/emit! (udw/apply-frame-displacement (:id shape) delta)
                      (udw/materialize-frame-displacement (:id shape)))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)]

    [:div.element-set

     [:div.element-set-content

      [:div.row-flex
       [:div.custom-select.flex-grow {:on-click #(reset! show-presets-dropdown? true)}
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
       [:span.orientation-icon {on-click #(on-orientation-clicked :horiz)} i/size-horiz]
       ]


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
                            :on-change on-width-change
                            :value (-> (:width shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]


       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
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
                            :on-change on-pos-x-change
                            :value (-> (:x shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "y"
                            :type "number"
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
  [:div
   [:& measures-menu {:shape shape}]
   [:& fill-menu {:shape shape}]
   [:& stroke-menu {:shape shape}]])
